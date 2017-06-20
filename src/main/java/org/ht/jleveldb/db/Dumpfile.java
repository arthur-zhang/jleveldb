package org.ht.jleveldb.db;

import org.ht.jleveldb.Env;
import org.ht.jleveldb.FileName;
import org.ht.jleveldb.FileType;
import org.ht.jleveldb.Iterator0;
import org.ht.jleveldb.Options;
import org.ht.jleveldb.RandomAccessFile0;
import org.ht.jleveldb.ReadOptions;
import org.ht.jleveldb.SequentialFile;
import org.ht.jleveldb.Status;
import org.ht.jleveldb.WritableFile;
import org.ht.jleveldb.WriteBatch;
import org.ht.jleveldb.db.format.ParsedInternalKey;
import org.ht.jleveldb.db.format.ValueType;
import org.ht.jleveldb.table.Table;
import org.ht.jleveldb.util.ByteBuf;
import org.ht.jleveldb.util.ByteBufFactory;
import org.ht.jleveldb.util.FuncOutput;
import org.ht.jleveldb.util.FuncOutputLong;
import org.ht.jleveldb.util.Slice;

public class Dumpfile {
	// Dump the contents of the file named by fname in text format to
	// *dst.  Makes a sequence of dst->Append() calls; each call is passed
	// the newline-terminated text corresponding to a single item found
	// in the file.
	//
	// Returns a non-OK result if fname does not name a leveldb storage
	// file, or if the file cannot be read.
	public static Status dumpFile(Env env, String fname, WritableFile dst) {
		FuncOutput<FileType> ftype0 = new FuncOutput<FileType>();
		if (!guessType(fname, ftype0)) {
		    return Status.invalidArgument(fname + ": unknown file type");
		}
		switch (ftype0.getValue()) {
		    case LogFile:         return dumpLog(env, fname, dst);
		    case DescriptorFile:  return dumpDescriptor(env, fname, dst);
		    case TableFile:       return dumpTable(env, fname, dst);
		    default:
		      break;
		}
		return Status.invalidArgument(fname + ": not a dump-able file type");
	}
	
	// Notified when log reader encounters corruption.
	static class CorruptionReporter implements LogReader.Reporter {
		public WritableFile dst;
		public void corruption(int bytes, Status status) {
			String r = "corruption: ";
			r += bytes;//AppendNumberTo(&r, bytes);
			r += " bytes; ";
			r += status.toString();
			r += '\n';
			byte[] b = r.getBytes();
			dst.append(new Slice(b, 0, b.length));
		}
	};
	
	public interface LogReadCallback {
		public void run(long offset, Slice slice, WritableFile f);
	}
	
	// Print contents of a log file. (*func)() is called on every record.
	static Status printLogContents(Env env, String fname,
			LogReadCallback callback,  WritableFile dst) {
		FuncOutput<SequentialFile> file0 = new FuncOutput<SequentialFile>();
		Status s = env.newSequentialFile(fname, file0);
		if (!s.ok()) {
			return s;
		}
		SequentialFile file = file0.getValue();
		CorruptionReporter reporter = new CorruptionReporter();
		reporter.dst = dst;
		LogReader reader = new LogReader(file, reporter, true, 0);
		Slice record = new Slice();
		ByteBuf scratch = ByteBufFactory.defaultByteBuf();
		while (reader.readRecord(record, scratch)) {
			//(*func)(reader.LastRecordOffset(), record, dst);
			callback.run(reader.lastRecordOffset(), record, dst);
			scratch.clear();
		}
		file.delete();
		file = null;
		return Status.ok0();
	}
	
	static void appendEscapedStringTo(String s, Slice value) {
		
	}
	
	// Called on every item found in a WriteBatch.
	static class WriteBatchItemPrinter implements WriteBatch.Handler {
		public WritableFile dst;
		public void put(Slice key, Slice value) {
			String r = "  put '";
			appendEscapedStringTo(r, key);
			r += "' '";
			appendEscapedStringTo(r, value);
			r += "'\n";
			byte[] b = r.getBytes();
			dst.append(new Slice(b, 0, b.length));
		}
		public void delete(Slice key) {
			String r = "  del '";
			appendEscapedStringTo(r, key);
			r += "'\n";
			byte[] b = r.getBytes();
			dst.append(new Slice(b, 0, b.length));
		}
	}
	
	// Called on every log record (each one of which is a WriteBatch)
	// found in a kLogFile.
	static class WriteBatchPrinter implements LogReadCallback {
		public void run(long pos, Slice record, WritableFile dst) {
			String r = "--- offset ";
			r += pos;
			r += "; ";
			if (record.size() < 12) {
				r += "log record length ";
				r += record.size();
				r += " is too small\n";
				byte[] b = r.getBytes();
				dst.append(new Slice(b, 0, b.length));
				return;
			}
			WriteBatch batch = new WriteBatch();
			WriteBatchInternal.setContents(batch, record);
			r += "sequence ";
		  	r += WriteBatchInternal.sequence(batch);
		  	r += '\n';
		  	byte[] b = r.getBytes();
		  	dst.append(new Slice(b, 0, b.length));
		  	WriteBatchItemPrinter batchItemPrinter = new WriteBatchItemPrinter();
		  	batchItemPrinter.dst = dst;
		  	Status s = batch.iterate(batchItemPrinter);
		  	if (!s.ok()) {
		  		byte[] b2 = ("  error: " + s.toString() + "\n").getBytes();
		  		dst.append(new Slice(b2, 0, b2.length));
		  	}
		}
	}
	
	static Status dumpLog(Env env, String fname, WritableFile dst) {
		return printLogContents(env, fname, new WriteBatchPrinter(), dst);
	}
	
	// Called on every log record (each one of which is a WriteBatch)
	// found in a kDescriptorFile.
	static class VersionEditPrinter implements LogReadCallback {
		public void run(long pos, Slice record, WritableFile dst) {
			String r = "--- offset ";
			r += pos;
			r += "; ";
			VersionEdit edit = new VersionEdit();
			Status s = edit.decodeFrom(record);
			if (!s.ok()) {
				r += s.toString();
				r += '\n';
			} else {
				r += edit.debugString();
			}
			byte[] b = r.getBytes();
			dst.append(new Slice(b, 0, b.length));
		}
	}
	
	static Status dumpDescriptor(Env env, String fname, WritableFile dst) {
		return printLogContents(env, fname, new VersionEditPrinter(), dst);
	}
		
	static Status dumpTable(Env env, String fname, WritableFile dst) {
		  FuncOutputLong fileSize = new FuncOutputLong();
		  FuncOutput<RandomAccessFile0> file0 = new FuncOutput<RandomAccessFile0>();
		  FuncOutput<Table> table0 = new FuncOutput<Table>();
		  Status s = env.getFileSize(fname, fileSize);
		  if (s.ok()) {
			  s = env.newRandomAccessFile(fname, file0);
		  }
		  if (s.ok()) {
		    // We use the default comparator, which may or may not match the
		    // comparator used in this database. However this should not cause
		    // problems since we only use Table operations that do not require
		    // any comparisons.  In particular, we do not call Seek or Prev.
		    s = Table.open(new Options(null), file0.getValue(), fileSize.getValue(), table0);
		  }
		  if (!s.ok()) {
			  if (table0.getValue() != null)
				  table0.getValue().delete();
			  if (file0.getValue() != null)
				  file0.getValue().delete();
			  return s;
		  }

		  Table table = table0.getValue();
		  RandomAccessFile0 file = file0.getValue();
		  ReadOptions ro = new ReadOptions();
		  ro.fillCache = false;
		  Iterator0 iter = table.newIterator(ro);
		  String r = new String();
		  for (iter.seekToFirst(); iter.valid(); iter.next()) {
			  r = "";
			  ParsedInternalKey key = new ParsedInternalKey();
			  if (!key.parse(iter.key())) {
				  r = "badkey '";
				  appendEscapedStringTo(r, iter.key());
				  r += "' => '";
				  appendEscapedStringTo(r, iter.value());
				  r += "'\n";
				  byte[] b = r.getBytes();
				  dst.append(new Slice(b, 0, b.length));
			  } else {
				  r = "'";
				  appendEscapedStringTo(r, key.userKey);
				  r += "' @ ";
				  r += key.sequence;
				  r += " : ";
				  if (key.type == ValueType.Deletion) {
					  r += "del";
				  } else if (key.type == ValueType.Value) {
					  r += "val";
				  } else {
					  r += key.type;
				  }
				  r += " => '";
				  appendEscapedStringTo(r, iter.value());
				  r += "'\n";
				  byte[] b = r.getBytes();
				  dst.append(new Slice(b, 0, b.length));
			  }
		  }
		  s = iter.status();
		  if (!s.ok()) {
			  byte[] b = ("iterator error: " + s.toString() + "\n").getBytes();
			  dst.append(new Slice(b, 0, b.length));
		  }

		  iter.delete();
		  table.delete();
		  file.delete();
		  return Status.ok0();
	}
	
	
	
	static boolean guessType(String fname, FuncOutput<FileType> type) {
		  int pos = fname.lastIndexOf('/'); //size_t pos = fname.rfind('/');
		  String basename;
		  if (pos < 0) {
			  basename = fname;
		  } else {
			  basename = fname.substring(pos+1); //basename = std::string(fname.data() + pos + 1, fname.size() - pos - 1);
		  }
		  FuncOutputLong ignored = new FuncOutputLong();
		  return FileName.parseFileName(basename, ignored, type);
	}
}
