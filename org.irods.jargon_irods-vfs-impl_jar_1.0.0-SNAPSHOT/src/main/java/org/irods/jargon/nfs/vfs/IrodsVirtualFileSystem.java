/**
 * 
 */
package org.irods.jargon.nfs.vfs;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import javax.security.auth.Subject;

import org.cliffc.high_scale_lib.NonBlockingHashMap;
import org.cliffc.high_scale_lib.NonBlockingHashMapLong;
import org.dcache.nfs.status.NoEntException;
import org.dcache.nfs.v4.NfsIdMapping;
import org.dcache.nfs.v4.SimpleIdMap;
import org.dcache.nfs.v4.xdr.nfsace4;
import org.dcache.nfs.vfs.AclCheckable;
import org.dcache.nfs.vfs.DirectoryEntry;
import org.dcache.nfs.vfs.FsStat;
import org.dcache.nfs.vfs.Inode;
import org.dcache.nfs.vfs.Stat;
import org.dcache.nfs.vfs.Stat.Type;
import org.dcache.nfs.vfs.VirtualFileSystem;
import org.irods.jargon.core.connection.IRODSAccount;
import org.irods.jargon.core.exception.DataNotFoundException;
import org.irods.jargon.core.exception.JargonException;
import org.irods.jargon.core.pub.CollectionAndDataObjectListAndSearchAO;
import org.irods.jargon.core.pub.IRODSAccessObjectFactory;
import org.irods.jargon.core.pub.UserAO;
import org.irods.jargon.core.pub.domain.ObjStat;
import org.irods.jargon.core.pub.domain.User;
import org.irods.jargon.core.pub.io.IRODSFile;
import org.irods.jargon.core.pub.io.IRODSFileFactory;
import org.irods.jargon.nfs.vfs.utils.PermissionBitmaskUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.primitives.Longs;
import java.nio.file.FileAlreadyExistsException;
import java.util.ArrayList;
import org.dcache.nfs.status.ExistException;
import org.dcache.nfs.status.NotSuppException;
import org.dcache.nfs.status.PermException;
import org.dcache.nfs.status.ServerFaultException;


/**
 * iRODS implmentation of the nfs4j virtual file system
 * 
 * @author Mike Conway - NIEHS
 *
 */
public class IrodsVirtualFileSystem implements VirtualFileSystem {
    
      

	private static final Logger log = LoggerFactory.getLogger(IrodsVirtualFileSystem.class);

	private final IRODSAccessObjectFactory irodsAccessObjectFactory;
	private final IRODSAccount rootAccount;
	private final IRODSFile root;
	private final NonBlockingHashMapLong<Path> inodeToPath = new NonBlockingHashMapLong<>();
	private final NonBlockingHashMap<Path, Long> pathToInode = new NonBlockingHashMap<>();
	private final AtomicLong fileId = new AtomicLong(1); // numbering starts at 1
	private final NfsIdMapping _idMapper = new SimpleIdMap();

	/**
	 * Check access to file system object.
	 * <p/>
	 * This method will honor the read/write/execute bits for the user mode and
	 * ignore others TODO: don't know if this even makes sense
	 *
	 * @param inode
	 *            inode of the object to check.
	 * @param mode
	 *            a mask of permission bits to check.
	 * @return an allowed subset of permissions from the given mask.
	 * @throws IOException
	 */
	@Override
	public int access(Inode inode, int mode) throws IOException {
		log.info("access()");

		if (inode == null) {
			throw new IllegalArgumentException("null inode");
		}

		long inodeNumber = this.getInodeNumber(inode);
		// Throws NoEntException if not resolved...
		Path path = this.resolveInode(inodeNumber);

		log.info("path:{}", path);

		int returnMode = 0;
		try {
			IRODSFile pathFile = this.irodsAccessObjectFactory.getIRODSFileFactory(this.resolveIrodsAccount())
					.instanceIRODSFile(path.toString());

			if (PermissionBitmaskUtils.isUserExecuteSet(mode)) {
				log.debug("check user exec");
				if (pathFile.canExecute()) {
					log.debug("determine user can execute");
					returnMode = PermissionBitmaskUtils.turnOnUserExecute(returnMode);
				}
			}

			boolean canWrite = false;
			if (PermissionBitmaskUtils.isUserWriteSet(mode)) {
				log.debug("checking user write");
				canWrite = pathFile.canWrite();
				if (canWrite) {
					log.debug("determine user can write");
					returnMode = PermissionBitmaskUtils.turnOnUserWrite(returnMode);
				}
			}

			if (PermissionBitmaskUtils.isUserReadSet(mode)) {
				log.debug("check user read");
				if (canWrite) {
					log.debug("user already determined to have write");
					returnMode = PermissionBitmaskUtils.turnOnUserRead(returnMode);
				} else if (pathFile.canRead()) {
					log.debug("user can read");
					returnMode = PermissionBitmaskUtils.turnOnUserRead(returnMode);
				}
			}

			log.debug("finished!");
			return returnMode;

		} catch (JargonException e) {
			log.error("exception getting access for path:{}", path, e);
			throw new IOException(e);
		} finally {
			irodsAccessObjectFactory.closeSessionAndEatExceptions();
		}

	}

	/**
	 * Flush data in {@code dirty} state to the stable storage. Typically follows
	 * {@link #write()} operation.
	 *
	 * @param inode
	 *            inode of the file to commit.
	 * @param offset
	 *            the file position to start commit at.
	 * @param count
	 *            number of bytes to commit.
	 * @throws IOException
	 */
	@Override
	public void commit(Inode inode, long offset, int count) throws IOException {
		log.info("commit() is right now a noop, need to think about it");

	}

	/**
	 * Create a new object in a given directory with a specific name.
	 *
	 * @param parent
	 *            directory where new object must be created.
	 * @param type
	 *            the type of the object to be created.
	 * @param name
	 *            name of the object.
	 * @param subject
	 *            the owner subject of a newly created object.
	 * @param mode
	 *            initial permission mask.
	 * @return the inode of the newly created object.
	 * @throws IOException
	 */
	@Override
	public Inode create(Inode parent, Type type, String name, Subject subject, int mode) throws IOException {
		log.info("create()");
		if (parent == null) {
			throw new IllegalArgumentException("null parent");
		}

		if (type == null) {
			throw new IllegalArgumentException("null type");
		}

		if (name == null) {
			throw new IllegalArgumentException("null name");
		}

		if (subject == null) {
			throw new IllegalArgumentException("null subjet");
		}

		log.info("parent:{}", parent);
		log.info("type:{}", type);
		log.info("name:{}", name);
		log.info("subject:{}", subject);
		log.info("mode:{}", mode);

		long parentInodeNumber = getInodeNumber(parent);
		Path parentPath = resolveInode(parentInodeNumber);
		Path newPath = parentPath.resolve(name);

		try {
			IRODSFileFactory irodsFileFactory = this.irodsAccessObjectFactory
					.getIRODSFileFactory(this.resolveIrodsAccount());
			IRODSFile newFile = irodsFileFactory.instanceIRODSFile(newPath.toString());
			log.debug("creating new file at:{}", newFile);
			newFile.createNewFile();
			long newInodeNumber = fileId.getAndIncrement();
			map(newInodeNumber, newPath);
			setOwnershipAndMode(newPath, subject, mode);
			return toFh(newInodeNumber);

		} catch (JargonException e) {
			log.error("error creating new file at path:{}", newPath, e);
			throw new IOException("exception creating new file", e);
		}

	}

	private void setOwnershipAndMode(Path newPath, Subject subject, int mode) {
		log.info("setOwnershipAndMode()"); // TODO: right now a noop

	}

	@Override
	public nfsace4[] getAcl(Inode arg0) throws IOException {
            //info on nfsace4: https://www.ibm.com/support/knowledgecenter/en/ssw_aix_61/com.ibm.aix.osdevice/acl_type_nfs4.htm
		return new nfsace4[0]; // TODO: this is same in local need to look at what nfsace4 is composed of
	}

	@Override
	public AclCheckable getAclCheckable() {
		return AclCheckable.UNDEFINED_ALL;
	}

	@Override
	public FsStat getFsStat() throws IOException {
		log.info("getFsStat()");
		FileStore store = Files.getFileStore(Paths.get(root.getAbsolutePath()));
		long total = store.getTotalSpace();
		long free = store.getUsableSpace();
		return new FsStat(total, Long.MAX_VALUE, total - free, pathToInode.size());
	}

	@Override
	public NfsIdMapping getIdMapper() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Inode getRootInode() throws IOException {
		return toFh(1); // always #1 (see constructor)
	}

	private Inode toFh(long inodeNumber) {
		return Inode.forFile(Longs.toByteArray(inodeNumber));
	}

	/**
	 * Get file system object's attributes.
	 *
	 * @param inode
	 *            inode of the file system object.
	 * @return {@link Stat} with file's attributes.
	 * @throws IOException
	 */
	@Override
	public Stat getattr(Inode inode) throws IOException {
		long inodeNumber = getInodeNumber(inode);
		Path path = resolveInode(inodeNumber);
		return statPath(path, inodeNumber);
	}

	@Override
	public boolean hasIOLayout(Inode arg0) throws IOException {
		System.out.println("hasIOLayout: " + arg0.toString());
		return false;
	}

	@Override
	public Inode link(Inode arg0, Inode arg1, String arg2, Subject arg3) throws IOException {
		 long parentInodeNumber = getInodeNumber(arg0);
        Path parentPath = resolveInode(parentInodeNumber);

        long existingInodeNumber = getInodeNumber(arg1);
        Path existingPath = resolveInode(existingInodeNumber);

        Path targetPath = parentPath.resolve(arg2);

        try {
            Files.createLink(targetPath, existingPath);
        } catch (UnsupportedOperationException e) {
            throw new NotSuppException("Not supported", e);
        } catch (FileAlreadyExistsException e) {
            throw new ExistException("Path exists " + arg2, e);
        } catch (SecurityException e) {
            throw new PermException("Permission denied: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new ServerFaultException("Failed to create: " + e.getMessage(), e);
        }

        long newInodeNumber = fileId.getAndIncrement();
        map(newInodeNumber, targetPath);
        return toFh(newInodeNumber);
	}

	@Override
	public List<DirectoryEntry> list(Inode arg0) throws IOException {
            long inodeNumber = getInodeNumber(arg0);
            Path path = resolveInode(inodeNumber);
            final List<DirectoryEntry> list = new ArrayList<>();
            Files.newDirectoryStream(path).forEach(p -> {
                try {
                    long cookie = resolvePath(p);
                    list.add(new DirectoryEntry(p.getFileName().toString(), toFh(cookie), statPath(p, cookie)));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            return list;
	}
        
        private long resolvePath(Path path) throws NoEntException {
            Long inodeNumber = pathToInode.get(path);
            if (inodeNumber == null) {
                throw new NoEntException("path " + path);
            }
            return inodeNumber;
        }

	@Override
	public Inode lookup(Inode arg0, String arg1) throws IOException {
		System.out.println("lookup: " + arg0.toString());
		return null;
	}

	@Override
	public Inode mkdir(Inode arg0, String arg1, Subject arg2, int arg3) throws IOException {
		// TODO Auto-generated method stub
                System.out.println("mkdir: " + arg0.toString());
		return null;
	}

	@Override
	public boolean move(Inode arg0, String arg1, Inode arg2, String arg3) throws IOException {
		// TODO Auto-generated method stub
                System.out.println("move: " + arg0.toString());
		return false;
	}

	@Override
	public Inode parentOf(Inode arg0) throws IOException {
                System.out.println("parentof: " + arg0.toString());
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public int read(Inode arg0, byte[] arg1, long arg2, int arg3) throws IOException {
		// TODO Auto-generated method stub
                System.out.println("read: " + arg0.toString());
		return 0;
	}

	@Override
	public String readlink(Inode arg0) throws IOException {
		// TODO Auto-generated method stub
                System.out.println("readlink: " + arg0.toString());
		return null;
	}

	@Override
    public void remove(Inode arg0, String arg1) throws IOException {
		// TODO Auto-generated method stub
                System.out.println("remove: " + arg0.toString());

	}

	@Override
	public void setAcl(Inode arg0, nfsace4[] arg1) throws IOException {
		// TODO Auto-generated method stub
                System.out.println("setacl: " + arg0.toString());

	}

	@Override
	public void setattr(Inode arg0, Stat arg1) throws IOException {
		// TODO Auto-generated method stub
                System.out.println("setattr: " + arg0.toString());

	}

	@Override
	public Inode symlink(Inode arg0, String arg1, String arg2, Subject arg3, int arg4) throws IOException {
		// TODO Auto-generated method stub
                System.out.println("symlink: " + arg0.toString());
		return null;
	}

	@Override
	public WriteResult write(Inode arg0, byte[] arg1, long arg2, int arg3, StabilityLevel arg4) throws IOException {
		// TODO Auto-generated method stub
                System.out.println("write: " + arg0.toString());
		return null;
	}

	private void establishRoot() throws DataNotFoundException, JargonException {
		log.info("establishRoot at:{}", root);
		try {
			if (root.exists() && root.canRead()) {
				log.info("root is valid");
			} else {
				throw new DataNotFoundException("cannot establish root at:" + root);
			}

			log.debug("mapping root...");
			map(fileId.getAndIncrement(), root.getAbsolutePath()); // so root is always inode #1

		} finally {
			irodsAccessObjectFactory.closeSessionAndEatExceptions();
		}

	}

	/**
	 * Default constructor
	 * 
	 * @param irodsAccessObjectFactory
	 *            {@link IRODSAccessObjectFactory} with hooks to the core jargon
	 *            system
	 * @param rootAccount
	 *            {@link IRODSAccount} that can access the root node
	 * @param root
	 *            {@link IRODSFile} that is the root node of this file system
	 */
	public IrodsVirtualFileSystem(IRODSAccessObjectFactory irodsAccessObjectFactory, IRODSAccount rootAccount,
			IRODSFile root) throws DataNotFoundException, JargonException {
		super();

		if (irodsAccessObjectFactory == null) {
			throw new IllegalArgumentException("null irodsAccessObjectFactory");
		}

		if (rootAccount == null) {
			throw new IllegalArgumentException("null rootAccount");
		}

		if (root == null) {
			throw new IllegalArgumentException("null root");
		}

		this.irodsAccessObjectFactory = irodsAccessObjectFactory;
		this.rootAccount = rootAccount;
		this.root = root;
		establishRoot();
	}

	private void map(long inodeNumber, String irodsPath) {
		map(inodeNumber, Paths.get(irodsPath));
	}

	private void map(long inodeNumber, Path path) {
		if (inodeToPath.putIfAbsent(inodeNumber, path) != null) {
			throw new IllegalStateException();
		}
		Long otherInodeNumber = pathToInode.putIfAbsent(path, inodeNumber);
		if (otherInodeNumber != null) {
			// try rollback
			if (inodeToPath.remove(inodeNumber) != path) {
				throw new IllegalStateException("cant map, rollback failed");
			}
			throw new IllegalStateException("path ");
		}
	}

	/**
	 * Get the iRODS absolute path given the inode number
	 * 
	 * @param inodeNumber
	 *            <code>long</code> with the inode number
	 * @return {@link Path} that is the inode
	 * @throws NoEntException
	 */
	private Path resolveInode(long inodeNumber) throws NoEntException {
		Path path = inodeToPath.get(inodeNumber);
		if (path == null) {
			throw new NoEntException("inode #" + inodeNumber);
		}
		return path;
	}

	/**
	 * Get a stat relating to the given file path and inode number
	 * 
	 * @param path
	 *            {@link Path} of the file
	 * @param inodeNumber
	 *            <code>long</code> with the inode number
	 * @return {@link Stat} describing the file
	 * @throws IOException
	 */
	private Stat statPath(Path path, long inodeNumber) throws IOException {

		log.info("statPath()");

		if (path == null) {
			throw new IllegalArgumentException("null path");
		}

		/*
		 * wrap in try/catch/finally
		 */

		log.info("path:{}", path);
		log.info("inodeNumber:{}", inodeNumber);

		String irodsAbsPath = path.normalize().toString();
		log.debug("irodsAbsPath:{}", irodsAbsPath);

		try {
			CollectionAndDataObjectListAndSearchAO listAO = this.irodsAccessObjectFactory
					.getCollectionAndDataObjectListAndSearchAO(resolveIrodsAccount());
			ObjStat objStat = listAO.retrieveObjectStatForPath(irodsAbsPath);
			log.debug("objStat:{}", objStat);

			Stat stat = new Stat();

			stat.setATime(objStat.getModifiedAt().getTime());
			stat.setCTime(objStat.getCreatedAt().getTime());
			stat.setMTime(objStat.getModifiedAt().getTime());

			UserAO userAO = this.irodsAccessObjectFactory.getUserAO(resolveIrodsAccount());
			StringBuilder sb = new StringBuilder();
			sb.append(objStat.getOwnerName());
			sb.append("#");
			sb.append(objStat.getOwnerZone());
			User user = userAO.findByName(sb.toString());

			stat.setGid(0); // iRODS does not have a gid
			stat.setUid(Integer.parseInt(user.getId()));
			// TODO right now don't have soft link or mode support
			stat.setMode(PermissionBitmaskUtils.USER_READ | PermissionBitmaskUtils.USER_WRITE);
			stat.setNlink(0);
			stat.setDev(17);
			stat.setIno((int) inodeNumber);
			stat.setRdev(17);
			stat.setSize(objStat.getObjSize());
			stat.setFileid((int) inodeNumber);
			stat.setGeneration(objStat.getModifiedAt().getTime());

			return stat;
		} catch (NumberFormatException | JargonException e) {
			log.error("exception getting stat for path:{}", path, e);
			throw new IOException(e);
		} finally {
			irodsAccessObjectFactory.closeSessionAndEatExceptions();
		}
	}

	private long getInodeNumber(Inode inode) {
		return Longs.fromByteArray(inode.getFileId());
	}

	/**
	 * Stand-in for a method to return the current user or proxy as a given
	 * user...not sure yet how the principal is resolved
	 * 
	 * @return
	 */
	private IRODSAccount resolveIrodsAccount() {
		return rootAccount;
	}

}
