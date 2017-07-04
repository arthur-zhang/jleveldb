/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tchaicatkovsky.jleveldb;

import java.util.ServiceLoader;

import com.tchaicatkovsky.jleveldb.util.Object0;

public class LevelDB {

	private static DBTools dbtools = null;
	private static Env defaultEnv = null;
	private static DB defaultDB = null;

	public static Status newDB(Options options, String name, Object0<DB> dbOut) throws Exception {
		if (defaultDB == null) {
			ServiceLoader<DB> serviceLoader = ServiceLoader.load(DB.class);
			for (DB db0 : serviceLoader) {
				defaultDB = db0;
				break;
			}
		}
		dbOut.setValue(null);
		DB newOne = defaultDB.getClass().newInstance();
		Status s = newOne.open(options, name);
		if (s.ok())
			dbOut.setValue(newOne);
		else {
			newOne.close();
		}

		return s;
	}

	public static Status destroyDB(String dbname, Options options) throws Exception {
		if (dbtools == null) {
			ServiceLoader<DBTools> serviceLoader = ServiceLoader.load(DBTools.class);
			for (DBTools dbt0 : serviceLoader) {
				dbtools = dbt0;
				break;
			}
		}
		return dbtools.destroyDB(dbname, options);
	}

	public static Status repairDB(String dbname, Options options) throws Exception {
		if (dbtools == null) {
			ServiceLoader<DBTools> serviceLoader = ServiceLoader.load(DBTools.class);
			for (DBTools dbt0 : serviceLoader) {
				dbtools = dbt0;
				break;
			}
		}
		return dbtools.repairDB(dbname, options);
	}

	public static Env defaultEnv() {
		if (defaultEnv == null) {
			ServiceLoader<Env> serviceLoader = ServiceLoader.load(Env.class);
			for (Env env0 : serviceLoader) {
				defaultEnv = env0;
				break;
			}
		}
		return defaultEnv;
	}
}
