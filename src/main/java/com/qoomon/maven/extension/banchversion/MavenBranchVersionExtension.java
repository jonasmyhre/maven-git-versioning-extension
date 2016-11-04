package com.qoomon.maven.extension.banchversion;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.ModelProcessor;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

/**
 * Created by qoomon on 04/11/2016.
 */
// your extension must be a "Plexus" component so mark it with the annotation
@Component(role = AbstractMavenLifecycleParticipant.class, hint = "MavenBranchVersion")
public class MavenBranchVersionExtension extends AbstractMavenLifecycleParticipant {

    @Requirement
    private Logger logger;

    @Requirement
    private PlexusContainer container;

    @Requirement
    private ModelProcessor modelProcessor;

    @Override
    public void afterProjectsRead(MavenSession mavenSession) throws MavenExecutionException {
        MavenProject rootProject = mavenSession.getTopLevelProject();
        List<MavenProject> projects = mavenSession.getAllProjects();

        logger.info("change project(s) version(s)");

        String newVersion = "JumpJump";

        // Let's modify in memory resolved projects model
        for (MavenProject project : projects) {
            logger.debug("artefact: " + project.getArtifact());

            project.setVersion(newVersion);
            logger.debug("artifact version set to " + newVersion);

            VersionRange newVersionRange = VersionRange.createFromVersion(newVersion);
            project.getArtifact().setVersionRange(newVersionRange);
            logger.debug("artifact version range set to " + newVersionRange);

            // No need to worry about parent link, because model is in memory

            if (newProjectVersions.containsKey(initalProjectGAV)) {
                model.setVersion(newProjectVersions.get(initalProjectGAV));
                updatePomVersion(project, newVersion);
            }

            if (model.getParent() != null) {
                GAV parentGAV = GAV.from(model.getParent());    // SUPPRESS CHECKSTYLE AbbreviationAsWordInName

                if (newProjectVersions.keySet().contains(parentGAV)) {
                    // parent has been modified
                    model.getParent().setVersion(newProjectVersions.get(parentGAV));
                    updatePomVersion(project, newVersion);
                }
            }



        }


    }

    private void updatePomVersion(MavenProject project, String newVersion) throws MavenExecutionException {
        try {
            try (FileReader fileReader = new FileReader(project.getFile())) {
                logger.debug("read current pom" + newVersion);
                Model mavenModel = new MavenXpp3Reader().read(fileReader);
                mavenModel.setVersion(newVersion);

                logger.debug("create new pom");
                File newPomFile = File.createTempFile("pom", ".xml");
                newPomFile.deleteOnExit();

                logger.debug("write newPomFile" );
                try (FileWriter fileWriter = new FileWriter(newPomFile)) {
                    new MavenXpp3Writer().write(fileWriter, mavenModel);

                    logger.debug("project.setPomFile(newPomFile)");
                    project.setPomFile(newPomFile);
                }
            }
        } catch (IOException | XmlPullParserException e) {
            throw new MavenExecutionException("Error during updating pom", e);
        }
    }

}