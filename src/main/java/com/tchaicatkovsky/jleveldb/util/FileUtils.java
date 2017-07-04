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
package com.tchaicatkovsky.jleveldb.util;

import java.io.File;

/**
 * @author Teng Huang ht201509@163.com
 */
public class FileUtils {
	 public static void deletePath(String path) {
		 deletePath(new File(path));
	 }
	 
	 public static void deletePath(File path) {
	    if (!path.exists())
	        return;
	    
	    if (path.isFile()) {
	        path.delete();
	        return;  
	    }
	    
	    File[] files = path.listFiles();  
	    for (int i = 0; i < files.length; i++) {  
	    	deletePath(files[i]);  
	    }
	    
	    path.delete();
	}
}

