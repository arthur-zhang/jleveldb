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

package com.tchaicatkovsky.jleveldb;

import java.util.concurrent.atomic.AtomicBoolean;

public abstract class Logger0 {

	public abstract void delete();

	public abstract void log(String format, Object... objects);

	public static boolean enable = true;
	
	public static void disableLogger0() {
		enable = false;
	}
	
	public static void enableLogger0() {
		enable = true;
	}
	public static void log0(Logger0 log, String format, Object... objects) {
		if (log != null && enable)
			log.log(format, objects);
	}

	static AtomicBoolean kDebug = new AtomicBoolean(false);

	public static void setDebug(boolean b) {
		kDebug.set(b);
	}

	public static boolean getDebug() {
		return kDebug.get();
	}

	public static void debug(String format, Object... objects) {
		if (kDebug.get())
			System.out.printf(format, objects);
	}
}
