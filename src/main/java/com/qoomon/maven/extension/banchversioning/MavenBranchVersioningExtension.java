package com.qoomon.maven.extension.banchversioning;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
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
                GAV gav = GAV.of(project);
                throw new MavenExecutionException("Version Format validation", new IllegalArgumentException(gav + " version does not match semantic versioning pattern " + SEMVER_PATTERN));
            }
        }

        logger.info("--- maven-branch-versioning-extension ");

        try {
            File gitDirectory = new File(mavenSession.getExecutionRootDirectory());
            FileRepositoryBuilder repositoryBuilder = new FileRepositoryBuilder()
                    .findGitDir(gitDirectory);  // scan up the file system tree

            String branchName;
            try (Repository repository = repositoryBuilder.build()) {
                branchName = repository.getBranch();
            }

            logger.info("current branch: " + branchName);
            updateProjectVersionsToBranchVersions(projects, branchName);

        } catch (Exception e) {
            logger.error("", e);
            throw new MavenExecutionException("Error while determine project version(s)", e);
        }
    }

    private void updateProjectVersionsToBranchVersions(List<MavenProject> projects, String branchName) throws IOException {
        Map<GAV, String> versionMap = determineProjectVersions(projects, branchName);

        versionMap.forEach((gav, branchVersion) -> logger.info("set version to " + branchVersion + " @ " + gav));
        for (MavenProject project : projects) {
            updateProjectVersion(versionMap, project);
        }
    }

    private Map<GAV, String> determineProjectVersions(List<MavenProject> projects, String branchName) throws IOException {

        Map<GAV, String> versionMap = Maps.newHashMap();
        for (MavenProject project : projects) {
            String branchVersion;
            // Main Branch
            if (mainReleaseBranch.equalsIgnoreCase(branchName)) {
                branchVersion = project.getVersion().replaceFirst("-SNAPSHOT$","");
            } else {
                String branchNameUnified = branchName.replaceAll("[^A-Za-z0-9]", "_");
                // Release Branches
                if (releaseBranchPrefixSet.stream().anyMatch(prefix -> branchName.matches("^" + prefix))) {
                    branchVersion = branchNameUnified + "-" + project.getVersion().replaceFirst("-SNAPSHOT$","");
                }
                // SNAPSHOT Branches
                else {
                    branchVersion = branchNameUnified + "-SNAPSHOT";
                }
            }
            versionMap.put(GAV.of(project), branchVersion);
        }

        return versionMap;
    }

    private void updateProjectVersion(Map<GAV, String> newProjectVersionMap, MavenProject project) throws IOException {
        GAV projectGav = GAV.of(project);

        // --- update project ---
        {
            // update project parent version
            // No need to update parent version, because model is in memory

            // update project version
            String projectVersionUpdated = newProjectVersionMap.get(projectGav);
            project.setVersion(projectVersionUpdated);
            project.getArtifact().setVersionRange(VersionRange.createFromVersion(projectVersionUpdated));
        }

        // --- update project model ---
        {
            {
                Model projectModel = project.getModel();
                // update project model parent version
                Parent projectModelParent = projectModel.getParent();
                if (projectModelParent != null) {
                    // update parent version
                    projectModelParent.setVersion(project.getParent().getVersion());
                }

                // update project model version
                projectModel.setVersion(project.getVersion());
            }
            {
                Model projectModelOriginal = project.getOriginalModel();
                // update project model parent version
                Parent projectModelOriginalParent = projectModelOriginal.getParent();
                if (projectModelOriginalParent != null) {
                    // update parent version
                    projectModelOriginalParent.setVersion(project.getParent().getVersion());
                }

                // update project model version
                projectModelOriginal.setVersion(project.getVersion());
            }

            updateProjectFile(project);
        }
    }

    private void updateProjectFile(MavenProject project) throws IOException {
        Model originalModel = project.getOriginalModel();
        logger.debug("override temp pom");
        File newPomFile = File.createTempFile("pom", ".xml");
        newPomFile.deleteOnExit();
        try (FileWriter fileWriter = new FileWriter(newPomFile)) {
            new MavenXpp3Writer().write(fileWriter, originalModel);
            project.setPomFile(newPomFile);
        }
    }
}