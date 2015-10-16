/*
 * Copyright to the original author or authors.
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
package org.rioproject.resolver.aether;

import org.apache.maven.settings.building.SettingsBuildingException;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.repository.WorkspaceReader;
import org.eclipse.aether.repository.WorkspaceRepository;
import org.rioproject.resolver.aether.util.SettingsUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A {@code WorkspaceReader} that checks if the artifact is in the local repository.
 *
 * @author Dennis Reedy
 */
public class LocalRepositoryWorkspaceReader implements WorkspaceReader, FlatDirectoryReader {
    private final WorkspaceRepository workspaceRepository = new WorkspaceRepository();
    private final String localRepositoryLocation;
    private final List<File> directories = new ArrayList<File>();
    private static final Logger logger = LoggerFactory.getLogger(LocalRepositoryWorkspaceReader.class);

    public LocalRepositoryWorkspaceReader() throws SettingsBuildingException {
        localRepositoryLocation = SettingsUtil.getLocalRepositoryLocation(SettingsUtil.getSettings());
    }

    public WorkspaceRepository getRepository() {
        return workspaceRepository;
    }

    public void addDirectories(Collection<File> directories) {
        if(directories!=null)
            this.directories.addAll(directories);
    }

    public File findArtifact(Artifact artifact) {
        if(!artifact.isSnapshot()) {
            File artifactFile = new File(localRepositoryLocation, getArtifactPath(artifact));
            if(artifactFile.exists())
                return artifactFile;
            return findArtifactInFlatDirectories(artifact);
        }
        return null;
    }

    protected String getLocalRepositoryLocation() {
        return localRepositoryLocation;
    }

    public List<String> findVersions(Artifact artifact) {
        return new ArrayList<String>();
    }

    protected String getArtifactPath(final Artifact a) {
        String sep = File.separator;
        StringBuilder path = new StringBuilder();
        path.append(a.getGroupId().replace('.', File.separatorChar));
        path.append(sep);
        path.append(a.getArtifactId());
        path.append(sep);
        path.append(a.getVersion());
        path.append(sep);
        path.append(getFileName(a));
        return path.toString();
    }

    File findArtifactInFlatDirectories(Artifact artifact) {
        File artifactFile = null;
        if(!directories.isEmpty()) {
            String fileName = getFileName(artifact);
            if (logger.isDebugEnabled())
                logger.debug("Resolve {}, file {}", artifact.toString(), fileName);
            for (File directory : directories) {
                File file = new File(directory, fileName);
                if (file.exists()) {
                    if (logger.isDebugEnabled())
                        logger.debug("Resolved {}, file {}", artifact.toString(), file.getPath());
                    artifactFile = file;
                    break;
                }
            }

            if (artifactFile == null) {
                if (logger.isWarnEnabled()) {
                    if (directories.isEmpty()) {
                        logger.warn("Did not resolve {}", artifact.toString());
                    } else {
                        StringBuilder b = new StringBuilder();
                        for (File d : directories) {
                            if (b.length() > 0)
                                b.append("\n");
                            b.append("\t").append(d.getPath());
                        }
                        logger.warn("Did not resolve {} using the following flat directories\n{}",
                                    artifact.toString(), b.toString());
                    }
                }
            }
        }
        return artifactFile;
    }

    String getFileName(Artifact a) {
        org.rioproject.resolver.Artifact artifact = new org.rioproject.resolver.Artifact(a.getGroupId(),
                                                                                         a.getArtifactId(),
                                                                                         a.getVersion(),
                                                                                         a.getExtension(),
                                                                                         a.getClassifier());
        return artifact.getFileName();
    }

}
