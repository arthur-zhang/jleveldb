package com.tchaicatkovsky.jleveldb.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;

import org.junit.Test;

import com.tchaicatkovsky.jleveldb.util.ByteBuf;
import com.tchaicatkovsky.jleveldb.util.ByteBufFactory;
import com.tchaicatkovsky.jleveldb.util.Coding;
import com.tchaicatkovsky.jleveldb.util.DefaultSlice;
import com.tchaicatkovsky.jleveldb.util.Slice;

public class TestCoding {

	@Test
	public void testFixed32() {
		ByteBuf s = ByteBufFactory.defaultByteBuf();

		for (int v = 0; v < 100000; v++) {
			s.writeFixedNat32(v);
		}

		byte[] p = s.data();
		int offset = 0;
		for (int v = 0; v < 100000; v++) {
			int actual = Coding.decodeFixedNat32(p, offset);
			assertEquals(v, actual);
			offset += 4;
		}
	}

	@Test
	public void testFixed64() {
		ByteBuf s = ByteBufFactory.defaultByteBuf();

		for (int power = 0; power <= 62; power++) {
			long v = 1L << power;
			s.writeFixedNat64(v + 0);
			s.writeFixedNat64(v + 1);
		}

		byte[] p = s.data();
		int offset = 0;
		for (int power = 0; power <= 62; power++) {
			long v = 1L << power;
			long actual;

			actual = Coding.decodeFixedNat64(p, offset);
			assertEquals(v + 0, actual);
			offset += 8;

			actual = Coding.decodeFixedNat64(p, offset);
			assertEquals(v + 1, actual);
			offset += 8;
		}
	}

	@Test
	public void testEncodingOutput() {
		ByteBuf dst = ByteBufFactory.defaultByteBuf();

		dst.writeFixedNat32(0x04030201);
		assertEquals(4, dst.size());
		assertEquals(0x01, (int) (dst.getByte(0) & 0xff));
		assertEquals(0x02, (int) (dst.getByte(1) & 0xff));
		assertEquals(0x03, (int) (dst.getByte(2) & 0xff));
		assertEquals(0x04, (int) (dst.getByte(3) & 0xff));

		dst.clear();
		dst.writeFixedNat64(0x0807060504030201L);
		assertEquals(8, dst.size());
		assertEquals(0x01, (int) (dst.getByte(0) & 0xff));
		assertEquals(0x02, (int) (dst.getByte(1) & 0xff));
		assertEquals(0x03, (int) (dst.getByte(2) & 0xff));
		assertEquals(0x04, (int) (dst.getByte(3) & 0xff));
		assertEquals(0x05, (int) (dst.getByte(4) & 0xff));
		assertEquals(0x06, (int) (dst.getByte(5) & 0xff));
		assertEquals(0x07, (int) (dst.getByte(6) & 0xff));
		assertEquals(0x08, (int) (dst.getByte(7) & 0xff));
	}

	@Test
	public void testVarint32() {
		ByteBuf s = ByteBufFactory.defaultByteBuf();
		for (int i = 0; i < (32 * 32); i++) {
			int v = (i / 32) << (i % 32);
			while (v < 0)
				v = v + Integer.MAX_VALUE;
			s.writeVarNat32(v);
		}

		Slice p = new DefaultSlice(s);
		for (int i = 0; i < (32 * 32); i++) {
			int v = (i / 32) << (i % 32);
			while (v < 0)
				v = v + Integer.MAX_VALUE;
			int expected = v;

			int oldOffset = p.offset();
			int actual = Coding.popVarNat32(p);

			assertEquals(expected, actual);
			assertEquals(Coding.varNatLength(actual), p.offset() - oldOffset);
		}
		assertEquals(p.offset(), s.size());
	}

	@Test
	public void testVarint64() {
		// Construct the list of values to check
		ArrayList<Long> values = new ArrayList<>();
		// Some special values
		values.add(0L);
		values.add(100L);
		for (int k = 0; k < 62; k++) {
			// Test values near powers of two
			final long power = 1L << k;
			values.add(power);
			values.add(power + 1);
		}

		ByteBuf s = ByteBufFactory.defaultByteBuf();
		for (int i = 0; i < values.size(); i++) {
			s.writeVarNat64(values.get(i));
		}

		Slice p = new DefaultSlice(s);
		for (int i = 0; i < values.size(); i++) {
			int oldOffset = p.offset();
			long actual = Coding.popVarNat64(p);

			assertTrue(values.get(i) == actual);
			assertEquals(Coding.varNatLength(actual), p.offset() - oldOffset);
		}
		assertEquals(p.offset(), s.size());
	}

	@Test
	public void testVarint32Overflow() {
		//TODO
	}
	
	@Test
	public void testVarint32Truncation() {
		//TODO
	}
	
	@Test
	public void testVarint64Overflow() {
		//TODO
	}
	
	@Test
	public void testVarint64Truncation() {
		//TODO
	}
	
	
	@Test
	public void testStrings() {
		ByteBuf s = ByteBufFactory.defaultByteBuf();
		s.writeLengthPrefixedSlice(new DefaultSlice(""));
		s.writeLengthPrefixedSlice(new DefaultSlice("foo"));
		s.writeLengthPrefixedSlice(new DefaultSlice("bar"));
		s.writeLengthPrefixedSlice(new DefaultSlice(TestUtil.makeString(200, 'x')));

		Slice input = new DefaultSlice(s);
		Slice v = new DefaultSlice();
		assertTrue(Coding.popLengthPrefixedSlice(input, v));
		assertEquals("", v.encodeToString());
		assertTrue(Coding.popLengthPrefixedSlice(input, v));
		assertEquals("foo", v.encodeToString());
		assertTrue(Coding.popLengthPrefixedSlice(input, v));
		assertEquals("bar", v.encodeToString());
		assertTrue(Coding.popLengthPrefixedSlice(input, v));
		assertEquals(TestUtil.makeString(200, 'x'), v.encodeToString());
		assertEquals("", input.encodeToString());
	}
}
