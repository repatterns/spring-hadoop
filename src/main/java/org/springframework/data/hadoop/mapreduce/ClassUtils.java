/*
 * Copyright 2011-2012 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.hadoop.mapreduce;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.util.Tool;
import org.springframework.beans.BeanUtils;
import org.springframework.core.io.Resource;

/**
 * Class-related utilities.
 * 
 * @author Costin Leau
 */
// NOTE: jars with nested /classes/ are supported as well but this functionality is disabled
// as it seems to have not been used in hadoop.
abstract class ClassUtils {

	@SuppressWarnings("unchecked")
	public static <T> T loadClassParentLast(Resource jar, ClassLoader parentClassLoader, String className, Configuration cfg) {
		ClassLoader cl = createParentLastClassLoader(jar, parentClassLoader, cfg);
		Class<? extends Tool> toolClass = (Class<? extends Tool>) org.springframework.util.ClassUtils.resolveClassName(
				className, cl);
		return (T) BeanUtils.instantiateClass(toolClass);
	}

	public static ClassLoader createParentLastClassLoader(Resource jar, ClassLoader parentClassLoader, Configuration cfg) {
		ClassLoader cl = null;

		// sanity check
		if (parentClassLoader == null) {
			parentClassLoader = org.springframework.util.ClassUtils.getDefaultClassLoader();
			cl = parentClassLoader;
		}

		// check if a custom CL is needed
		if (jar != null) {
			// check if unjarring is required (it's a legacy JAR)
			try {
				if (isLegacyJar(jar)) {
					URL[] extractedURLs = expandedJarClassPath(jar, cfg);
					cl = new ParentLastURLClassLoader(extractedURLs, parentClassLoader);
				}
				else {
					cl = new ParentLastURLClassLoader(new URL[] { jar.getURL() }, parentClassLoader);
				}

			} catch (IOException e) {
				throw new IllegalStateException("Cannot open jar file", e);
			}
		}

		return cl;
	}

	private static boolean isLegacyJar(Resource jar) throws IOException {
		JarInputStream jis = new JarInputStream(jar.getInputStream());
		JarEntry entry = null;
		try {
			while ((entry = jis.getNextJarEntry()) != null) {
				String name = entry.getName();
				if (name.startsWith("lib/") //|| name.startsWith("classes/")
				) {
					return true;
				}
			}
		} finally {
			IOUtils.closeStream(jis);
		}
		return false;
	}

	private static URL[] expandedJarClassPath(Resource jar, Configuration cfg) throws IOException {
		// detect base dir
		File baseDir = detectBaseDir(cfg);

		// expand the jar
		unjar(jar, baseDir);

		// build classpath
		List<URL> cp = new ArrayList<URL>();
		cp.add(new File(baseDir + "/").toURI().toURL());

		//cp.add(new File(baseDir + "/classes/").toURI().toURL());
		File[] libs = new File(baseDir, "lib").listFiles();
		if (libs != null) {
			for (int i = 0; i < libs.length; i++) {
				cp.add(libs[i].toURI().toURL());
			}
		}

		return cp.toArray(new URL[cp.size()]);
	}


	private static File detectBaseDir(Configuration cfg) throws IOException {
		File tmpDir = null;

		if (cfg != null) {
			tmpDir = new File(cfg.get("hadoop.tmp.dir"));
			tmpDir.mkdirs();
			if (!tmpDir.isDirectory()) {
				tmpDir = null;
			}
		}

		final File workDir = File.createTempFile("hadoop-unjar", "", tmpDir);
		workDir.delete();
		workDir.mkdirs();

		return workDir;
	}


	private static void unjar(Resource jar, File baseDir) throws IOException {
		JarInputStream jis = new JarInputStream(jar.getInputStream());
		JarEntry entry = null;
		try {
			while ((entry = jis.getNextJarEntry()) != null) {
				if (!entry.isDirectory()) {
					File file = new File(baseDir, entry.getName());
					if (!file.getParentFile().mkdirs()) {
						if (!file.getParentFile().isDirectory()) {
							throw new IOException("Mkdirs failed to create " + file.getParentFile().toString());
						}
					}
					OutputStream out = new FileOutputStream(file);
					try {
						byte[] buffer = new byte[8192];
						int i;
						while ((i = jis.read(buffer)) != -1) {
							out.write(buffer, 0, i);
						}
					} finally {
						IOUtils.closeStream(out);
					}
				}
			}
		} finally {
			IOUtils.closeStream(jis);
		}
	}
}