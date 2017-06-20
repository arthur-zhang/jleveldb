package org.ht.jleveldb;

import java.util.List;

import org.ht.jleveldb.util.ByteBuf;
import org.ht.jleveldb.util.FuncOutput;
import org.ht.jleveldb.util.FuncOutputLong;
import org.ht.jleveldb.util.Slice;

public interface Env {
	/**
	 * The returned file will only be accessed by one thread at a time.
	 * @param fname
	 * @param result
	 * @return
	 */
	Status newSequentialFile(String fname, FuncOutput<SequentialFile> result);
	
	/**
	 * The returned file may be concurrently accessed by multiple threads.
	 * 
	 * @param fname
	 * @param result
	 * @return
	 */
	Status newRandomAccessFile(String fname, FuncOutput<RandomAccessFile0> result);
	 
	/**
	 * The returned file will only be accessed by one thread at a time.
	 * 
	 * @param fname
	 * @param result
	 * @return
	 */
	Status newWritableFile(String fname,  FuncOutput<WritableFile> result);
	
	/**
	 * The returned file will only be accessed by one thread at a time.</br></br>
	 * 
	 * May return an IsNotSupportedError error if this Env does
	 * not allow appending to an existing file.  Users of Env (including
	 * the jleveldb implementation) must be prepared to deal with
	 * an Env that does not support appending.
	 * 
	 * @param fname
	 * @param result
	 * @return
	 */
	Status newAppendableFile(String fname, FuncOutput<WritableFile> result);
	
	/**
	 * Returns true iff the named file exists.
	 * @param fname
	 * @return
	 */
	boolean fileExists(String fname);
	
	/**
	 * Store in result the names of the children of the specified directory.
	 * The names are relative to "dir".
	 *
	 * @param dir
	 * @param result
	 * @return
	 */
	Status getChildren(String dir, List<String> result);
	
	/**
	 * Delete the named file.
	 * @param fname
	 * @return
	 */
	Status deleteFile(String fname);
	
	/**
	 * Create the specified directory.
	 * @param dirname
	 * @return
	 */
	Status createDir(String dirname);
	
	/**
	 * Delete the specified directory.
	 * @param dirname
	 * @return
	 */
	Status deleteDir(String dirname);
	
	/**
	 * Store the size of fname in fileSize.
	 * @param fname
	 * @param fileSize [OUTPUT]
	 * @return
	 */
	Status getFileSize(String fname, FuncOutputLong fileSize);
	
	/**
	 * Rename file src to target.
	 * @param src
	 * @param target
	 * @return
	 */
	Status renameFile(String src, String target);
	
	/**
	 * Lock the specified file.  Used to prevent concurrent access to
	 * the same db by multiple processes.  On failure, stores NULL in
	 * lock and returns non-OK.</br></br>
	 *
	 * On success, stores a pointer to the object that represents the
	 * acquired lock in *lock and returns OK.  The caller should call
	 * UnlockFile(*lock) to release the lock.  If the process exits,
	 * the lock will be automatically released.</br></br>
	 *
	 * If somebody else already holds the lock, finishes immediately
	 * with a failure.  I.e., this call does not wait for existing locks
	 * to go away.</br></br>
	 * 
	 * May create the named file if it does not already exist.
	 * @param fname
	 * @param lock
	 * @return
	 */
	Status lockFile(String fname, FuncOutput<FileLock0> lock);
	
	/**
	 * Release the lock acquired by a previous successful call to LockFile.</br>
	 * 
	 * REQUIRES: lock was returned by a successful LockFile() call</br>
	 * 
	 * REQUIRES: lock has not already been unlocked.</br>
	 * 
	 * @param lock
	 * @return
	 */
	Status unlockFile(FileLock0 lock);
	
	
	// Arrange to run "(*function)(arg)" once in a background thread.
	//
	// "function" may run in an unspecified thread.  Multiple functions
	// added to the same Env may run concurrently in different threads.
	// I.e., the caller may not assume that background work items are
	// serialized.
	void schedule(Runnable r);
	
	// Start a new thread, invoking "function(arg)" within the new thread.
	// When "function(arg)" returns, the thread will be destroyed.
	void startThread(Runnable runnable);
	
	/**
	 *  Create and return a log file for storing informational messages.
	 * @param fname
	 * @param logger
	 * @return
	 */
	Status newLogger(String fname, FuncOutput<Logger0> logger);
	
	/**
	 * Only useful for computing deltas of time.
	 * @return
	 */
	long nowMillis();
	
	/**
	 * Sleep/delay the thread for the prescribed number of micro-seconds.
	 * 
	 * @param micros
	 */
	void sleepForMilliseconds(int millis);
	
	/**
	 * A utility routine: write "data" to the named file.
	 * @param data
	 * @param fname
	 * @return
	 */
	Status writeStringToFile(Slice data, String fname);
	
	
	Status writeStringToFileSync(Slice data, String fname);
	
	/**
	 * A utility routine: read contents of named file into data.
	 * @param fname
	 * @param data [OUTPUT]
	 * @return
	 */
	Status readFileToString(String fname, ByteBuf data);
}
