/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tchaicatkovsky.jleveldb.util;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.SyncFailedException;
import java.lang.management.ManagementFactory;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import com.tchaicatkovsky.jleveldb.Env;
import com.tchaicatkovsky.jleveldb.FileLock0;
import com.tchaicatkovsky.jleveldb.Logger0;
import com.tchaicatkovsky.jleveldb.RandomAccessFile0;
import com.tchaicatkovsky.jleveldb.SequentialFile;
import com.tchaicatkovsky.jleveldb.Status;
import com.tchaicatkovsky.jleveldb.WritableFile;

import sun.nio.ch.DirectBuffer;

@SuppressWarnings("restriction")
public class EnvImpl implements Env {
	/**
	 * Helper class to limit resource usage to avoid exhaustion.
	 * Currently used to limit read-only file descriptors and mmap file usage 
	 * so that we do not end up running out of file descriptors, virtual memory,
	 * or running into kernel performance problems for very large databases.
	 */
	class Limiter {
		/** 
		 * Limit maximum number of resources to |n|.
		 * @param n
		 */
		public Limiter(long n) {
			setAllowed(n);
		}
 
		/**
		 *  If another resource is available, acquire it and return true.
		 *  Else return false.
		 * @return
		 */
		public boolean acquire() {
			if (getAllowed() <= 0) {
				return false;
			}
			mu.lock();
			try {
				long x = getAllowed();
				if (x <= 0) {
					return false;
				} else {
					setAllowed(x - 1);
					return true;
				}
			} finally {
				mu.unlock();
			}
		}

		/**
		 * Release a resource acquired by a previous call to Acquire() that returned
		 * true.
		 */
		void release() {
			mu.lock();
			try {
				setAllowed(getAllowed() + 1);
			} finally {
				mu.unlock();
			}
		}

		Mutex mu = new Mutex();
		AtomicLong allowed = new AtomicLong();

		long getAllowed() {
			return allowed.get();
		}

		// REQUIRES: mu_ must be held
		void setAllowed(long v) {
			allowed.set(v);
		}
	};

	static class SequentialFileImpl implements SequentialFile {
		String filename;
		DataInputStream dis;
		FileInputStream fis;

		public SequentialFileImpl(String fname) {
			filename = fname;
		}

		public Status open() {
			try {
				fis = new FileInputStream(new File(filename));
				dis = new DataInputStream(fis);
				return Status.ok0();
			} catch (IOException e) {
				e.printStackTrace();
				return Status.ioError(filename + " SequentialFileImpl.open failed: " + e);
			}
		}

		public void close() {
			try {
				if (dis != null) {
					dis.close();
					dis = null;
					fis.close();
					fis = null;
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		public void delete() {
			close();
		}

		public Status read(int n, Slice result, byte[] scratch) {
			Status s = Status.ok0();
			try {
				int r = dis.read(scratch, 0, n);
				result.init(scratch, 0, r);
				if (r < 0) {
					// We leave status as ok if we hit the end of the file
					result.init(scratch, 0, 0);
				} else if (r < n) {
					if (dis.read(scratch, r, 1) < 0) {

					} else {
						// A partial read with an error: return a non-ok status
						s = Status.ioError(filename + " partial read");
					}
				}
				result.init(scratch, 0, r);
				return s;
			} catch (IOException e) {
				return Status.ioError(filename + " SequentialFileImpl.read failed: " + e);
			}
		}

		public Status skip(long n) {
			try {
				dis.skip(n);
				return Status.ok0();
			} catch (IOException e) {
				return Status.ioError(filename + " SequentialFileImpl.skip failed: " + e.getMessage());
			}
		}
	}

	static class RandomAccessFileImpl implements RandomAccessFile0 {
		String filename;
		DataInputStream dis;

		public RandomAccessFileImpl(String fname) {
			filename = fname;
		}

		public Status open() {
			try {
				dis = new DataInputStream(new FileInputStream(new File(filename)));
				return Status.ok0();
			} catch (FileNotFoundException e) {
				return Status.ioError(filename + " RandomAccessFileImpl.open failed: " + e);
			}
		}

		public void close() {
			try {
				if (dis != null) {
					dis.close();
					dis = null;
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		public void delete() {
			close();
		}

		public String name() {
			return filename;
		}

		public Status read(long offset, int n, Slice result, byte[] scratch) {
			try {
				dis.reset();
				dis.skip(offset);
				int r = dis.read(scratch, 0, n);
				if (r < n)
					return Status.ioError(filename + " RandomAccessFileImpl.read failed: partial read");
				result.init(scratch, 0, r);
				return Status.ok0();
			} catch (IOException e) {
				return Status.ioError(filename + " RandomAccessFileImpl.read failed: " + e);
			}
		}
	}

	static class MmapReadableFile implements RandomAccessFile0 {
		String filename;
		MappedByteBuffer buffer = null;
		RandomAccessFile file = null;
		long fileSize = 0;

		public MmapReadableFile(String fname) {
			filename = fname;
		}

		public Status open() {
			try {
				fileSize = (new File(filename)).length();
				file = new RandomAccessFile(filename, "r");
				buffer = file.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
				return Status.ok0();
			} catch (IOException e) {
				return Status.ioError(filename + " MmapReadableFile.open failed: " + e);
			}
		}

		public void delete() {
			close();
		}

		public void close() {
			try {
				if (file != null) {
					if (buffer != null && ((DirectBuffer) buffer).cleaner() != null)
						((DirectBuffer) buffer).cleaner().clean();
					file.close();
					file = null;
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		public String name() {
			return filename;
		}

		public Status read(long offset, int n, Slice result, byte[] scratch) {
			if (offset + n > fileSize)
				return Status.ioError(filename + " MmapReadableFile.read failed: exceed file size");

			MappedByteBuffer tmpBuf = null;
			try {
				tmpBuf = file.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
				tmpBuf.position((int) offset);
				if (buffer.remaining() < n)
					System.out.printf("[DEBUG] MmapReadableFile.read, remaining=%d, n=%d\n", buffer.remaining(), n);

				tmpBuf.get(scratch, 0, n);
				result.init(scratch, 0, n);
				return Status.ok0();
			} catch (Exception e) {
				return Status.otherError("" + e);
			} finally {
				if (tmpBuf != null) {
					((DirectBuffer) tmpBuf).cleaner().clean();
				}
			}
		}
	}

	static class WritableFileImpl implements WritableFile {
		String filename;
		boolean append;
		FileOutputStream fos;
		DataOutputStream dos;

		public WritableFileImpl(String fname, boolean append) {
			filename = fname;
			this.append = append;
		}

		public Status open() {
			try {
				fos = new FileOutputStream(new File(filename), append);
				dos = new DataOutputStream(fos);
				return Status.ok0();
			} catch (FileNotFoundException e) {
				return Status.ioError(filename + " WritableFileImpl.open failed: " + e);
			}
		}

		public Status append(Slice data) {
			try {
				dos.write(data.data(), data.offset(), data.size());
				return Status.ok0();
			} catch (IOException e) {
				return Status.ioError(filename + " WritableFileImpl.append failed: " + e.getMessage());
			}
		}

		public Status close() {
			try {
				if (fos != null) {
					dos.flush();
					fos.flush();
					fos.close();
					fos = null;
					dos.close();
					dos = null;
					// System.out.println("[DEBUG] WritableFileImpl.close: "+filename);
				}
				return Status.ok0();
			} catch (IOException e) {
				return Status.ioError(filename + " WritableFileImpl.closed failed: " + e.getMessage());
			}
		}

		public Status flush() {
			try {
				dos.flush();
				return Status.ok0();
			} catch (IOException e) {
				return Status.ioError(filename + " WritableFileImpl.flush failed: " + e.getMessage());
			}
		}

		public Status sync() {
			try {
				fos.getFD().sync();
				return Status.ok0();
			} catch (SyncFailedException e) {
				return Status.ioError(filename + " WritableFileImpl.sync failed: " + e.getMessage());
			} catch (IOException e) {
				return Status.ioError(filename + " WritableFileImpl.sync failed: " + e.getMessage());
			}
		}

		public void delete() {
			close();
		}
	}

	static class LockTable {
		Mutex mu = new Mutex();
		TreeSet<String> lockedFiles = new TreeSet<String>();

		public boolean insert(String fname) {
			mu.lock();
			try {
				return lockedFiles.add(fname);
			} finally {
				mu.unlock();
			}
		}

		public void remove(String fname) {
			mu.lock();
			try {
				lockedFiles.remove(fname);
			} finally {
				mu.unlock();
			}
		}
	};

	ReentrantLock mu;
	Condition bgsignal;
	Thread bgthread;
	boolean startedBgthread;

	// Limiter mmapLimit = new Limiter();
	// Limiter fdLimit = new Limiter();

	static int k_open_read_only_file_limit = -1;
	static int k_mmap_limit = -1;

	// Return the maximum number of concurrent mmaps.
	// static int MaxMmaps() {
	// if (k_mmap_limit >= 0) {
	// return k_mmap_limit;
	// }
	// // Up to 1000 mmaps for 64-bit binaries; none for smaller pointer sizes.
	// k_mmap_limit = 8 >= 8 ? 1000 : 0;
	// return k_mmap_limit;
	// }

	public EnvImpl() {
		mu = new ReentrantLock();
		bgsignal = mu.newCondition();
		startedBgthread = false;
	}

	@Override
	public Status newSequentialFile(String fname, Object0<SequentialFile> result) {
		SequentialFileImpl file = new SequentialFileImpl(fname);
		Status s = file.open();
		if (s.ok())
			result.setValue(file);
		else
			result.setValue(null);
		return s;
	}

	@Override
	public Status newRandomAccessFile(String fname, Object0<RandomAccessFile0> result) {
		MmapReadableFile f1 = new MmapReadableFile(fname);
		Status s = f1.open();
		if (s.ok()) {
			result.setValue(f1);
			return s;
		} else {
			RandomAccessFileImpl f2 = new RandomAccessFileImpl(fname);
			if (s.ok()) {
				result.setValue(f2);
				return s;
			} else
				result.setValue(null);
		}
		return s;
	}

	@Override
	public Status newWritableFile(String fname, Object0<WritableFile> result) {
		WritableFileImpl f = new WritableFileImpl(fname, false); // FILE* f = fopen(fname.c_str(), "w");
		Status s = f.open();
		if (s.ok())
			result.setValue(f);
		else
			result.setValue(null);
		return s;
	}

	@Override
	public Status newAppendableFile(String fname, Object0<WritableFile> result) {
		WritableFileImpl f = new WritableFileImpl(fname, true); // FILE* f = fopen(fname.c_str(), "a");
		Status s = f.open();
		if (s.ok())
			result.setValue(f);
		else
			result.setValue(null);
		return s;
	}

	@Override
	public boolean fileExists(String fname) {
		return Files.exists(FileSystems.getDefault().getPath(fname));
	}

	@Override
	public Status getChildren(String dir, List<String> result) {
		File f = new File(dir);
		String[] fileList = f.list();
		if (fileList != null) {
			for (String name : fileList)
				result.add(name);
		}
		return Status.ok0();
	}

	@Override
	public Status deleteFile(String fname) {
		// try {
		// Files.delete(FileSystems.getDefault().getPath(fname));
		// return Status.ok0();
		// } catch (IOException e) {
		// e.printStackTrace();
		// return Status.ioError(fname+" deleteFile failed: "+e);
		// }

		(new File(fname)).delete();
		return Status.ok0();
	}

	@Override
	public Status createDir(String dirname) {
		try {
			Files.createDirectory(FileSystems.getDefault().getPath(dirname));
			return Status.ok0();
		} catch (IOException e) {
			return Status.ioError(dirname + " createDir failed: " + e);
		}
	}

	@Override
	public Status deleteDir(String dirname) {
		try {
			Files.delete(FileSystems.getDefault().getPath(dirname));
			return Status.ok0();
		} catch (IOException e) {
			return Status.ioError(dirname + " deleteDir failed: " + e);
		}
	}

	@Override
	public Status getFileSize(String fname, Long0 fileSize) {
		try {
			long size = Files.size(FileSystems.getDefault().getPath(fname));
			fileSize.setValue(size);
			return Status.ok0();
		} catch (IOException e) {
			return Status.ioError(fname + " getFileSize failed: " + e);
		}
	}

	@Override
	public Status renameFile(String src, String target) {
		try {
			Files.move(FileSystems.getDefault().getPath(src), FileSystems.getDefault().getPath(target), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			return Status.ok0();
		} catch (IOException e) {
			e.printStackTrace();
			return Status.ioError(src + " renameFile to " + target + " failed");
		}
	}

	static class FileLock0Impl extends FileLock0 {
		public FileLock lock;
		public String fname;
		public FileOutputStream fos;

		public FileLock0Impl(String fname, FileLock lock, FileOutputStream fos) {
			this.fname = fname;
			this.lock = lock;
			this.fos = fos;
		}
	}

	@Override
	public Status lockFile(String fname, Object0<FileLock0> lock0) {
		FileOutputStream os = null;
		try {
			os = new FileOutputStream(new File(fname));
			FileChannel channel = os.getChannel();
			FileLock lock = channel.tryLock();
			if (lock == null)
				lock0.setValue(null);
			else
				lock0.setValue(new FileLock0Impl(fname, lock, os));

			return lock0.getValue() != null ? Status.ok0() : Status.otherError(fname + " lockFile failed");
		} catch (Exception e) {
			if (os != null) {
				try {
					os.close();
				} catch (Exception e1) {
					e1.printStackTrace();
				}
			}
			lock0.setValue(null);
			return Status.ioError(fname + " [error] lockFile failed: " + e);
		}
	}

	@Override
	public Status unlockFile(FileLock0 lock) {
		FileLock0Impl l = (FileLock0Impl) lock;
		if (l != null) {
			try {
				l.lock.release();
				l.fos.close();
			} catch (IOException e) {
				return Status.ioError(l.fname + " unlockFile failed: " + e);
			}
		}
		return Status.ok0();
	}

	void BGThread() {
		while (true) {
			// Wait until there is an item that is ready to run
			mu.lock();

			while (queue.isEmpty()) {
				try {
					bgsignal.await();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}

			Runnable item = queue.pollFirst();

			mu.unlock();

			if (item != null)
				item.run();
		}
	}

	LinkedList<Runnable> queue = new LinkedList<Runnable>();

	public class BGThreadRunnable implements Runnable {
		public void run() {
			BGThread();
		}
	}

	@Override
	public void schedule(Runnable r) {
		mu.lock();

		// Start background thread if necessary
		if (!startedBgthread) {
			startedBgthread = true;
			bgthread = new Thread(new BGThreadRunnable());
			bgthread.start();
		}

		// If the queue is currently empty, the background thread may currently be
		// waiting.
		if (queue.isEmpty()) {
			bgsignal.signal();
		}

		// Add to priority queue
		queue.add(r);

		mu.unlock();
	}

	@Override
	public void startThread(Runnable runnable) {
		Thread t = new Thread(runnable);
		t.start();
	}

	static long gettid() {
		return Thread.currentThread().getId();
	}

	@Override
	public Status newLogger(String fname, Object0<Logger0> logger) {
		logger.setValue(new Logger0Impl());
		return Status.ok0();
	}

	static int getPid() {
		String name = ManagementFactory.getRuntimeMXBean().getName();  
		System.out.println(name);  
		// get pid  
		String pid = name.split("@")[0];
		return Integer.parseInt(pid);
	}
	
	@Override
	public Status getTestDirectory(Object0<String> path) {
		path.setValue(String.format("./testdir/leveldbtest-%d", getPid()));
		createDir(path.getValue());
		return Status.ok0();
	}

	@Override
	public long nowMillis() {
		return System.currentTimeMillis();
	}

	@Override
	public void sleepForMilliseconds(int millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	Status doWriteStringToFile(Slice data, String fname, boolean shouldSync) {
		if (fname.contains("MANIFEST"))
			Thread.dumpStack();

		Object0<WritableFile> file0 = new Object0<WritableFile>();
		Status s = newWritableFile(fname, file0);
		if (!s.ok()) {
			return s;
		}
		WritableFile file = file0.getValue();
		s = file.append(data);
		if (s.ok() && shouldSync) {
			s = file.sync();
		}
		if (s.ok()) {
			s = file.close();
		}
		file.delete(); // Will auto-close if we did not close above
		if (!s.ok()) {
			deleteFile(fname);
		}
		return s;
	}

	@Override
	public Status writeStringToFile(Slice data, String fname) {
		return doWriteStringToFile(data, fname, false);
	}

	@Override
	public Status writeStringToFileSync(Slice data, String fname) {
		return doWriteStringToFile(data, fname, true);
	}

	@Override
	public Status readFileToString(String fname, ByteBuf data) {
		DataInputStream is = null;
		try {
			is = new DataInputStream(new FileInputStream(new File(fname)));
			byte[] tmp = new byte[4096];
			while (true) {
				int r = is.read(tmp, 0, tmp.length);
				if (r < 0)
					break;
				else
					data.append(tmp, r);
			}
			return Status.ok0();
		} catch (Exception e) {
			return Status.ioError(fname + " readFileToString failed: " + e.getMessage());
		} finally {
			try {
				if (is != null)
					is.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
}
