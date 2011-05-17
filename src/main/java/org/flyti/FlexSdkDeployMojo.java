package org.flyti;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.deployer.ArtifactDeployer;
import org.apache.maven.artifact.deployer.ArtifactDeploymentException;
import org.apache.maven.artifact.installer.ArtifactInstallationException;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryFactory;
import org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout;
import org.apache.maven.plugin.MojoExecutionException;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;

import java.io.File;

/**
 * @goal deploy
 * @requiresProject false
 */
@Component(role=FlexSdkDeployMojo.class)
public class FlexSdkDeployMojo extends FlexSdkInstallMojo {
  @Requirement
  private ArtifactDeployer deployer;

  @Requirement
  private ArtifactRepositoryFactory repositoryFactory;

  @Requirement
  private ArtifactRepositoryLayout repositoryLayout;

  /**
   * Server Id to map on the &lt;id&gt; under &lt;server&gt; section of settings.xml
   * In most cases, this parameter will be required for authentication.
   *
   * @parameter expression="${repositoryId}" default-value="remote-repository"
   * @required
   */
  private String repositoryId;

  /**
   * URL where the artifact will be deployed. <br/>
   * ie ( file:///C:/m2-repo or scp://host.com/path/to/repo )
   *
   * @parameter expression="${url}"
   * @required
   */
  private String url;

  @Override
  protected void publish(Artifact artifact, File file) throws ArtifactInstallationException, MojoExecutionException {
    super.publish(artifact, file);

    final ArtifactRepository deploymentRepository;
    deploymentRepository = repositoryFactory.createDeploymentArtifactRepository(repositoryId, url, repositoryLayout, false);

    try {
      deployer.deploy(file, artifact, deploymentRepository, localRepository);
    }
    catch (ArtifactDeploymentException e) {
      throw new MojoExecutionException("Cannot deploy artifact", e);
    }
  }
}
