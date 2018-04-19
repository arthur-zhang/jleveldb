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
 * 
 * This file is translated from source code file Copyright (c) 2011 
 * The LevelDB Authors and licensed under the BSD-3-Clause license.
 */

package com.tchaicatkovsky.jleveldb.db;

public class LogFormat {
	public enum RecordType {
		// Zero is reserved for preallocated files
		ZeroType(0),
		
		FullType(1),
		
		// For fragments
		FirstType(2),
		MiddleType(3),
		LastType(4);
		
		int type;
		
		private RecordType(int type) {
			this.type = type;
		}
		
		public int getType() {
			return type;
		}
	}
	
	public static final int kMaxRecordType = RecordType.LastType.getType();
	
	public static final int kBlockSize = 32768;
	
	public static final int kHeaderSize = 4 + 2 + 1;
}
