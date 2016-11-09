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

    // see http://semver.org/#semantic-versioning-200
    private final Pattern SEMVER_PATTERN = Pattern.compile("^(0|[1-9][0-9]*)(\\.(0|[1-9][0-9]*))?(\\.(0|[1-9][0-9]*))?(-[a-zA-Z][a-zA-Z0-9]*)?(\\+[a-zA-Z0-9]+)?$");

    private String mainReleaseBranch = "master";

    private Set<String> releaseBranchPrefixSet = Sets.newHashSet("support-", "support/");

    @Requirement
    private Logger logger;


    @Override
    public void afterProjectsRead(MavenSession mavenSession) throws MavenExecutionException {
        List<MavenProject> projects = mavenSession.getAllProjects();

        logger.info("--- maven-semantic-versioning-extension ");
        // check for semantic versioning format
        for (MavenProject project : projects) {
            if (!SEMVER_PATTERN.matcher(project.getVersion()).matches()) {
                throw new MavenExecutionException("Version Format validation", new IllegalArgumentException(project.getArtifact() + " version does not match semantic versioning pattern " + SEMVER_PATTERN));
            }
        }

        logger.info("--- maven-branch-versioning-extension ");

        try {
            File gitDirectory = new File(mavenSession.getExecutionRootDirectory());
            FileRepositoryBuilder repositoryBuilder = new FileRepositoryBuilder()
                    .findGitDir(gitDirectory);  // scan up the file system tree

            try (Repository repository = repositoryBuilder.build()) {
                updateProjectVersionsToBranchVersions(projects, repository);
            }

        } catch (Exception e) {
            logger.error("", e);
            throw new MavenExecutionException("Error while determine project version(s)", e);
        }
    }

    private void updateProjectVersionsToBranchVersions(List<MavenProject> projects, Repository repository) throws IOException {

        Map<Artifact, String> versionMap = determineProjectVersions(projects, repository);

        for (MavenProject project : projects) {
            updateProjectVersion(versionMap, project);
        }
    }

    private Map<Artifact, String> determineProjectVersions(List<MavenProject> projects, Repository repository) throws IOException {

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

            logger.info(project.getArtifact() + System.lineSeparator()
                    + "       Branch version: " + branchVersion);

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

}