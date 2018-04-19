/**
 * Copyright (c) 2017-2018 Teng Huang <ht201509 at 163 dot com>
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

package com.tchaicatkovsky.jleveldb.util;

public class ByteUtils {
	final public static int memcmp(byte[] a, int aoff, byte[] b, int boff, int size) {
		for (int i = 0; i < size; i++) {
			if (a[aoff+i] < b[boff+i])
				return -1;
			else if (a[aoff+i] > b[boff+i])
				return 1;
		}
		return 0;
	}
	
	final public static int  bytewiseCompare(byte[] a, int aoff, int asize, byte[] b, int boff, int bsize) {
		int minLen = Integer.min(asize, bsize);
		int r = memcmp(a, aoff, b, boff, minLen);
		if (r == 0) {
		    if (asize < bsize) r = -1;
		    else if (asize > bsize) r = +1;
		}
		return r;
	}
}
