/*
 * Copyright 2015 the original author or authors.
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

package asset.pipeline.grails;

import asset.pipeline.AssetHelper;
import org.springframework.core.io.Resource;
import java.io.IOException;
import java.util.Date;

public class AssetAttributes {
	private boolean gzipExists = false;
	private boolean exists = false;
	private Resource resource;
	private Resource gzipResource;
	private boolean isDirectory = false;
	private Long fileSize;
	private Long gzipFileSize;
	private Date lastModified;
	private volatile String digest;

	public AssetAttributes(boolean exists, Boolean gzipExists, Boolean isDirectory, Long fileSize, Long gzipFileSize, Date lastModified, Resource resource, Resource gzipResource) {
		this.gzipExists = gzipExists;
		this.exists = exists;
		this.fileSize = fileSize;
		this.isDirectory = isDirectory;
		this.gzipFileSize = gzipFileSize;
		this.resource = resource;
		this.lastModified = lastModified;
		this.gzipResource = gzipResource;
	}

	public Date getLastModified() {
		return this.lastModified;
	}

	public Resource getResource() {
		return this.resource;
	}

	public Resource getGzipResource() {
		return this.gzipResource;
	}

	public boolean exists() {
		return this.exists;
	}

	public boolean isDirectory() {
		return this.isDirectory;
	}

	public boolean gzipExists() {
		return this.gzipExists;
	}

	public Long getFileSize() {
		return this.fileSize;
	}

	public Long getGzipFileSize() {
		return this.gzipFileSize;
	}

	/**
	 * The digest of the asset's bytes, which validates a name the manifest gives no digest (see
	 * {@link asset.pipeline.AssetPipelineResponseBuilder#getCurrentETag()}). It is read from the asset when first
	 * asked for and kept, so that it is computed once however often the asset is requested. Null when the asset
	 * has no resource, or when it cannot be read, which serving it will report.
	 */
	public String getDigest() {
		String current = digest;
		if (current == null && resource != null) {
			try {
				current = AssetHelper.getByteDigest(resource.getInputStream());
			} catch (IOException e) {
				return null;
			}
			digest = current;
		}
		return current;
	}
}