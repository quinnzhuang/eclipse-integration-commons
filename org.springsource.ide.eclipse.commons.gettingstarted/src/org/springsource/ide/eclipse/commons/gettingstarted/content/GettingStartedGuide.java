/*******************************************************************************
 *  Copyright (c) 2013 VMware, Inc.
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  Contributors:
 *      VMware, Inc. - initial API and implementation
 *******************************************************************************/
package org.springsource.ide.eclipse.commons.gettingstarted.content;

import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jackson.annotate.JsonIgnoreProperties;
import org.codehaus.jackson.annotate.JsonProperty;
import org.codehaus.jackson.map.ObjectMapper;
import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.IPath;
import org.springsource.ide.eclipse.commons.gettingstarted.GettingStartedActivator;
import org.springsource.ide.eclipse.commons.gettingstarted.content.CodeSet.CodeSetEntry;
import org.springsource.ide.eclipse.commons.gettingstarted.github.Repo;
import org.springsource.ide.eclipse.commons.gettingstarted.util.DownloadManager;
import org.springsource.ide.eclipse.commons.gettingstarted.util.UIThreadDownloadDisallowed;
import org.springsource.ide.eclipse.commons.livexp.core.ValidationResult;

/**
 * Content for a GettingStartedGuide provided via a Github Repo
 * 
 * @author Kris De Volder
 */
public class GettingStartedGuide extends GithubRepoContent {

	protected Repo repo;

	private List<CodeSet> codesets;

	public static final String GUIDE_DESCRIPTION_TEXT = 
			"A guide is a short focussed tutorial "
			+ "on how to use Spring to accomplish a specific task. " 
			+ "It has an 'initial' code set, a 'complete' code" 
			+ "set and a readme file explaining how you get from "
			+ "one to the other.";

	/**
	 * All getting started guides are supposed to have the same codesets names. This constant defines those
	 * names.
	 */
	public static final CodeSetMetaData[] defaultCodesets = {
		new CodeSetMetaData("initial").desciption(
				"Import this if you want to work " +
				"through the bulk of the guide, but skip basic project setup"
		), 
		new CodeSetMetaData("complete").desciption(
				"A project representing the code in its 'complete' state, at " +
				"the end of the guide."
		)
	};
	
	/**
	 * Relative path from the 'root' codeset to where the optional
	 * metadata file is that describes codeset layout for projects
	 * that don't follow the default layout.
	 */
	private static final String CODE_SET_METADATA = ".codesets.json";
	
	private static String[] _defaultCodesetNames;
	public static String[] defaultCodesetNames() {
		if (_defaultCodesetNames==null) {
			String[] defaultCodesetNames = new String[defaultCodesets.length];
			for (int i = 0; i < defaultCodesets.length; i++) {
				defaultCodesetNames[i] = defaultCodesets[i].name;
			}
			_defaultCodesetNames = defaultCodesetNames;
		}
		return _defaultCodesetNames;
	}

	
	public GettingStartedGuide(Repo repo, DownloadManager dl) {
		super(dl);
		this.repo = repo;
	}
	
	@Override
	public String toString() {
		return this.getClass().getSimpleName()+"("+getName()+")";
	}
	
	public List<CodeSet> getCodeSets() throws UIThreadDownloadDisallowed {
		if (codesets==null) {
			CodeSet root = CodeSet.fromZip("ROOT", getZip(), getRootPath());
			if (root.hasFile(CODE_SET_METADATA)) {
				try {
					//TODO: we have to parse the metadata file and extract the codeset names and locations from it.
					CodeSetMetaData[] metadata = root.readFileEntry(CODE_SET_METADATA, new CodeSet.Processor<CodeSetMetaData[]>() {
						@Override
						public CodeSetMetaData[] doit(CodeSetEntry e) throws Exception {
							InputStream in = e.getData();
							try {
								ObjectMapper mapper = new ObjectMapper();
								return mapper.readValue(in, CodeSetMetaData[].class);
							} finally {
								in.close();
							}
						}
					});
					if (metadata==null) {
						metadata = new CodeSetMetaData[0];
					}
					CodeSet[] array = new CodeSet[metadata.length];
					for (int i = 0; i < array.length; i++) {
						String name = metadata[i].name;
						String dir = metadata[i].dir;
						Assert.isLegal(dir!=null||name!=null, ".codesets.json objects must specify either a 'dir' or a 'name' or both.");
						if (dir==null) {
							dir = name; //Use the name as the default. The convention is that a codeset is in a sudirectory with the same name as
							            // the codeset name.
						}
						//'dir' can't be null at this point because of the assert above and the default value computed from the name
						IPath zipPath = getRootPath().append(dir);
						array[i] = CodeSet.fromZip(name, getZip(), zipPath);
					}
					//Success parsing .codesets.json and initialising codesets field.
					codesets = Arrays.asList(array);
					return codesets;
				} catch (Throwable e) {
					GettingStartedActivator.log(e);
				}
			}
			//We get here if either
			//   - there's no .codeset.json 
			//   - .codeset.json is broken.
			CodeSet[] array = new CodeSet[defaultCodesetNames().length];
			for (int i = 0; i < array.length; i++) {
				array[i] = CodeSet.fromZip(defaultCodesetNames()[i], getZip(), getRootPath().append(defaultCodesetNames()[i]));
			}
			codesets = Arrays.asList(array);
		}
		return codesets;
	}

	@Override
	public Repo getRepo() {
		return this.repo;
	}

	/**
	 * Creates a validator that checks whether a given build type is supported by a project. This only
	 * consider project content, not whether requisite build tooling is installed.
	 * <p>
	 * This validator needs access to the content. Thus it forces the content to be downloaded.
	 * Content is downloaded in a background job so as not to block the UI thread in which
	 * this method is typically called to provide validation logic for a wizard.
	 * If this method does get called from the UIThread may throw a {@link UIThreadDownloadDisallowed}
	 * exception unless the required content is already cached locally. It is up to the client
	 * to dealt with this situation (e.g. by triggering a background download and showing a 
	 * temporary info message until download is complete. 
	 */
	public ValidationResult validateBuildType(BuildType bt) throws UIThreadDownloadDisallowed {
		for (CodeSet cs : getCodeSets()) {
			ValidationResult result = cs.validateBuildType(bt);
			if (!result.isOk()) {
				return result;
			}
		}
		return ValidationResult.OK;
	}
	
	private String beatify(String name) {
		if (name.startsWith("gs-")) {
			name = name.substring(3);
		}
		String[] words = name.split("\\-");
		StringBuilder buf = new StringBuilder();
		for (int i = 0; i < words.length; i++) {
			String w = words[i];
			if (w.length()>0) {
				buf.append(w.substring(0,1).toUpperCase());
				buf.append(w.substring(1));
			}
			buf.append(' ');
		}
		return buf.toString();
	}
	
	@Override
	public URL getHomePage() {
		//Looks like this now:
		//http://sagan.cfapps.io/guides/gs/spring-boot/
		try {
			String gsGuideName = getName();
			if (gsGuideName.startsWith("gs-")) {
				String guideName = gsGuideName.substring(3);
				return new URL("http://sagan-staging.cfapps.io/guides/gs/"+guideName);
			}
		} catch (MalformedURLException e) {
			GettingStartedActivator.log(e);
		}
		//Fallback on default implementation if custom logic failed
		return super.getHomePage();
	}
	
	/**
	 * A more 'beautiful' name derived from the guide's repository name.
	 */
	public String getDisplayName() {
		return beatify(getName());
	}
	
	/** 
	 * Metadata elements parsed from .codesets.json file are represented as instances of
	 * this class.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	static public class CodeSetMetaData {
		@JsonProperty
		public String name;
		@JsonProperty
		public String dir;
		
		@JsonProperty 
		public String description;

		/**
		 * No args constructor (needed for Jackson mapper).
		 */
		public CodeSetMetaData() {
		}
		
		public CodeSetMetaData(String name) {
			this.name = name;
			this.dir = dir;
		}
		
		public CodeSetMetaData desciption(String d) {
			description = d;
			return this;
		}

		public String toString() {
			return "CodeSetMD(name = "+name+", dir = "+dir+")";
		}
	}

	
}
