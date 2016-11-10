package com.qoomon.maven.extension.banchversioning;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Created by qoomon on 04/11/2016.
 */

@Component(role = AbstractMavenLifecycleParticipant.class, hint = "MavenBranchVersion")
public class MavenBranchVersioningExtension extends AbstractMavenLifecycleParticipant {

    /**
     * Settings
     */

    private String mainReleaseBranch = "master";

    private Set<String> releaseBranchPrefixSet = Sets.newHashSet("support-", "support/");

    public static final String RELEASE_PROFILE_NAME = "release";

    /**
     * Options
     */

    public static final String DISABLE_BRANCH_VERSIONING_PROPERTY_KEY = "disableBranchVersioning";


    /**
     * Constants
     */
    // see http://semver.org/#semantic-versioning-200
    private final Pattern SEMVER_PATTERN = Pattern.compile("^(0|[1-9][0-9]*)(\\.(0|[1-9][0-9]*))?(\\.(0|[1-9][0-9]*))?(-[a-zA-Z][a-zA-Z0-9]*)?(\\+[a-zA-Z0-9]+)?$");


    @Requirement
    private Logger logger;


    @Override
    public void afterSessionStart(MavenSession mavenSession) throws MavenExecutionException {
    }


    @Override
    public void afterProjectsRead(MavenSession mavenSession) throws MavenExecutionException {
        Boolean disableExtension = Boolean.valueOf(mavenSession.getUserProperties().getProperty(DISABLE_BRANCH_VERSIONING_PROPERTY_KEY, "false"));
        if (disableExtension) {
            return;
        }


        File gitDirectory = new File(mavenSession.getExecutionRootDirectory());
        FileRepositoryBuilder repositoryBuilder = new FileRepositoryBuilder().findGitDir(gitDirectory);

        {
            logger.info("--- maven-semantic-versioning-extension ");

            // ensure semantic version format
            List<MavenProject> projects = mavenSession.getAllProjects();
            for (MavenProject project : projects) {
                logger.info("Ensure semantic version format @ " + project.getArtifact());
                if (!SEMVER_PATTERN.matcher(project.getVersion()).matches()) {
                    throw new MavenExecutionException("Version validation error", new IllegalArgumentException(project.getArtifact() + " version does not match semantic versioning pattern " + SEMVER_PATTERN));
                }
            }
            logger.info("");
        }

        {
            logger.info("--- maven-branch-versioning-extension ");

            List<MavenProject> projects = mavenSession.getAllProjects();

            // ensure snapshot versions
            for (MavenProject project : projects) {
                logger.info("Ensure snapshot version @ " + project.getArtifact());
                if (!project.getVersion().endsWith("-SNAPSHOT")) {
                    throw new MavenExecutionException("Version validation error", new IllegalArgumentException(project.getArtifact() + " version is not a snapshot version"));
                }
            }

            // update project version to branch version for current maven session
            try (Repository repository = repositoryBuilder.build()) {
                updateProjectVersionsToBranchVersions(projects, repository);
            } catch (Exception e) {
                logger.error("", e);
                throw new MavenExecutionException("", e);
            }
            logger.info("");
        }

        {
            logger.info("--- maven-branch-release-extension ");

            // enable release profile ofr release branches
            try (Repository repository = repositoryBuilder.build()) {
                String branchName = repository.getBranch();
                if (branchName.equals(mainReleaseBranch) || releaseBranchPrefixSet.stream().anyMatch(branchName::startsWith)) {
                    if (mavenSession.getSettings().getProfiles().contains(RELEASE_PROFILE_NAME)) {
                        logger.info("Activate " + RELEASE_PROFILE_NAME + "profile");
                        mavenSession.getSettings().addActiveProfile(RELEASE_PROFILE_NAME);
                    } else {
                        logger.info("No " + RELEASE_PROFILE_NAME + "profile");
                    }
                }
            } catch (Exception e) {
                logger.error("", e);
                throw new MavenExecutionException("", e);
            }
            logger.info("");
        }


    }

    private void updateProjectVersionsToBranchVersions(List<MavenProject> projects, Repository repository) throws IOException {

        Map<Artifact, String> versionMap = determineProjectBranchVersions(projects, repository);

        for (MavenProject project : projects) {
            updateProjectVersion(versionMap, project);
        }
    }

    private Map<Artifact, String> determineProjectBranchVersions(List<MavenProject> projects, Repository repository) throws IOException {

        String branchName = repository.getBranch();
        logger.debug("branch: " + branchName);
        String commitHash = repository.resolve("HEAD").getName();
        logger.debug("commit: " + commitHash);

        boolean detachedHead = branchName.equals(commitHash);
        if (!detachedHead) {
            logger.info("Branch: " + branchName);
        } else {
            logger.info("Branch: (HEAD detached at " + commitHash + ")");
        }

        Map<Artifact, String> versionMap = Maps.newHashMap();
        for (MavenProject project : projects) {
            String projectVersion = project.getVersion();
            String releaseVersion = projectVersion.replaceFirst("-SNAPSHOT$", "");

            String branchVersion;
            // Detached HEAD
            if (detachedHead) {
                branchVersion = commitHash;
            }
            // Main Branch
            else if (mainReleaseBranch.equalsIgnoreCase(branchName)) {
                branchVersion = releaseVersion;
            }
            // Release Branches
            else if (releaseBranchPrefixSet.stream().anyMatch(branchName::startsWith)) {
                branchVersion = branchName + "-" + releaseVersion;
            }
            // SNAPSHOT Branches
            else {
                branchVersion = branchName + "-SNAPSHOT";
            }

            logger.info("Processing change of " + project.getArtifact() + " -> " + branchVersion);

            versionMap.put(project.getArtifact(), branchVersion);
        }

        return versionMap;
    }

    private void updateProjectVersion(Map<Artifact, String> newProjectVersionMap, MavenProject project) throws IOException {

        // --- update project ---
        {
            // update project parent version
            // No need to update parent version, because model is in memory

            // update project version
            String projectVersionUpdated = newProjectVersionMap.get(project.getArtifact());
            logger.debug(project.getArtifactId() + " set version " + projectVersionUpdated);
            project.setVersion(projectVersionUpdated);

            VersionRange versionRange = VersionRange.createFromVersion(projectVersionUpdated);
            logger.debug(project.getArtifactId() + " set version range " + projectVersionUpdated);
            project.getArtifact().setVersionRange(versionRange);
        }

        // --- update project model ---
        {
            {
                Model projectModel = project.getModel();
                // update project model parent version
                if (projectModel.getParent() != null) {
                    // update parent version
                    logger.debug(project.getArtifactId() + " set model parent version " + project.getParent().getVersion());
                    projectModel.getParent().setVersion(project.getParent().getVersion());
                }

                // update project model version
                logger.debug(project.getArtifactId() + " set model version " + project.getVersion());
                projectModel.setVersion(project.getVersion());
            }
            {
                Model projectModelOriginal = project.getOriginalModel();
                // update project model parent version
                if (projectModelOriginal.getParent() != null) {
                    // update parent version
                    logger.debug(project.getArtifactId() + " set original model parent version " + project.getParent().getVersion());
                    projectModelOriginal.getParent().setVersion(project.getParent().getVersion());
                }

                // update project model version
                logger.debug(project.getArtifactId() + " set original model version " + project.getVersion());
                projectModelOriginal.setVersion(project.getVersion());
            }

            updateProjectFile(project);
        }

    }

    private void updateProjectFile(MavenProject project) throws IOException {
        Model originalModel = project.getOriginalModel();
        File newPomFile = File.createTempFile("pom", ".xml");
        newPomFile.deleteOnExit();
        try (FileWriter fileWriter = new FileWriter(newPomFile)) {
            new MavenXpp3Writer().write(fileWriter, originalModel);
        }
        logger.debug(project.getArtifactId() + " set pom file");
        project.setPomFile(newPomFile);
    }


    @Override
    public void afterSessionEnd(MavenSession session) throws MavenExecutionException {
    }
}