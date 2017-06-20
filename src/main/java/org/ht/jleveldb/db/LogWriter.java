package org.ht.jleveldb.db;

import org.ht.jleveldb.Status;
import org.ht.jleveldb.WritableFile;
import org.ht.jleveldb.db.LogFormat.RecordType;
import org.ht.jleveldb.util.Coding;
import org.ht.jleveldb.util.Crc32c;
import org.ht.jleveldb.util.Slice;

public class LogWriter {
	
	WritableFile dest;
	int blockOffset;       // Current offset in block
	
	  // crc32c values for all supported record types.  These are
	  // pre-computed to reduce the overhead of computing the crc of the
	  // record type stored in the header.
	long typeCrc[] = new long[LogFormat.kMaxRecordType + 1];
	  
	static void initTypeCrc(long[] typeCrc) {
		byte[] tmp = new byte[1];
		for (int i = 0; i <= LogFormat.kMaxRecordType; i++) {
			tmp[0] = (byte)i;
			typeCrc[i] = Crc32c.value(tmp, 0, 1);
		}
	}
	
	  // Create a writer that will append data to "*dest".
	  // "*dest" must be initially empty.
	  // "*dest" must remain live while this Writer is in use.
	public LogWriter(WritableFile dest) {
		this.dest = dest;
		blockOffset = 0;
		initTypeCrc(typeCrc);
	}
	
	  // Create a writer that will append data to "*dest".
	  // "*dest" must have initial length "dest_length".
	  // "*dest" must remain live while this Writer is in use.
	public LogWriter(WritableFile dest, long destLength) {
		this.dest = dest;
		blockOffset = (int)(destLength % LogFormat.kBlockSize);
		initTypeCrc(typeCrc);
	}
	
	public static final byte[] fillzero = new byte[]{0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0};
	
	public Status addRecord(Slice slice) {
		byte[] ptr = slice.data();
		int ptrOffset = slice.offset;
		int left = slice.size();

		  // Fragment the record if necessary and emit it.  Note that if slice
		  // is empty, we still want to iterate once to emit a single
		  // zero-length record
		Status s = Status.ok0();
		boolean begin = true;
		do {
			int leftover = LogFormat.kBlockSize - blockOffset;
		    assert(leftover >= 0);
		    if (leftover < LogFormat.kHeaderSize) {
		    	// Switch to a new block
		    	if (leftover > 0) {
		    		// Fill the trailer (literal below relies on kHeaderSize being 7)
		    		dest.append(new Slice(fillzero, 0, leftover));
		    	}
		    	blockOffset = 0;
		    }

		    // Invariant: we never leave < kHeaderSize bytes in a block.
		    assert(LogFormat.kBlockSize - blockOffset - LogFormat.kHeaderSize >= 0);

		    int avail = LogFormat.kBlockSize - blockOffset - LogFormat.kHeaderSize;
		    int fragmentLength = (left < avail) ? left : avail;

		    RecordType type;
		    boolean end = (left == fragmentLength);
		    if (begin && end) {
		    	type = LogFormat.RecordType.FullType;
		    } else if (begin) {
		    	type = LogFormat.RecordType.FirstType;
		    } else if (end) {
		    	type = LogFormat.RecordType.LastType;
		    } else {
		    	type = LogFormat.RecordType.MiddleType;
		    }

		    s = emitPhysicalRecord(type, ptr, ptrOffset, fragmentLength);
		    ptrOffset += fragmentLength;
		    left -= fragmentLength;
		    begin = false;
		  } while (s.ok() && left > 0);
		  return s;
	}
	
	//Status emitPhysicalRecord(RecordType type, const char* ptr, size_t length);
	Status emitPhysicalRecord(RecordType t, byte[] ptr, int offset, int n) {
		assert(n <= 0xffff);  // Must fit in two bytes
		assert(blockOffset + LogFormat.kHeaderSize + n <= LogFormat.kBlockSize);

		// Format the header
		byte buf[] = new byte[LogFormat.kHeaderSize];
		buf[4] = (byte)(n & 0xff);
		buf[5] = (byte)((n >> 8) & 0xff);
		buf[6] = (byte)(t.getType() & 0xff);

		// Compute the crc of the record type and the payload.
		long crc = Crc32c.extend(typeCrc[t.getType()], ptr, 0, (int)n);
		crc = Crc32c.mask(crc);                 // Adjust for storage
		Coding.encodeFixedNat32Long(buf, 0, 4, crc);

		// Write the header and the payload
		Status s = dest.append(new Slice(buf, 0, LogFormat.kHeaderSize));
		if (s.ok()) {
			s = dest.append(new Slice(ptr, 0, n));
			if (s.ok()) {
				s = dest.flush();
			}
		}
		blockOffset += LogFormat.kHeaderSize + n;
		return s;
	}
	
	public void delete() {

	}
}
