/**
 * Copyright (c) 2017-2018, Teng Huang <ht201509 at 163 dot com>
 * All rights reserved.
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tchaicatkovsky.jleveldb.db;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.tchaicatkovsky.jleveldb.Env;
import com.tchaicatkovsky.jleveldb.FileName;
import com.tchaicatkovsky.jleveldb.FileType;
import com.tchaicatkovsky.jleveldb.Iterator0;
import com.tchaicatkovsky.jleveldb.Logger0;
import com.tchaicatkovsky.jleveldb.Options;
import com.tchaicatkovsky.jleveldb.ReadOptions;
import com.tchaicatkovsky.jleveldb.SequentialFile;
import com.tchaicatkovsky.jleveldb.Status;
import com.tchaicatkovsky.jleveldb.WritableFile;
import com.tchaicatkovsky.jleveldb.db.format.DBFormat;
import com.tchaicatkovsky.jleveldb.db.format.InternalKey;
import com.tchaicatkovsky.jleveldb.db.format.InternalKeyComparator;
import com.tchaicatkovsky.jleveldb.table.MergingIterator;
import com.tchaicatkovsky.jleveldb.table.Table;
import com.tchaicatkovsky.jleveldb.table.TwoLevelIterator;
import com.tchaicatkovsky.jleveldb.util.Boolean0;
import com.tchaicatkovsky.jleveldb.util.ByteBuf;
import com.tchaicatkovsky.jleveldb.util.ByteBufFactory;
import com.tchaicatkovsky.jleveldb.util.IntLongPair;
import com.tchaicatkovsky.jleveldb.util.ListUtils;
import com.tchaicatkovsky.jleveldb.util.Long0;
import com.tchaicatkovsky.jleveldb.util.Mutex;
import com.tchaicatkovsky.jleveldb.util.Object0;
import com.tchaicatkovsky.jleveldb.util.Slice;
import com.tchaicatkovsky.jleveldb.util.SliceFactory;

/**
 * The representation of a DBImpl consists of a set of Versions.  The
 * newest version is called "current".  Older versions may be kept
 * around to provide a consistent view to live iterators.</br></br>
 *
 * Each Version keeps track of a set of Table files per level.  The
 * entire set of versions is maintained in a VersionSet.</br></br>
 *
 * Version,VersionSet are thread-compatible, but require external
 * synchronization on all accesses.</br></br>
 *
 */
public class VersionSet {
	
	Env env;
	String dbname;
	Options options;
	public TableCache tableCache;
	InternalKeyComparator icmp;
	long nextFileNumber;
	long manifestFileNumber;
	long lastSequence;
	long logNumber;
	long prevLogNumber;  // 0 or backing store for memtable being compacted
	
	// Opened lazily
	/**
	 * MANIFEST file
	 */
	WritableFile descriptorFile;
	/**
	 * MANIFEST log writer
	 */
	LogWriter descriptorLog;
	Version dummyVersions; // Head of circular doubly-linked list of versions.
	Version current; // == dummyVersions.prev
	
	/**
	 *  Per-level key at which the next compaction at that level should start.
	 *  Either an empty string, or a valid InternalKey.
	 */
	ByteBuf[] compactPointer = new ByteBuf[DBFormat.kNumLevels];
	
	public VersionSet(String dbname, Options options, TableCache tableCache, InternalKeyComparator cmp) {
		env = options.env;
	    this.dbname = dbname;
	    this.options = options.cloneOptions();
	    this.tableCache = tableCache;
	    icmp = cmp;
	    nextFileNumber = 2;
	    manifestFileNumber = 0;  // Filled by recover()
	    lastSequence = 0;
	    logNumber = 0;
	    prevLogNumber = 0;
	    descriptorFile = null;
	    descriptorLog = null;
	    dummyVersions = new Version(this);
	    current = null;
	    
	    compactPointer = new ByteBuf[DBFormat.kNumLevels];
	    for (int i = 0; i < DBFormat.kNumLevels; i++) {
	    	compactPointer[i] = ByteBufFactory.newUnpooled(); 
	    }
	    
	    appendVersion(new Version(this));
	}
	
	public void delete() {
		current.unref();
		assert(dummyVersions.next == dummyVersions);  // List must be empty
		if (descriptorLog != null) {
			descriptorLog.delete();
			descriptorLog = null;
		}
		if (descriptorFile != null) {
			descriptorFile.delete();
			descriptorFile = null;
		}
	}
	
	int versionRef1 = 0;
	int versionRef2 = 0;
	public void incrVersionRef() {
		versionRef1++;
	}
	
	public void decrVersionRef() {
		versionRef2++;
	}
	
	void appendVersion(Version v) {
		// Make "v" current
		assert(v.refs == 0);
		assert(v != current);
		if (current != null) {
		    current.unref();
		}
		current = v;
		v.ref();
		
		// Append to linked list
		v.prev = dummyVersions.prev;
		v.next = dummyVersions;
		v.prev.next = v;
		v.next.prev = v;
	}
	
	static class Builder {
		
		static class BySmallestKey implements Comparator<FileMetaData> {
			InternalKeyComparator ikcmp;
			public BySmallestKey(InternalKeyComparator ikcmp) {
				this.ikcmp = ikcmp;
			}
			
			public int compare(FileMetaData a, FileMetaData b) {
				int r = ikcmp.compare(a.smallest, b.smallest);
			    if (r != 0) {
			        return r;
			    } else {
			        // Break ties by file number
			        return Long.compare(a.number, b.number);
			    }
			}
		}
		
		static class  LevelState {
		    TreeSet<Long> deletedFiles;
		    TreeSet<FileMetaData> addedFiles;
		};
		
		VersionSet vset;
		Version base;
		LevelState[] levels = new LevelState[DBFormat.kNumLevels];
		BySmallestKey cmp;
		
		public Builder(VersionSet vset, Version base) {
			this.vset = vset;
			this.base = base;
			base.ref();
			cmp = new BySmallestKey(vset.icmp);
			for (int level = 0; level < DBFormat.kNumLevels; level++) {
				levels[level] = new LevelState();
				levels[level].addedFiles = new TreeSet<FileMetaData>(cmp);
				levels[level].deletedFiles = new TreeSet<Long>();
			}
		}
		
		public void delete() {
			for (int level = 0; level < DBFormat.kNumLevels; level++) {
				TreeSet<FileMetaData> added = levels[level].addedFiles;
			    ArrayList<FileMetaData> toUnref = new ArrayList<>();
			    //toUnref.reserve(added->size());
			    for (FileMetaData fmd : added) {
			        toUnref.add(fmd);
			    }
			    added.clear();
			    for (int i = 0; i < toUnref.size(); i++) {
			        FileMetaData f = toUnref.get(i);
			        f.refs--;
			        if (f.refs <= 0) {
			        	f.delete();// delete f;
			        }
			    }
			}
			base.unref();
		}
		
		/**
		 * Apply all of the edits in edit to the current state.
		 * @param edit
		 */
		public void apply(VersionEdit edit) {
		    // Update compaction pointers
		    for (int i = 0; i < edit.compactPointers.size(); i++) {
		    	int level = edit.compactPointers.get(i).i;
		    	Slice tmp = edit.compactPointers.get(i).obj.encode();
		    	vset.compactPointer[level].assign(tmp.data(), tmp.offset(), tmp.size());
		    }

		    // Delete files
		    HashSet<IntLongPair> del = edit.deletedFiles;
		    for (IntLongPair p : del) {
		    	int level = p.i;
		    	long number = p.l;
		    	levels[level].deletedFiles.add(number);
		    }

		    // Add new files
		    for (int i = 0; i < edit.newFiles.size(); i++) {
		    	int level = edit.newFiles.get(i).i;
		    	FileMetaData f = edit.newFiles.get(i).obj.clone();
		    	f.refs = 1;

		        // We arrange to automatically compact this file after
		        // a certain number of seeks.  Let's assume:
		        //   (1) One seek costs 10ms
		        //   (2) Writing or reading 1MB costs 10ms (100MB/s)
		        //   (3) A compaction of 1MB does 25MB of IO:
		        //         1MB read from this level
		        //         10-12MB read from next level (boundaries may be misaligned)
		        //         10-12MB written to next level
		        // This implies that 25 seeks cost the same as the compaction
		        // of 1MB of data.  I.e., one seek costs approximately the
		        // same as the compaction of 40KB of data.  We are a little
		        // conservative and allow approximately one seek for every 16KB
		        // of data before triggering a compaction.
		    	f.allowedSeeks = (int)(f.fileSize / 16384);
		    	if (f.allowedSeeks < 100)
		    		f.allowedSeeks = 100;

		    	levels[level].deletedFiles.remove(f.number);
		    	levels[level].addedFiles.add(f);
		    }
		}
		
		public void dumpFileMetaDataList(int level, ArrayList<FileMetaData> flist) {
			String s = "level="+level+", flist=";
			for (int i = 0; i < flist.size(); i++) {
				FileMetaData f = flist.get(i);
				s += String.format("{number:%d,fileSize:%d,smallest:%s,largest:%s}, ", 
						f.number, f.fileSize,f.smallest.debugString(), f.largest.debugString());
			}
			
			System.out.println("[DEBUG] flist="+s);
		}
		
		public void saveTo(Version v) {
		    BySmallestKey cmp = new BySmallestKey(vset.icmp);
		    for (int level = 0; level < DBFormat.kNumLevels; level++) {
		    	// Merge the set of added files with the set of pre-existing files.
		    	// Drop any deleted files.  Store the result in v.
		    	ArrayList<FileMetaData> baseFiles = base.levelFiles(level);
		    	//dumpFileMetaDataList(level, baseFiles);
		    	TreeSet<FileMetaData> added = levels[level].addedFiles;
		    	
		    	final int baseFilesSize = baseFiles.size();
		    	int startIdx = 0;
		    	for (FileMetaData fmd : added) {
		    		// Add all smaller files listed in base
		    		int endIdx = ListUtils.lowerBound(baseFiles, fmd, cmp);
		    		
		    		for (int i = startIdx; i < endIdx && i < baseFilesSize; i++)
		    			maybeAddFile(v, level, baseFiles.get(i));
		    		
		    		startIdx = endIdx;
		    		maybeAddFile(v, level, fmd);
		    	}
		    	
		    	// Add remaining base files
		    	for (; startIdx < baseFilesSize; startIdx++)
		    		maybeAddFile(v, level, baseFiles.get(startIdx));
		    }
		}
		
		static <T> int count(TreeSet<T> set, T v) {
			return set.contains(v) ? 1 : 0;
		}
		
		public void maybeAddFile(Version v, int level, FileMetaData f) {
			if (count(levels[level].deletedFiles, f.number) > 0) {
		        // File is deleted: do nothing
			} else {
		        ArrayList<FileMetaData> files = v.levelFiles(level);
		        if (level > 0 && !files.isEmpty()) {
		        	// Must not overlap
		        	int ret = vset.icmp.compare(files.get(files.size()-1).largest, f.smallest);
		        	assert(ret < 0);
		        }
		        f.refs++;
		        files.add(f.clone());
		    }
		}
	}
	
	
	/**
	 * Apply edit to the current version to form a new descriptor that
	 * is both saved to persistent state and installed as the new
	 * current version.  Will release mu while actually writing to the file.
	 * </br></br>
	 * 
	 * REQUIRES: mu is held on entry.</br>
	 * REQUIRES: no other thread concurrently calls logAndApply()
	 * 
	 * @param edit
	 * @param mu
	 * @return
	 */
	public Status logAndApply(VersionEdit edit, Mutex mu) {
		if (edit.hasLogNumber) {
			assert(edit.logNumber >= logNumber);
			assert(edit.logNumber < nextFileNumber);
		} else {
			edit.setLogNumber(logNumber);
		}

		if (!edit.hasPrevLogNumber) {
			edit.setPrevLogNumber(prevLogNumber);
		}
		edit.setNextFile(nextFileNumber);
		edit.setLastSequence(lastSequence);
		
		Version v = new Version(this);
		{
			Builder builder = new Builder(this, current);
			builder.apply(edit);
			builder.saveTo(v);
			builder.delete();
			builder = null;
		}
		finalize(v);

		// Initialize new descriptor log file if necessary by creating
		// a temporary file that contains a snapshot of the current version.
		String newManifestFile = null;
		Status s = Status.ok0();
		if (descriptorLog == null) {
			// No reason to unlock mu here since we only hit this path in the
			// first call to logAndApply (when opening the database).
			assert(descriptorFile == null);
			newManifestFile = FileName.getDescriptorFileName(dbname, manifestFileNumber);
			edit.setNextFile(nextFileNumber);
			Object0<WritableFile> descriptorFile0 = new Object0<WritableFile>();
			s = env.newWritableFile(newManifestFile, descriptorFile0);
			descriptorFile = descriptorFile0.getValue();
			if (s.ok()) {
				descriptorLog = new LogWriter(descriptorFile);
			    s = writeSnapshot(descriptorLog);
			}
		}
		
		// Unlock during expensive MANIFEST log write
		{
			mu.unlock();

			// Write new record to MANIFEST log
			if (s.ok()) {
				ByteBuf record = ByteBufFactory.newUnpooled();
			    edit.encodeTo(record);
			    s = descriptorLog.addRecord(SliceFactory.newUnpooled(record));
			    if (s.ok()) {
			    	s = descriptorFile.sync();
			    }
			    if (!s.ok()) {
			        Logger0.log0(options.infoLog, "MANIFEST write: {}\n", s);
			    }
			}

			// If we just created a new descriptor file, install it by writing a
			// new CURRENT file that points to it.
			if (s.ok() && newManifestFile != null && !newManifestFile.isEmpty()) {
				s = FileName.setCurrentFile(env, dbname, manifestFileNumber);
			}

			mu.lock();
		}

		// Install the new version
		if (s.ok()) {
			appendVersion(v);
			logNumber = edit.logNumber;
			prevLogNumber = edit.prevLogNumber;
		} else {
			v.delete();
			v = null;
			if (newManifestFile != null && !newManifestFile.isEmpty()) {
				descriptorLog.delete();
			   	descriptorFile.delete();
			    descriptorLog = null;
			    descriptorFile = null;
			    env.deleteFile(newManifestFile);
			}
		}

		return s;
	}
	
	
	static class LogReporter implements LogReader.Reporter {
		Status status;
		public void corruption(int bytes, Status s) {
			if (this.status.ok()) 
				this.status = s;
		}
	};
	
	/**
	 * Recover the last saved descriptor from persistent storage.
	 * @return saveManifest
	 */
	public Status recover(Boolean0 saveManifest) {
		// Read "CURRENT" file, which contains a pointer to the current manifest file
		ByteBuf currentFileName = ByteBufFactory.newUnpooled();
		Status s = env.readFileToString(FileName.getCurrentFileName(dbname), currentFileName);
		if (!s.ok()) {
			return s;
		}
		if (currentFileName.empty() || currentFileName.getByte(currentFileName.size()-1) != (byte)(0x0A)/*\n*/)
			return Status.corruption("CURRENT file does not end with newline");
		currentFileName.resize(currentFileName.size() - 1);
		ByteBuf dataBuf = ByteBufFactory.newUnpooled();
		env.readFileToString(currentFileName.encodeToString(), dataBuf);
		
		String dscname = dbname + "/" + currentFileName.encodeToString();
		Object0<SequentialFile> file0 = new Object0<SequentialFile>();
		s = env.newSequentialFile(dscname, file0);
		if (!s.ok()) {
		    return s;
		}
		SequentialFile file = file0.getValue();
		
		boolean haveLogNumber0 = false;
		boolean havePrevLogNumber0 = false;
		boolean haveNextFile0 = false;
		boolean haveLastSequence0 = false;
		long nextFile0 = 0;
		long lastSequence0 = 0;
		long logNumber0 = 0;
		long prevLogNumber0 = 0;
		  
		Builder builder = new Builder(this, current);
		try {
			try {
				LogReporter reporter = new LogReporter();
				reporter.status = s;
				LogReader reader = new LogReader(file, reporter, true, 0);
				Slice record = SliceFactory.newUnpooled();
				ByteBuf scratch = ByteBufFactory.newUnpooled();
				while (reader.readRecord(record, scratch) && s.ok()) {
					VersionEdit edit = new VersionEdit();
				    s = edit.decodeFrom(record);
				    if (s.ok()) {
				        if (edit.hasComparator && !edit.comparator.equals(icmp.userComparator().name())) {
				        	s = Status.invalidArgument(edit.comparator + 
				        			" does not match existing comparator " + 
				        			icmp.userComparator().name());
				        }
				    }
				    
				    if (s.ok()) {
				        builder.apply(edit);
				    }
				    
				    if (edit.hasLogNumber) {
				        logNumber0 = edit.logNumber;
				        haveLogNumber0 = true;
				    }
				    
				    if (edit.hasPrevLogNumber) {
				        prevLogNumber0 = edit.prevLogNumber;
				        havePrevLogNumber0 = true;
				    }
	
				    if (edit.hasNextFileNumber) {
				        nextFile0 = edit.nextFileNumber;
				        haveNextFile0 = true;
				    }
				    
				    if (edit.hasLastSequence) {
				        lastSequence0 = edit.lastSequence;
				        haveLastSequence0 = true;
				    }
				}
			} finally {
				file.delete();
				file = null;
			}
		
		
			if (s.ok()) {
			    if (!haveNextFile0) {
			    	s = Status.corruption("no meta-nextfile entry in descriptor");
			    } else if (!haveLogNumber0) {
			    	s = Status.corruption("no meta-lognumber entry in descriptor");
			    } else if (!haveLastSequence0) {
			    	s = Status.corruption("no last-sequence-number entry in descriptor");
			    }
	
			    if (!havePrevLogNumber0) {
			    	prevLogNumber0 = 0;
			    }
	
			    markFileNumberUsed(prevLogNumber0);
			    markFileNumberUsed(logNumber0);
			}
		
			if (s.ok()) {
			    Version v = new Version(this);
			    builder.saveTo(v);
			    // Install recovered version
			    finalize(v);
			    appendVersion(v);
			    manifestFileNumber = nextFile0;
			    nextFileNumber = nextFile0 + 1;
			    lastSequence = lastSequence0;
			    logNumber = logNumber0;
			    prevLogNumber = prevLogNumber0;
	
			    // See if we can reuse the existing MANIFEST file.
			    if (reuseManifest(dscname, currentFileName.encodeToString())) {
			    	// No need to save new manifest
			    } else {
			    	saveManifest.setValue(true);
			    }
			}
			return s;
		} finally {
			if (builder != null) {
				builder.delete();
				builder = null;
			}
		}
	}
	
	/**
	 * Return the current version.
	 */
	public Version current() {
		return current;
	}
	
	/**
	 * Return the current manifest file number
	 * @return
	 */
	public long manifestFileNumber() { 
		return manifestFileNumber; 
	}
	
	/**
	 * Allocate and return a new file number
	 * @return
	 */
	public long newFileNumber() {
		return nextFileNumber++; 
	}
	
	/**
	 * Arrange to reuse "fileNumber" unless a newer file number has
	 * already been allocated. </br></br>
	 * 
	 * REQUIRES: "fileNumber" was returned by a call to newFileNumber().
	 * @param file_number
	 */
	public void reuseFileNumber(long fileNumber) {
	    if (nextFileNumber == fileNumber + 1) {
	      nextFileNumber = fileNumber;
	    }
	}
	
	public int numLevelFiles(int level) {
		assert(level >= 0);
		assert(level < DBFormat.kNumLevels);
		return current.levelFiles(level).size();
	}
	  
	/**
	 * Return the combined file size of all files at the specified level.
	 * @param level
	 * @return
	 */
	public long numLevelBytes(int level) {
		assert(level >= 0);
		assert(level < DBFormat.kNumLevels);
		return VersionSetGlobal.totalFileSize(current.levelFiles(level));
	}
	
	/**
	 * Return the last sequence number.
	 * @return
	 */
	public long lastSequence() {
		return lastSequence; 
	}
	
	/**
	 * Set the last sequence number to s.
	 * @param s
	 */
	public void setLastSequence(long s) {
	    assert(s >= lastSequence);
	    lastSequence = s;
	}
	
	/**
	 * Mark the specified file number as used.
	 * @param number
	 */
	public void markFileNumberUsed(long number) {
		if (nextFileNumber <= number) {
			nextFileNumber = number + 1;
		}
	}
	
	/**
	 * Return the current log file number.
	 * @return
	 */
	public long logNumber() { 
		return logNumber;
	}
	
	/**
	 * Return the log file number for the log file that is currently
	 * being compacted, or zero if there is no such log file.
	 * @return
	 */
	public long prevLogNumber() {
		return prevLogNumber;
	}
	

	/**
	 * Pick level and inputs for a new compaction.
	 * 
	 * @return Returns null if there is no compaction to be done.
	 */
	public Compaction pickCompaction() {
		Compaction c;
		int level;

		// We prefer compactions triggered by too much data in a level over
		// the compactions triggered by seeks.
		final boolean sizeCompaction = (current.compactionScore >= 1.0);
		final boolean seekCompaction = (current.fileToCompact != null);
		if (sizeCompaction) {
		    level = current.compactionLevel;
		    assert(level >= 0);
		    assert(level+1 < DBFormat.kNumLevels);
		    c = new Compaction(options, level);

		    // Pick the first file that comes after compactPointer[level]
		    for (int i = 0; i < current.levelFiles(level).size(); i++) {
		    	FileMetaData f = current.levelFiles(level).get(i);
		    	if (compactPointer[level].empty() ||
		    			icmp.compare(f.largest.encode(), compactPointer[level]) > 0) {
		    		c.input(0).add(f);
		    		break;
		    	}
		    }
		    if (c.input(0).isEmpty()) {
		    	// Wrap-around to the beginning of the key space
		    	c.input(0).add(current.levelFiles(level).get(0));
		    }
		} else if (seekCompaction) {
		    level = current.fileToCompactLevel;
		    c = new Compaction(options, level);
		    c.input(0).add(current.fileToCompact);
		} else {
		    return null;
		}

		c.inputVersion = current;
		c.inputVersion.ref();

		// Files in level 0 may overlap each other, so pick up all overlapping ones
		if (level == 0) {
		    InternalKey smallest = new InternalKey();
		    InternalKey largest = new InternalKey();
		    getRange(c.input(0), smallest, largest);
		    // Note that the next call will discard the file we placed in
		    // c.inputs[0] earlier and replace it with an overlapping set
		    // which will include the picked file.
		    current.getOverlappingInputs(0, smallest, largest, c.input(0));
		    assert(!c.input(0).isEmpty());
		}

		setupOtherInputs(c);

		return c;
	}
	
	/**
	 * Return a compaction object for compacting the range [begin,end] in
	 * the specified level. Caller should delete
	 * the result.
	 * 
	 * @param level
	 * @param begin
	 * @param end
	 * @return null if there is nothing in that
	 * level that overlaps the specified range.
	 */
	public static void resize(ArrayList<FileMetaData> inputs, int newSize) {
		if (inputs.size() == newSize)
			return;
		
		if (inputs.size() > newSize) {
			int delCnt = inputs.size() - newSize;
			for (int i = 0; i < delCnt; i++) {
				FileMetaData f = inputs.remove(inputs.size()-1);
				f.delete();
			}
		} else {
			int addCnt = newSize - inputs.size();
			for (int i = 0; i < addCnt; i++)
				inputs.add(new FileMetaData());
		}
	}
	public Compaction compactRange(int level, InternalKey begin, InternalKey end) {
		ArrayList<FileMetaData> inputs = new ArrayList<FileMetaData>();
		current.getOverlappingInputs(level, begin, end, inputs);
		if (inputs.isEmpty()) {
		    return null;
		}
		
		// Avoid compacting too much in one shot in case the range is large.
		// But we cannot do this for level-0 since level-0 files can overlap
		// and we must not pick one file and drop another older file if the
		// two files overlap.
		if (level > 0) {
			final long limit = VersionSetGlobal.maxFileSizeForLevel(options, level);
		    long total = 0;
		    for (int i = 0; i < inputs.size(); i++) {
		    	long s = inputs.get(i).fileSize;
		    	total += s;
		    	if (total >= limit) {
		    		resize(inputs, i + 1); //inputs.resize(i + 1)
		    		break;
		    	}
		    }
		}
		
		Compaction c = new Compaction(options, level);
		c.inputVersion = current;
		c.inputVersion.ref();
		c.inputs[0] = inputs;
		setupOtherInputs(c);
		return c;
	}
	
	/**
	 * Return the maximum overlapping data (in bytes) at next level for any
	 * file at a level >= 1.
	 * @return
	 */
	public long maxNextLevelOverlappingBytes() {
		long result = 0;
		ArrayList<FileMetaData> overlaps = new ArrayList<FileMetaData>();
		for (int level = 1; level < DBFormat.kNumLevels - 1; level++) {
		    for (int i = 0; i < current.levelFiles(level).size(); i++) {
		    	FileMetaData f = current.levelFiles(level).get(i);
		    	current.getOverlappingInputs(level+1, f.smallest, f.largest, overlaps);
		    	long sum = VersionSetGlobal.totalFileSize(overlaps);
		    	if (sum > result) {
		    		result = sum;
		    	}
		    }
		}
		return result;
	}
	
	/**
	 *  Create an iterator that reads over the compaction inputs for "c".
	 *  The caller should delete the iterator when no longer needed.
	 * @param c
	 * @return
	 */
	public Iterator0 makeInputIterator(Compaction c) {
		ReadOptions opt = new ReadOptions();
		opt.verifyChecksums = options.paranoidChecks;
		opt.fillCache = false;

		// Level-0 files have to be merged together.  For other levels,
		// we will make a concatenating iterator per level.
		// TODO(opt): use concatenating iterator for level-0 if there is no overlap
		final int space = (c.level() == 0 ? c.input(0).size() + 1 : 2);
		List<Iterator0> list = new ArrayList<>();
		
		for (int which = 0; which < 2; which++) {
		    if (!c.input(which).isEmpty()) {
		    	if (c.level() + which == 0) {
		    		final ArrayList<FileMetaData> files = c.input(which);
		    		for (int i = 0; i < files.size(); i++) {
		    			list.add( tableCache.newIterator(opt, files.get(i).number, files.get(i).fileSize) );
		    		}
		    	} else {
		    		// Create concatenating iterator for the files from this level
		    		list.add( TwoLevelIterator.newTwoLevelIterator(
		    				new Version.LevelFileNumIterator(icmp, c.level() + which, c.input(which)), 
		    				VersionSetGlobal.getFileIterator, tableCache, opt) );
		    	}
		    }
		}
		
		assert(list.size() <= space);
		Iterator0 result = MergingIterator.newMergingIterator(icmp, list);
		list = null; //delete[] list;
		return result;
	}
	
	/**
	 *  Returns true iff some level needs a compaction.
	 * @return
	 */
	public boolean needsCompaction() {
	    Version v = current();
	    return (v.compactionScore >= 1.0) || (v.fileToCompact != null);
	}
	
	/**
	 *  Add all files listed in any live version to *live.
	 *  May also mutate some internal state.
	 */
	public void addLiveFiles(Set<Long> live) {
		for (Version v = dummyVersions.next; v != dummyVersions; v = v.next) {
			for (int level = 0; level < DBFormat.kNumLevels; level++) {
				ArrayList<FileMetaData> files = v.levelFiles(level);
			    for (int i = 0; i < files.size(); i++) {
			    	live.add(files.get(i).number);
			    }
			}
		 }
	}
	
	/**
	 *  Return the approximate offset in the database of the data for
	 *  "key" as of version "v".
	 * @return
	 */
	public long approximateOffsetOf(Version v, InternalKey ikey) {
		long result = 0;
		for (int level = 0; level < DBFormat.kNumLevels; level++) {
			ArrayList<FileMetaData> files = v.levelFiles(level);
		    for (int i = 0; i < files.size(); i++) {
		    	if (icmp.compare(files.get(i).largest, ikey) <= 0) {
		    		// Entire file is before "ikey", so just add the file size
		    		result += files.get(i).fileSize;
		    	} else if (icmp.compare(files.get(i).smallest, ikey) > 0) {
		    		// Entire file is after "ikey", so ignore
		    		if (level > 0) {
		    			// Files other than level 0 are sorted by meta->smallest, so
		    			// no further files in this level will contain data for
		    			// "ikey".
		    			break;
		    		}
		    	} else {
		    		// "ikey" falls in the range for this table.  Add the
		    		// approximate offset of "ikey" within the table.
		    		Object0<Table> table0 = new Object0<Table>();
		    		Iterator0 iter = tableCache.newIterator(new ReadOptions(), 
		    				files.get(i).number, files.get(i).fileSize, table0);
		    		if (table0.getValue() != null) {
		    			result += table0.getValue().approximateOffsetOf(ikey.encode());
		    		}
		    		iter.delete();
		    		iter = null;
		    	}
		    }
		}
		return result;
	}
	
	public String levelSummary() {
		// Update code if kNumLevels changes
		//assert(DBFormat.kNumLevels == 7);
		return String.format("files[ %d %d %d %d %d %d %d ]", 
		           current.levelFiles(0).size(),
		           current.levelFiles(1).size(),
		           current.levelFiles(2).size(),
		           current.levelFiles(3).size(),
		           current.levelFiles(4).size(),
		           current.levelFiles(5).size(),
		           current.levelFiles(6).size());
	}
	
	
	boolean reuseManifest(String dscname, String dscbase) {
		if (!options.reuseLogs)
			return false;
		
		Object0<FileType> manifestType = new Object0<FileType>();
		Long0 manifestNumber = new Long0();
		Long0 manifestSize = new Long0();
		if (!FileName.parseFileName(dscbase, manifestNumber, manifestType) ||
			      manifestType.getValue() != FileType.DescriptorFile ||
			      !env.getFileSize(dscname, manifestSize).ok() ||
			      // Make new compacted MANIFEST if old one is too big
			      manifestSize.getValue() >= VersionSetGlobal.targetFileSize(options)) {
			return false;
		}

		assert(descriptorFile == null);
		assert(descriptorLog == null);
		Object0<WritableFile> descriptorFile0 = new Object0<WritableFile>();
		Status r = env.newAppendableFile(dscname, descriptorFile0);
		descriptorFile = descriptorFile0.getValue();
		if (!r.ok()) {
			Logger0.log0(options.infoLog, "Reuse MANIFEST: %s\n", r);
			assert(descriptorFile == null);
			return false;
		}
		
		Logger0.log0(options.infoLog, "Reusing MANIFEST %s\n", dscname);
		descriptorLog = new LogWriter(descriptorFile, manifestSize.getValue());
		manifestFileNumber = manifestNumber.getValue();
		return true;
	}
	
	void finalize(Version v) {
		// Precomputed best level for next compaction
		int bestLevel = -1;
		double bestScore = -1.0;

		for (int level = 0; level < DBFormat.kNumLevels-1; level++) {
		    double score;
		    if (level == 0) {
		      // We treat level-0 specially by bounding the number of files
		      // instead of number of bytes for two reasons:
		      //
		      // (1) With larger write-buffer sizes, it is nice not to do too
		      // many level-0 compactions.
		      //
		      // (2) The files in level-0 are merged on every read and
		      // therefore we wish to avoid too many files when the individual
		      // file size is small (perhaps because of a small write-buffer
		      // setting, or very high compression ratios, or lots of
		      // overwrites/deletions).
		      score = v.levelFiles(level).size() /
		          (double)(DBFormat.kL0_CompactionTrigger);
		    } else {
		    	// Compute the ratio of current size to size limit.
		    	long levelBytes = VersionSetGlobal.totalFileSize(v.levelFiles(level));
		    	score = (double)(levelBytes) / VersionSetGlobal.maxBytesForLevel(options, level);
		    }

		    if (score > bestScore) {
		    	bestLevel = level;
		    	bestScore = score;
		    }
		}

		v.compactionLevel = bestLevel;
		v.compactionScore = bestScore;
	}
	
	void getRange(ArrayList<FileMetaData> inputs,
            InternalKey smallest,
            InternalKey largest) {
		assert(!inputs.isEmpty());
		smallest.clear();
		largest.clear();
		for (int i = 0; i < inputs.size(); i++) {
		    FileMetaData f = inputs.get(i);
		    if (i == 0) {
		    	smallest.assgin(f.smallest); //*smallest = f.smallest;
		        largest.assgin(f.largest); //*largest = f->largest;
		    } else {
		    	if (icmp.compare(f.smallest, smallest) < 0) {
		    		smallest.assgin(f.smallest); //*smallest = f->smallest;
		    	}
		    	if (icmp.compare(f.largest, largest) > 0) {
		    		largest.assgin(f.largest); //*largest = f->largest;
		    	}
		    }
		}
	}
	
	void getRange2(ArrayList<FileMetaData> inputs1,
            List<FileMetaData> inputs2,
            InternalKey smallest,
            InternalKey largest) {
		ArrayList<FileMetaData> all = new ArrayList<>();
		all.addAll(inputs1);
		all.addAll(inputs2);
		getRange(all, smallest, largest);
	}
	
	String dumpFileNumberList(ArrayList<FileMetaData> l) {
		String s = "";
		for (int i = 0; i < l.size(); i++) {
			if (s.length() > 0)
				s += ",";
			s += l.get(i).number;
		}
		return s;
	}
	
	void setupOtherInputs(Compaction c) {
		int level = c.level();
		InternalKey smallest = new InternalKey();
		InternalKey largest = new InternalKey();
		getRange(c.input(0), smallest, largest);
		
		current.getOverlappingInputs(level+1, smallest, largest, c.input(1));
		
		// Get entire range covered by compaction
		InternalKey allStart = new InternalKey();
		InternalKey allLimit = new InternalKey();
		getRange2(c.input(0), c.input(1), allStart, allLimit);
		
		// See if we can grow the number of inputs in "level" without
		// changing the number of "level+1" files we pick up.
		if (!c.input(1).isEmpty()) {
			ArrayList<FileMetaData> expanded0 = new ArrayList<FileMetaData>();
		    current.getOverlappingInputs(level, allStart, allLimit, expanded0);
		    
		    final long inputs0Size = VersionSetGlobal.totalFileSize(c.input(0));
		    final long inputs1Size = VersionSetGlobal.totalFileSize(c.input(1));
		    final long expanded0Size = VersionSetGlobal.totalFileSize(expanded0);
		    if (expanded0.size() > c.input(0).size() &&
		        inputs1Size + expanded0Size < VersionSetGlobal.expandedCompactionByteSizeLimit(options)) {
		    	InternalKey newStart = new InternalKey();
		    	InternalKey newLimit = new InternalKey();
		    	getRange(expanded0, newStart, newLimit);
		    	ArrayList<FileMetaData> expanded1 = new ArrayList<FileMetaData>();
		    	current.getOverlappingInputs(level+1, newStart, newLimit, expanded1);
		    	
		    	if (expanded1.size() == c.input(1).size()) {
		    		Logger0.log0(options.infoLog,
		    				"Expanding@%d %d+%d (%d+%d bytes) to %d+%d (%d+%d bytes)\n",
		    				level,
		    				c.input(0).size(),
		    				c.input(1).size(),
		            		inputs0Size, inputs1Size,
		            		expanded0.size(),
		            		expanded1.size(),
		            		expanded0Size,	inputs1Size);
		    		smallest = newStart;
		    		largest = newLimit;
		        	c.inputs[0] = expanded0;
		        	c.inputs[1] = expanded1;
		        	getRange2(c.input(0), c.input(1), allStart, allLimit);
		    	}
		    }
		}

		// Compute the set of grandparent files that overlap this compaction
		// (parent == level+1; grandparent == level+2)
		if (level + 2 < DBFormat.kNumLevels) {
			current.getOverlappingInputs(level + 2, allStart, allLimit, c.grandparents);
		}

//		if (false) {
//			  options.infoLog.log("Compacting %d '%s' .. '%s'",
//		        level,
//		        smallest.debugString(),
//		        largest.debugString());
//		}

		// Update the place where we will do the next compaction for this level.
		// We update this immediately instead of waiting for the VersionEdit
		// to be applied so that if the compaction fails, we will try a different
		// key range next time.
		compactPointer[level] = ByteBufFactory.newUnpooled(largest.rep().data(), largest.rep().size());
		c.edit.setCompactPointer(level, largest);
	}
	
	Status writeSnapshot(LogWriter log) {
		  // TODO(optimize): Break up into multiple records to reduce memory usage on recovery?

		  // Save metadata
		  VersionEdit edit = new VersionEdit();
		  edit.setComparatorName(icmp.userComparator().name());

		  // Save compaction pointers
		  for (int level = 0; level < DBFormat.kNumLevels; level++) {
			  if (!compactPointer[level].empty()) {
				  InternalKey key = new InternalKey();
				  key.decodeFrom(compactPointer[level]);
				  edit.setCompactPointer(level, key);
			  }
		  }

		  // Save files
		  for (int level = 0; level < DBFormat.kNumLevels; level++) {
			  ArrayList<FileMetaData> files = current.levelFiles(level);
		   	  for (int i = 0; i < files.size(); i++) {
		   		  FileMetaData f = files.get(i);
		   		  edit.addFile(level, f.number, f.fileSize, f.smallest, f.largest, f.numEntries);
		   	  }
		  }

		  ByteBuf record = ByteBufFactory.newUnpooled();
		  record.require(1);
		  edit.encodeTo(record);
		  return log.addRecord(SliceFactory.newUnpooled(record));
	}

	public String debugDataRange() {
		String s = "";
		if (current == null)
			return s;
		
		return current.debugDataRange();
	}
}
