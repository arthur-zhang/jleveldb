package org.ht.jleveldb.db;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

import org.ht.jleveldb.Iterator0;
import org.ht.jleveldb.Status;
import org.ht.jleveldb.db.format.DBFormat;
import org.ht.jleveldb.db.format.ParsedInternalKey;
import org.ht.jleveldb.db.format.ValueType;
import org.ht.jleveldb.util.ByteBuf;
import org.ht.jleveldb.util.ByteBufFactory;
import org.ht.jleveldb.util.Comparator0;
import org.ht.jleveldb.util.Slice;

public class DBIter extends Iterator0 {

	// Which direction is the iterator currently moving?
	  // (1) When moving forward, the internal iterator is positioned at
	  //     the exact entry that yields this->key(), this->value()
	  // (2) When moving backwards, the internal iterator is positioned
	  //     just before all entries whose user key == this->key().
	enum Direction {
		kForward,
	    kReverse
	};
	
	DBImpl db;
	Comparator0 userComparator;
	Iterator0 iter;
	long sequence;

	Status status = Status.ok0();
	ByteBuf savedKey = ByteBufFactory.defaultByteBuf();      // == current key when direction_==kReverse
	ByteBuf savedValue = ByteBufFactory.defaultByteBuf();    // == current raw value when direction_==kReverse
	Direction direction;
	boolean valid;

	Random rnd;
	long bytesCounter;
	
	public DBIter(DBImpl db, Comparator0 cmp, Iterator0 iter, long s,  int seed) {
		this.db = db;
		this.userComparator = cmp;
		this.iter = iter;
		this.sequence = s;
		
		direction = Direction.kForward;
		valid = false;
		rnd = ThreadLocalRandom.current();
		rnd.setSeed(seed);
		
		bytesCounter = randomPeriod();
	}
	
	// Pick next gap with average value of config::kReadBytesPeriod.
	long randomPeriod() {
	    //return rnd_.Uniform(2*config::kReadBytesPeriod);
		return 0; //TODO
	}
	
	void findNextUserEntry(boolean skipping, ByteBuf skip) {
		// Loop until we hit an acceptable entry to yield
		assert(iter.valid());
		assert(direction == Direction.kForward);
		do {
			ParsedInternalKey ikey = new ParsedInternalKey(); //TODO: optimized: reduce the new frequency.
		    if (parseKey(ikey) && ikey.sequence <= sequence) {
		    	switch (ikey.type) {
		        case Deletion:
		        	// Arrange to skip all upcoming entries for this key since
		        	// they are hidden by this deletion.
		        	saveKey(ikey.userKey, skip);
		        	skipping = true;
		        	break;
		        case Value:
		          if (skipping &&
		              userComparator.compare(ikey.userKey, skip) <= 0) {
		            // Entry hidden
		          } else {
		        	  valid = true;
		        	  savedKey.clear();
		        	  return;
		          }
		          break;
		      }
		    }
		    iter.next();
		} while (iter.valid());
		savedKey.clear();
		valid = false;
	}
	
	void findPrevUserEntry() {
		assert(direction == Direction.kReverse);

		ValueType valueType = ValueType.Deletion;
		if (iter.valid()) {
			do {
				ParsedInternalKey ikey = new ParsedInternalKey();
				if (parseKey(ikey) && ikey.sequence <= sequence) {
					if ((valueType != ValueType.Deletion) &&
							userComparator.compare(ikey.userKey, savedKey) < 0) {
						// We enscountered a non-deleted value in entries for previous keys,
						break;
					}
					valueType = ikey.type;
					if (valueType == ValueType.Deletion) {
						savedKey.clear();
						clearSavedValue();
					} else {
						Slice rawValue = iter.value();
						if (savedValue.capacity() > rawValue.size() + 1048576) {
							ByteBuf empty = ByteBufFactory.defaultByteBuf();
							swap(empty, savedValue);
						}
						saveKey(DBFormat.extractUserKey(iter.key()), savedKey);
						savedValue.assign(rawValue.data(), rawValue.offset, rawValue.size());
					}
				}
				iter.prev();
		    } while (iter.valid());
		}

		if (valueType == ValueType.Deletion) {
			// End
		    valid = false;
		    savedKey.clear();
		    clearSavedValue();
		    direction = Direction.kForward;
		} else {
		    valid = true;
		}
	}
	
	boolean parseKey(ParsedInternalKey ikey) {
		Slice k = iter.key();
		long n = k.size() + iter.value().size();
		bytesCounter -= n;
		while (bytesCounter < 0) {
			bytesCounter += randomPeriod();
		    db.recordReadSample(k);
		}
		if (!ikey.parse(k)) {
		    status = Status.corruption("corrupted internal key in DBIter");
		    return false;
		} else {
		    return true;
		}
	}

	final void saveKey(Slice k, ByteBuf dst) {
	    dst.assign(k.data(), k.offset, k.size());
	}

	final void clearSavedValue() {
	    if (savedValue.capacity() > 1048576) {
	    	ByteBuf empty = ByteBufFactory.defaultByteBuf();
	    	swap(empty, savedValue); //TODO
	    } else {
	    	savedValue.clear();
	    }
	}
	
	@Override
	public void delete() {
		db = null;
		savedKey = null;     // == current key when direction_==kReverse
		savedValue = null;   // == current raw value when direction_==kReverse
		if (iter != null) {
			iter.delete();
			iter = null;
		}
	}
	  
	@Override
	final public boolean valid() {
		return valid;
	}

	@Override
	public void seekToFirst() {
		// TODO Auto-generated method stub

	}

	@Override
	public void seekToLast() {
		// TODO Auto-generated method stub

	}

	@Override
	public void seek(Slice target) {
		// TODO Auto-generated method stub

	}

	@Override
	public void next() {
		// TODO Auto-generated method stub

	}

	@Override
	public void prev() {
		// TODO Auto-generated method stub

	}

	@Override
	public Slice key() {
		assert(valid);
		return (direction == Direction.kForward) ? DBFormat.extractUserKey(iter.key()) : new Slice(savedKey);
	}

	@Override
	public Slice value() {
		assert(valid);
		return (direction == Direction.kForward) ? iter.value() : new Slice(savedValue);
	}

	@Override
	public Status status() {
		if (status.ok()) {
			return iter.status();
		} else {
			return status;
		}
	}

	// Return a new iterator that converts internal keys (yielded by
	// "*internal_iter") that were live at the specified "sequence" number
	// into appropriate user keys.
	public static Iterator0 newDBIterator(
	    DBImpl db,
	    Comparator0 userKeyComparator,
	    Iterator0 internalIter,
	    long sequence,
	    int seed) {
		//TODO
		return null;
	} 
}
