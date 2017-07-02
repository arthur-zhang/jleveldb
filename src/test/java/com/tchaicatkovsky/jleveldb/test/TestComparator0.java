package com.tchaicatkovsky.jleveldb.test;

import org.junit.Test;

import com.tchaicatkovsky.jleveldb.db.format.InternalKeyComparator;
import com.tchaicatkovsky.jleveldb.util.ByteBuf;
import com.tchaicatkovsky.jleveldb.util.ByteBufFactory;
import com.tchaicatkovsky.jleveldb.util.BytewiseComparatorImpl;
import com.tchaicatkovsky.jleveldb.util.DefaultSlice;
import com.tchaicatkovsky.jleveldb.util.Slice;

public class TestComparator0 {
	@Test
	public void testBytewiseComparatorImpl() {
		Slice a = new DefaultSlice("abc");
		Slice b = new DefaultSlice("9999");
		int ret = BytewiseComparatorImpl.getInstance().compare(a, b);
		System.out.println(ret);
	}
	
	@Test
	public void testBytewiseComparatorImpl1() {
		byte[] abuf = new byte[]{(byte)0x0a, (byte)0xfd, (byte)0xfd, (byte)0x01, (byte)0x03, 
								 (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00};
		ByteBuf a = ByteBufFactory.defaultByteBuf(abuf, abuf.length);
		

		byte[] bbuf = new byte[]{(byte)0x09, (byte)0xfd, (byte)0x01, (byte)0x02,
								 (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00};
		ByteBuf b = ByteBufFactory.defaultByteBuf(bbuf, bbuf.length);
		
		InternalKeyComparator icmp = new InternalKeyComparator(BytewiseComparatorImpl.getInstance());
		//int ret = BytewiseComparatorImpl.getInstance().compare(a, b);
		int ret = icmp.compare(a, b);
		System.out.println(ret);
	}
}
