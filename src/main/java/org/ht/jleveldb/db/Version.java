package org.ht.jleveldb.db;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.ht.jleveldb.Iterator0;
import org.ht.jleveldb.ReadOptions;
import org.ht.jleveldb.Status;
import org.ht.jleveldb.db.format.DBFormat;
import org.ht.jleveldb.db.format.InternalKey;
import org.ht.jleveldb.db.format.InternalKeyComparator;
import org.ht.jleveldb.db.format.LookupKey;
import org.ht.jleveldb.db.format.ParsedInternalKey;
import org.ht.jleveldb.db.format.ValueType;
import org.ht.jleveldb.table.Table;
import org.ht.jleveldb.table.TwoLevelIterator;
import org.ht.jleveldb.util.ByteBuf;
import org.ht.jleveldb.util.Coding;
import org.ht.jleveldb.util.Comparator0;
import org.ht.jleveldb.util.Slice;

/**
 * Each Version keeps track of a set of Table files per level.  The
 * entire set of versions is maintained in a VersionSet.
 * 
 * @author Teng Huang ht201509@163.com
 */
public class Version {
	
	public interface CallBack {
		public boolean run(Object arg, int level, FileMetaData meta);
	}
	
	final VersionSet vset;
	Version next;
	Version prev;
	int refs;
	
	/**
	 * List of files per level
	 */
	//ArrayList<FileMetaData>[] files;
	Object[] files;
	
	/**
	 * Next file to compact based on seek stats.
	 */
	FileMetaData fileToCompact;
	int fileToCompactLevel;
	
	/**
	 * Level that should be compacted next and its compaction score.
	 * Score < 1 means compaction is not strictly needed.  These fields
	 * are initialized by Finalize().
	 */
	double compactionScore;
	int compactionLevel;
	
	public Version(VersionSet vset) {
		this.vset = vset;
		next = this;
		prev = this;
		refs = 0;
		fileToCompact = null;
		fileToCompactLevel = -1;
		compactionScore = -1.0;
		compactionLevel = -1;
		files = new Object[DBFormat.kNumLevels];
		for (int i = 0; i < DBFormat.kNumLevels; i++)
			files[i] = new ArrayList<FileMetaData>();
	}
	
	public void delete() {
		assert(refs == 0);

		// Remove from linked list
		prev.next = next;
		next.prev = prev;
		

		// Drop references to files
		for (int level = 0; level < DBFormat.kNumLevels; level++) {
		    for (int i = 0; i < levelFiles(level).size(); i++) {
		    	FileMetaData f = levelFiles(level).get(i);
		    	assert(f.refs > 0);
		    	f.refs--;
		    	if (f.refs <= 0) {
		    		//delete f;
		    		f.delete();
		    	}
		    }
		}
	}
	
    /**
     *  Reference count management (so Versions do not disappear out from
     *  under live iterators)
     */
	public void ref() {
		//Thread.dumpStack();
		vset.incrVersionRef();
		++refs;
	}
	
	public void unref() {
		//Thread.dumpStack();
		assert(this != vset.dummyVersions);
		assert(refs >= 1);
		vset.decrVersionRef();
		--refs;
		if (refs == 0) {
		    delete();
		}
	}
	
	public void addIterators(ReadOptions options, List<Iterator0> iters) {
		// Merge all level zero files together since they may overlap
		for (int i = 0; i < levelFiles(0).size(); i++)
		    iters.add(vset.tcache.newIterator(options, levelFiles(0).get(i).number, levelFiles(0).get(i).fileSize));

		// For levels > 0, we can use a concatenating iterator that sequentially
		// walks through the non-overlapping files in the level, opening them
		// lazily.
		for (int level = 1; level < DBFormat.kNumLevels; level++) {
		    if (!levelFiles(level).isEmpty())
		    	iters.add(newConcatenatingIterator(options, level));
		}
	}
	
	public Iterator0 newConcatenatingIterator(ReadOptions options, int level) {
		return TwoLevelIterator.newTwoLevelIterator(
			      new LevelFileNumIterator(vset.icmp, levelFiles(level)), 
			      VersionSetGlobal.getFileIterator, vset.tcache, options);
	}
	
	
	enum SaverState {
		kNotFound,
		kFound,
		kDeleted,
		kCorrupt
	};
	
	static class Saver {
		SaverState state;
		Comparator0 ucmp;
		Slice userKey;
		ByteBuf value;
		
		public Saver(SaverState state, Comparator0 ucmp, Slice userKey, ByteBuf value) {
			this.state = state;
			this.ucmp = ucmp;
			this.userKey = userKey;
			this.value = value;
		}
	}
	
	Table.HandleResult valueSaver = new Table.HandleResult() {
		public void run(Object arg, Slice ikey, Slice v) {
			Saver s = (Saver)arg;
			ParsedInternalKey parsedKey = new ParsedInternalKey();
			if (!parsedKey.parse(ikey)) {
				s.state = SaverState.kCorrupt;
			} else {
				if (s.ucmp.compare(parsedKey.userKey, s.userKey) == 0) {
					s.state = (parsedKey.type == ValueType.Value) ? SaverState.kFound : SaverState.kDeleted;
					if (s.state == SaverState.kFound)
						s.value.assign(v.data(), v.offset, v.size());
			    }
			}
		}
	};

	
	static Comparator<FileMetaData> newestFirst = new Comparator<FileMetaData>() {
		public int compare(FileMetaData o1, FileMetaData o2) {
			return -1 * Long.compare(o1.number, o2.number);
		}
	};
	
	public static class GetStats {
		public FileMetaData seekFile;
		int seekFileLevel;
	}
	
	/**
	 * Lookup the value for key.  If found, store it in {@code value}. Fills {@code stats}.</br></br>
	 * 
	 * <b>REQUIRES: lock is not held</b>
	 * @param options
	 * @param k
	 * @param value [OUTPUT]
	 * @param stats [OUTPUT]
	 * @return OK if found, else non-OK.
	 */
	public Status get(ReadOptions options, LookupKey k, ByteBuf value, GetStats stats) {
		Slice ikey = k.internalKey();
		Slice userKey = k.userKey();
		Comparator0 ucmp = vset.icmp.userComparator();
		Status s = Status.ok0();

		// We can search level-by-level since entries never hop across
		// levels.  Therefore we are guaranteed that if we find data
		// in an smaller level, later levels are irrelevant.
		ArrayList<FileMetaData> tmp = new ArrayList<FileMetaData>();
		FileMetaData tmp2;
		for (int level = 0; level < DBFormat.kNumLevels; level++) {
			int numFiles = levelFiles(level).size();
		    if (numFiles == 0) 
		    	continue;

		    // Get the list of files to search in this level
		    ArrayList<FileMetaData> levelFiles = levelFiles(level);
		    if (level == 0) {
		    	// Level-0 files may overlap each other.  Find all files that
		    	// overlap user_key and process them in order from newest to oldest.
		    	//tmp.reserve(numFiles);
		    	tmp.clear();
		    	for (int i = 0; i < numFiles; i++) {
		    		FileMetaData f = levelFiles.get(i);
		    		if (ucmp.compare(userKey, f.smallest.userKey()) >= 0 &&
		    				ucmp.compare(userKey, f.largest.userKey()) <= 0) {
		    			tmp.add(f);
		    		}
		    	}
		    	
		    	if (tmp.isEmpty()) 
		    		//go to next level
		    		continue; 

		    	Collections.sort(tmp, newestFirst); //std::sort(tmp.begin(), tmp.end(), NewestFirst);
		    	levelFiles = tmp; //files = &tmp[0];
		    	numFiles = tmp.size();
		    } else {
		    	// Binary search to find earliest index whose largest key >= ikey.
		    	int index = VersionSetGlobal.findFile(vset.icmp, levelFiles(level), ikey);
		    	if (index >= numFiles) {
		    		levelFiles = null;
		    		numFiles = 0;
		    	} else {
		    		tmp2 = levelFiles.get(index);
		    		if (ucmp.compare(userKey, tmp2.smallest.userKey()) < 0) {
		    			// All of "tmp2" is past any data for userKey
		    			levelFiles = null;
		    			numFiles = 0;
		    		} else {
		    			tmp.clear();
		    			tmp.add(tmp2);
		    			levelFiles = tmp;
		    			numFiles = 1;
		    		}
		    	}
		    }
		    

			stats.seekFile = null;
			stats.seekFileLevel = -1;
			FileMetaData lastFileRead = null;
			int lastFileReadLevel = -1;

		    for (int i = 0; i < numFiles; ++i) {
		    	if (lastFileRead != null && stats.seekFile == null) {
		    		// We have had more than one seek for this read.  Charge the 1st file.
		    		stats.seekFile = lastFileRead;
		    		stats.seekFileLevel = lastFileReadLevel;
		    	}

		    	FileMetaData f = levelFiles.get(i);
		    	lastFileRead = f;
		    	lastFileReadLevel = level;

		    	Saver saver = new Saver(SaverState.kNotFound, ucmp, userKey, value);
		    	s = vset.tcache.get(options, f.number, f.fileSize, ikey, saver, valueSaver);
		    	if (!s.ok()) {
		    		return s;
		    	}
		    	
		    	switch (saver.state) {
		        case kNotFound:
		        	break;      // Keep searching in other files
		        case kFound:
		        	return s;
		        case kDeleted:
		        	s = Status.notFound();  // May throws null pointer exception, Use empty error message for speed
		        	return s;
		        case kCorrupt:
		        	s = Status.corruption("corrupted key for "+userKey.encodeToString());
		        	return s;
		    	}
		    }
		}

		return Status.notFound();
	}
	
	/**
	 * Adds {@code stats} into the current state.</br></br>
	 * 
	 * <b>REQUIRES: lock is held</b>
	 * @param stats
	 * @return {@code true} if a new compaction may need to be triggered, {@code false} otherwise.
	 */
	public boolean updateStats(GetStats stats) {
		FileMetaData f = stats.seekFile;
		if (f != null) {
		    f.allowedSeeks--;
		    if (f.allowedSeeks <= 0 && fileToCompact == null) {
		    	fileToCompact = f;
		    	fileToCompactLevel = stats.seekFileLevel;
		    	return true;
		    }
		}
		return false;
	}
	
	/** 
	 * Record a sample of bytes read at the specified internal key.
	 * Samples are taken approximately once every config::kReadBytesPeriod
	 * bytes.  </br></br>
	 * 
	 * Returns true if a new compaction may need to be triggered.</br></br>
	 * 
	 * <b>REQUIRES: lock is held</b>
	 */
	static CallBack stateCallback = new CallBack() {
	    public boolean run(Object arg, int level, FileMetaData f) {
	    	State0 state = (State0)(arg);
	    	state.matches++;
	    	if (state.matches == 1) {
	    		// Remember first match.
	    		state.stats.seekFile = f;
	    		state.stats.seekFileLevel = level;
	    	}
	    	// We can stop iterating once we have a second match.
	    	return state.matches < 2;
	    }
	};
	
	static class State0 {
		 public GetStats stats;  // Holds first matching file
		 public int matches;
	}
	
	public boolean recordReadSample(Slice internalKey0) {
		ParsedInternalKey ikey = new ParsedInternalKey();
		if (!ikey.parse(internalKey0)) {
			return false;
		}
		
		State0 state = new State0();
		state.matches = 0;
		forEachOverlapping(ikey.userKey, internalKey0, state, stateCallback);

		// Must have at least two matches since we want to merge across
		// files. But what if we have a single file that contains many
		// overwrites and deletions?  Should we have another mechanism for
		// finding such files?
		if (state.matches >= 2) {
			// 1MB cost is about 1 seek (see comment in Builder::Apply).
		    return updateStats(state.stats);
		}
		return false;
	}

	
	/**
	 * 
	 * 
	 * @param level
	 * @param begin null means before all keys
	 * @param end null means after all keys
	 * @param inputs
	 */
	public void getOverlappingInputs(int level, InternalKey begin, InternalKey end, List<FileMetaData> inputs) {
		  assert(level >= 0);
		  assert(level < DBFormat.kNumLevels);
		  inputs.clear();
		  Slice userBegin = new Slice();
		  Slice userEnd = new Slice();
		  if (begin != null) {
			  userBegin = begin.userKey();
		  }
		  if (end != null) {
			  userEnd = end.userKey();
		  }
		  Comparator0 userCmp = vset.icmp.userComparator();
		  for (int i = 0; i < levelFiles(level).size(); ) {
			  FileMetaData f = levelFiles(level).get(i++);
			  Slice fileStart = f.smallest.userKey();
			  Slice fileLimit = f.largest.userKey();
			  if (begin != null && userCmp.compare(fileLimit, userBegin) < 0) {
				  // "f" is completely before specified range; skip it
			  } else if (end != null && userCmp.compare(fileStart, userEnd) > 0) {
				  // "f" is completely after specified range; skip it
			  } else {
				  inputs.add(f);
				  if (level == 0) {
					  // Level-0 files may overlap each other.  So check if the newly
					  // added file has expanded the range.  If so, restart search.
					  if (begin != null && userCmp.compare(fileStart, userBegin) < 0) {
						  userBegin = fileStart;
						  inputs.clear();
						  i = 0;
					  } else if (end != null && userCmp.compare(fileLimit, userEnd) > 0) {
						  userEnd = fileLimit;
						  inputs.clear();
						  i = 0;
					  }
				  }
			  }
		  }
	}
	
	/**
	 * some part of [smallestUserKey,largestUserKey].
	 * 
	 * @param level
	 * @param smallestUserKey null represents a key smaller than all keys in the DB.
	 * @param largestUserKey null represents a key largest than all keys in the DB.
	 * @return Returns true iff some file in the specified level overlaps
	 */
	public boolean overlapInLevel(int level, Slice smallestUserKey, Slice largestUserKey) {
		return VersionSetGlobal.someFileOverlapsRange(vset.icmp, (level > 0), levelFiles(level),
				smallestUserKey, largestUserKey);
	}
	
	/**
	 *  Return the level at which we should place a new memtable compaction
	 *  result that covers the range [smallest_user_key,largest_user_key].
	 *  
	 * @param smallestUserKey
	 * @param largestUserKey
	 * @return
	 */
	public int pickLevelForMemTableOutput(Slice smallestUserKey, Slice largestUserKey) {
		int level = 0;
		if (!overlapInLevel(0, smallestUserKey, largestUserKey)) {
		    // Push to next level if there is no overlap in next level,
		    // and the #bytes overlapping in the level after that are limited.
		    InternalKey start = new InternalKey(smallestUserKey, DBFormat.kMaxSequenceNumber, DBFormat.kValueTypeForSeek);
		    InternalKey limit = new InternalKey(largestUserKey, 0, ValueType.Deletion); //(ValueType)(0)
		    ArrayList<FileMetaData> overlaps = new ArrayList<FileMetaData>();
		    while (level < DBFormat.kMaxMemCompactLevel) {
		    	if (overlapInLevel(level + 1, smallestUserKey, largestUserKey)) {
		    		break;
		    	}
		    	if (level + 2 < DBFormat.kNumLevels) {
		    		// Check that file does not overlap too many grandparent bytes.
		    		getOverlappingInputs(level + 2, start, limit, overlaps);
		    		long sum = VersionSetGlobal.totalFileSize(overlaps);
		    		if (sum > VersionSetGlobal.maxGrandParentOverlapBytes(vset.options)) {
		    			break;
		    		}
		    	}
		    	level++;
		    }
		}
		return level;
	}
	
	public int numFiles(int level) { 
		return levelFiles(level).size(); 
	}

	
	void forEachOverlapping(Slice userKey, Slice internalKey, Object arg, CallBack func) {
		// TODO(sanjay): Change Version::get() to use this function.
		Comparator0 ucmp = vset.icmp.userComparator();

		  // Search level-0 in order from newest to oldest.
		ArrayList<FileMetaData> tmp = new ArrayList<FileMetaData>();
		//tmp.reserve(files[0].size());
		for (int i = 0; i < levelFiles(0).size(); i++) {
		    FileMetaData f = levelFiles(0).get(i);
		    if (ucmp.compare(userKey, f.smallest.userKey()) >= 0 &&
		        ucmp.compare(userKey, f.largest.userKey()) <= 0) {
		      tmp.add(f);
		    }
		}
		if (!tmp.isEmpty()) {
		    Collections.sort(tmp, newestFirst); //std::sort(tmp.begin(), tmp.end(), NewestFirst);
		    for (int i = 0; i < tmp.size(); i++) {
		    	if (!func.run(arg, 0, tmp.get(i))) {
		    		return;
		    	}
		    }
		}

		// Search other levels.
		for (int level = 1; level < DBFormat.kNumLevels; level++) {
		    int numFiles = levelFiles(level).size();
		    if (numFiles == 0) 
		    	continue;

		    // Binary search to find earliest index whose largest key >= internal_key.
		    int index = VersionSetGlobal.findFile(vset.icmp, levelFiles(level), internalKey);
		    if (index < numFiles) {
		    	FileMetaData f = levelFiles(level).get(index);
		    	if (ucmp.compare(userKey, f.smallest.userKey()) < 0) {
		    		// All of "f" is past any data for user_key
		    	} else {
		    		if (!func.run(arg, level, f)) {
		    			return;
		    		}
		    	}
		    }
		}
	}
	

	

	
	@SuppressWarnings("unchecked")
	final public ArrayList<FileMetaData> levelFiles(int level) {
		return (ArrayList<FileMetaData>)files[level];
	}
	
	
	public static class LevelFileNumIterator extends Iterator0 {
		InternalKeyComparator icmp;
		ArrayList<FileMetaData> flist;
		int index;

		  // Backing store for value().  Holds the file number and size.
		byte[] valueBuf = new byte[16];
		Slice value0 = new Slice(valueBuf, 0, valueBuf.length);
		
		public LevelFileNumIterator(InternalKeyComparator icmp, ArrayList<FileMetaData> flist) {
			this.icmp = icmp;
			this.flist = flist;
			index = flist.size();
		}
		
		@Override
		public void delete() {
			icmp = null;
			flist.clear();
		}
		
		@Override
		public Status status() { 
			return Status.ok0(); 
		}
		
		@Override
		public Slice key() {
		    assert(valid());
		    return flist.get(index).largest.encode();
		}
		
		@Override
		public Slice value() {
		    assert(valid());
		    Coding.encodeFixedNat64(valueBuf, 0, flist.get(index).number);
		    Coding.encodeFixedNat64(valueBuf, 8, flist.get(index).fileSize);
		    return value0;
		}
		
		@Override
		public void next() {
		    assert(valid());
		    index++;
		}
		
		@Override
		public void prev() {
		    assert(valid());
		    if (index == 0) {
		    	index = flist.size();  // Marks as invalid
		    } else {
		    	index--;
		    }
		}
		
		@Override
		public boolean valid() {
		    return index < flist.size();
		}
		
		@Override
		public void seek(Slice target) {
		    index = VersionSetGlobal.findFile(icmp, flist, target);
		}
		
		@Override
		public void seekToFirst() { 
			index = 0; 
		}
		
		@Override
		public void seekToLast() {
		    index = flist.isEmpty() ? 0 : flist.size() - 1;
		}
	}
}
