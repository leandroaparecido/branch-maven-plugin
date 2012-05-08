/*
 * Copyright (C) 2012 Leandro Aparecido <lehphyro@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.lehphyro.branchplugin;

import static org.twdata.maven.mojoexecutor.MojoExecutor.*;

import java.io.*;
import java.util.*;

import org.apache.maven.execution.*;
import org.apache.maven.plugin.*;
import org.apache.maven.project.*;
import org.codehaus.plexus.util.*;

/**
 * Creates maintenance branches for previously released versions.
 * 
 * @author leandro.aparecido
 * @goal maintenance
 */
public class MaintenanceMojo extends AbstractMojo {
	/**
	 * Version to base the branch on. Defaults to latest release.
	 * 
	 * @parameter expression="${baseVersion}"
	 */
	protected String baseVersion;

	/**
	 * The Maven Project Object
	 * 
	 * @parameter expression="${project}"
	 * @required
	 * @readonly
	 */
	protected MavenProject project;

	/**
	 * The Maven Session Object
	 * 
	 * @parameter expression="${session}"
	 * @required
	 * @readonly
	 */
	protected MavenSession session;

	/**
	 * The Maven PluginManager Object
	 * 
	 * @component
	 * @required
	 */
	protected BuildPluginManager pluginManager;

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		String releasedMajor;
		String releasedMinor;
		String releasedIncremental;

		if (baseVersion == null) {
			executeMojo(plugin(groupId("org.codehaus.mojo"), artifactId("build-helper-maven-plugin"), version("1.7")), goal("released-version"),
					configuration(), executionEnvironment(project, session, pluginManager));
	
			releasedMajor = project.getProperties().getProperty("releasedVersion.majorVersion");
			releasedMinor = project.getProperties().getProperty("releasedVersion.minorVersion");
			releasedIncremental = project.getProperties().getProperty("releasedVersion.incrementalVersion");
		} else {
			String[] parts = baseVersion.split("\\.");
			if (parts.length < 2) {
				throw new MojoExecutionException("Invalid version: " + baseVersion);
			} else {
				if (parts.length > 2) {
					releasedIncremental = parts[2];
				} else {
					releasedIncremental = null;
				}
				releasedMinor = parts[1];
				releasedMajor = parts[0];
			}
		}

		if (releasedMajor == null) {
			throw new MojoExecutionException("No release found for this project, cannot create maintenance branch");
		}

		getLog().info(String.format("Release version is [%s]", calculateReleaseVersion(releasedMajor, releasedMinor, releasedIncremental)));

		String tagName = calculateTagName(releasedMajor, releasedMinor, releasedIncremental);
		getLog().info(String.format("Creating branch from tag [%s]", tagName));

		String branchName = calculateBranchName(releasedMajor, releasedMinor);
		String branchVersion = calculateBranchVersion(releasedMajor, releasedMinor, releasedIncremental);
		getLog().info(String.format("Branch [%s] will be created with version [%s]", branchName, branchVersion));

		try {
			createBranch(branchName, tagName);
		} catch (IOException e) {
			throw new MojoExecutionException("Could not create branch", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return;
		}
		getLog().info(String.format("Branch [%s] created successfully", branchName));

		getLog().info("Updating project version");
		try {
			setBranchVersion(branchVersion);
		} catch (IOException e) {
			throw new MojoExecutionException("Could not commit version change", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return;
		}
	}

	protected String calculateReleaseVersion(String releasedMajor, String releasedMinor, String releasedIncremental) {
		StringBuilder version = new StringBuilder();
		version.append(releasedMajor);
		version.append('.');
		version.append(releasedMinor);
		if (releasedIncremental != null) {
			version.append('.');
			version.append(releasedIncremental);
		}

		return version.toString();
	}

	protected String calculateTagName(String releasedMajor, String releasedMinor, String releasedIncremental) {
		StringBuilder tagName = new StringBuilder();
		tagName.append(project.getArtifactId());
		tagName.append('-');
		tagName.append(releasedMajor);
		tagName.append('.');
		tagName.append(releasedMinor);
		if (releasedIncremental != null) {
			tagName.append('.');
			tagName.append(releasedIncremental);
		}
		return tagName.toString();
	}

	protected String calculateBranchName(String releasedMajor, String releasedMinor) {
		StringBuilder branchName = new StringBuilder();
		branchName.append(project.getArtifactId());
		branchName.append('-');
		branchName.append(releasedMajor);
		branchName.append('.');
		branchName.append(releasedMinor);
		branchName.append(".x");
		return branchName.toString();
	}

	protected String calculateBranchVersion(String releasedMajor, String releasedMinor, String releasedIncremental) {
		StringBuilder branchVersion = new StringBuilder();
		branchVersion.append(releasedMajor);
		branchVersion.append('.');
		branchVersion.append(releasedMinor);
		branchVersion.append('.');
		if (releasedIncremental == null) {
			branchVersion.append('1');
		} else {
			branchVersion.append(Integer.parseInt(releasedIncremental) + 1);
		}
		branchVersion.append("-SNAPSHOT");
		return branchVersion.toString();
	}

	protected void createBranch(String branchName, String tagName) throws IOException, InterruptedException, MojoExecutionException {
		ProcessBuilder processBuilder = new ProcessBuilder(getOsSpecificCommand("git diff --exit-code"));
		processBuilder.directory(project.getBasedir());
		processBuilder.redirectErrorStream(true);

		Process process = processBuilder.start();
		StringOutputStream sos = new StringOutputStream();
		IOUtil.copy(process.getInputStream(), sos);
		getLog().debug("git diff output: " + sos.toString());
		int statusCode = process.waitFor();
		if (statusCode != 0) {
			throw new MojoExecutionException("There are local modifications, please commit them before creating the maintenance branch");
		}

		processBuilder.command(getOsSpecificCommand("git diff --cached --exit-code"));
		process = processBuilder.start();
		sos = new StringOutputStream();
		IOUtil.copy(process.getInputStream(), sos);
		getLog().debug("git diff cached output: " + sos.toString());
		statusCode = process.waitFor();
		if (statusCode != 0) {
			throw new MojoExecutionException("There are local modifications, please commit them before creating the maintenance branch");
		}

		processBuilder.command(getOsSpecificCommand("git checkout -b " + branchName + " " + tagName));
		process = processBuilder.start();
		sos = new StringOutputStream();
		IOUtil.copy(process.getInputStream(), sos);
		getLog().debug("git checkout output: " + sos.toString());
		int branchCode = process.waitFor();
		if (branchCode != 0) {
			// A tag pode ter um nome sem incremental version
			String tagNameNoIncremental = tagName.substring(0, tagName.lastIndexOf('.'));
			processBuilder.command(getOsSpecificCommand("git checkout -b " + branchName + " " + tagNameNoIncremental));
			process = processBuilder.start();
			sos = new StringOutputStream();
			IOUtil.copy(process.getInputStream(), sos);
			getLog().debug("git checkout output: " + sos.toString());
			branchCode = process.waitFor();
			if (branchCode != 0) {
				throw new MojoExecutionException("Could not create branch from tag, status code: " + branchCode);
			}
		}
	}

	protected void setBranchVersion(String branchVersion) throws IOException, InterruptedException, MojoExecutionException {
		executeMojo(plugin(groupId("org.codehaus.mojo"), artifactId("versions-maven-plugin"), version("1.3.1")), goal("set"),
				configuration(element("newVersion", branchVersion), element("generateBackupPoms", "false")),
				executionEnvironment(project, session, pluginManager));

		ProcessBuilder processBuilder = new ProcessBuilder(getOsSpecificCommand("git commit -am \"preparing maintenance branch for development\""));
		processBuilder.directory(project.getBasedir());
		processBuilder.redirectErrorStream(true);

		Process process = processBuilder.start();
		StringOutputStream sos = new StringOutputStream();
		IOUtil.copy(process.getInputStream(), sos);
		getLog().debug("git commit output: " + sos.toString());
		int commitCode = process.waitFor();
		if (commitCode != 0) {
			throw new MojoExecutionException("Could not commit version change, status code: " + commitCode);
		}
	}

	protected List<String> getOsSpecificCommand(String command) {
		List<String> parts = new ArrayList<String>();
		String osName = System.getProperty("os.name");
		if (osName == null) {
			parts.add("cmd.exe");
			parts.add("/X");
			parts.add("/C");
		} else {
			osName = osName.toLowerCase();
			if (osName.contains("windows")) {
				parts.add("cmd.exe");
				parts.add("/X");
				parts.add("/C");
			} else {
				parts.add("sh");
				parts.add("-c");
			}
		}
		parts.add(command);

		return parts;
	}
}
