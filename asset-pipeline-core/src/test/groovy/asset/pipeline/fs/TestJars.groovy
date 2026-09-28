/*
* Copyright 2026 the original author or authors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package asset.pipeline.fs

import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TestJars {

	/**
	 * Writes a jar holding each of {@code paths} under META-INF/resources, and, when {@code directoryEntries}, an entry
	 * for every directory above them, as most jars have.
	 */
	static File write(File jar, List<String> paths, boolean directoryEntries = true) {
		jar.withOutputStream { OutputStream out ->
			new ZipOutputStream(out).withCloseable { ZipOutputStream zip ->
				Set<String> directories = [] as Set
				for(String path in paths.collect { "META-INF/resources/${it}" as String }) {
					List<String> components = path.split('/').toList()
					for(int i = 1; directoryEntries && i < components.size(); i++) {
						if(directories.add(components.take(i).join('/'))) {
							zip.putNextEntry(new ZipEntry(components.take(i).join('/') + '/'))
							zip.closeEntry()
						}
					}
					zip.putNextEntry(new ZipEntry(path))
					zip.write("// ${path}".bytes)
					zip.closeEntry()
				}
			}
		}
		return jar
	}
}
