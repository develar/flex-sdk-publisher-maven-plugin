package org.flyti;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.installer.ArtifactInstallationException;
import org.apache.maven.artifact.installer.ArtifactInstaller;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.codehaus.plexus.archiver.Archiver;
import org.codehaus.plexus.archiver.ArchiverException;
import org.codehaus.plexus.archiver.zip.ZipArchiver;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @goal install
 * @requiresProject false
 */
@Component(role=FlexSdkInstallMojo.class)
public class FlexSdkInstallMojo extends AbstractMojo {
  private static final String FLEX_GROUP_ID = "com.adobe.flex.framework";
  private static final String SWC_TYPE = "swc";
  private static final String RB_TYPE = "rb.swc";
  private static final String SWC_EXTENSION = ".swc";

  private static final int RB_END_TRIM_LENGTH = SWC_EXTENSION.length() + "_rb".length();
  
  private static final String[] CONFIGS_INCLUDES = new String[]{"*.xml", "*.ser"};
  private static final String[] CONFIGS_EXCLUDES = new String[]{"build_framework.xml", "build.xml", "flash-unicode-table.xml", "metadata.xml"};

  private static final Set<String> SMALL_SDK_ARTIFACTS_IDS = new HashSet<String>();
  static {
    @SuppressWarnings({"MismatchedQueryAndUpdateOfCollection"})
    Set<String> set = SMALL_SDK_ARTIFACTS_IDS;
    set.add("airframework");
    set.add("framework");
    set.add("spark");
    set.add("airspark");
    set.add("airglobal");
  }

  private final List<Artifact> smallFlex = new ArrayList<Artifact>();
  private final List<Artifact> smallAir = new ArrayList<Artifact>();

  @Requirement(role=Archiver.class, hint="zip")
  private ZipArchiver zipArchiver;

  @Requirement
  private ArtifactInstaller installer;

  @SuppressWarnings({"deprecation"})
  @Requirement
  private ArtifactFactory artifactFactory;

  /**
   * File location where targeted Flex SDK is located
   * @parameter expression="${home}"
   * @required
   */
  private File home;

  /**
   * @parameter expression="${version}"
   * @required
   */
  private String version;

  /**
   * @parameter expression="${airVersion}"
   * @required
   */
  private String airVersion;

  /**
   * @parameter expression="${localRepository}"
   * @required
   * @readonly
   */
  protected ArtifactRepository localRepository;

  /**
   * @parameter expression="${skipRsls}"
   * @readonly
   */
  private boolean skipRsls;

  private File sources;
  private File frameworks;
  private File tempFile;

  public void execute() throws MojoExecutionException, MojoFailureException {
    frameworks = new File(home, "frameworks");
    sources = new File(frameworks, "projects");

    try {
      tempFile = File.createTempFile("tmp", FLEX_GROUP_ID);
      tempFile.deleteOnExit();
      
      processLibraryDirectory(new File(frameworks, "libs"));
      processLocaleDirectory();

      processConfigs();
      processThemes();
      
      if (!skipRsls) {
        processRsls();
      }

      createAggregators();
    }
    catch (Exception e) {
      throw new MojoFailureException("Cannot publish", e);
    }
  }

  private void createAggregators() throws IOException, MojoExecutionException, ArtifactInstallationException {
    createAggregator("flex-framework-small", smallFlex);
    createAggregator("air-framework-small", smallAir);
  }

  private void createAggregator(String artifactId, List<Artifact> artifacts) throws IOException, MojoExecutionException, ArtifactInstallationException {
    Model pom = new Model();
    pom.setModelVersion("4.0.0");
    pom.setGroupId(FLEX_GROUP_ID);
    pom.setArtifactId(artifactId);
    pom.setVersion(version);
    pom.setPackaging("pom");

    for (Artifact artifact : artifacts) {
      Dependency dependency = new Dependency();
      dependency.setGroupId(artifact.getGroupId());
      dependency.setArtifactId(artifact.getArtifactId());
      dependency.setClassifier(artifact.getClassifier());
      dependency.setType(artifact.getType());
      dependency.setVersion(artifact.getVersion());
      pom.addDependency(dependency);
    }

    FileWriter writer = new FileWriter(tempFile);
    try {
      new MavenXpp3Writer().write(writer, pom);
    }
    finally {
      writer.close();
    }

    publish(createArtifact(artifactId, "pom"), tempFile);
  }

  private void processRsls() {
    // todo
  }

  private void processThemes() throws MojoExecutionException, ArtifactInstallationException {
    publish(createArtifact("spark", "css", "theme"), new File(frameworks, "themes/Spark/spark.css"));
  }

  private void processConfigs() throws ArchiverException, MojoExecutionException, ArtifactInstallationException, IOException {
    zipArchiver.reset();
    zipArchiver.setIncludeEmptyDirs(false);
    zipArchiver.addDirectory(frameworks, CONFIGS_INCLUDES, CONFIGS_EXCLUDES);
    zipArchiver.setDestFile(tempFile);
    zipArchiver.createArchive();
    Artifact artifact = createArtifact("framework", "zip", "configs");
    publish(artifact, tempFile);

    smallAir.add(artifact);
    smallFlex.add(artifact);
  }

  private void processLocaleDirectory() throws MojoExecutionException, ArtifactInstallationException {
    File locales = new File(home, "frameworks/locale");
    for (String locale : locales.list()) {
      if (locale.charAt(0) != '.') {
        final boolean isEnUS = locale.equals("en_US");
        File localeDirectory = new File(locales, locale);
        for (String library : localeDirectory.list()) {
          // playerglobal_rb and flash-integrations contain only docs
          if (library.charAt(0) != '.' && !library.equals("playerglobal_rb.swc") && !library.equals("flash-integration_rb.swc")) {
            File file = new File(localeDirectory, library);
            if (file.isFile() && library.endsWith(SWC_EXTENSION)) {
              final String artifactId = library.substring(0, library.length() - RB_END_TRIM_LENGTH);
              publish(createArtifact(artifactId, RB_TYPE, locale), file);
              if (isEnUS) {
                Artifact artifact = createArtifact(artifactId, RB_TYPE);
                publish(artifact, file);
                if (SMALL_SDK_ARTIFACTS_IDS.contains(artifactId)) {
                  smallAir.add(artifact);
                  if (!artifactId.contains("air")) {
                    smallFlex.add(artifact);
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  private void processLibraryDirectory(File directory) throws MojoExecutionException, IOException, ArchiverException, ArtifactInstallationException {
    for (String name : directory.list()) {
      if (name.charAt(0) != '.') {
        File file = new File(directory, name);
        if (file.isFile()) {
          if (name.endsWith(SWC_EXTENSION)) {
            final String artifactId = filenameToArtifactId(name);
            Artifact artifact = createArtifact(artifactId, SWC_TYPE);
            if (SMALL_SDK_ARTIFACTS_IDS.contains(artifactId)) {
              smallAir.add(artifact);
              if (!artifactId.contains("air")) {
                smallFlex.add(artifact);
              }
            }

            publish(artifact, file);
            publishSource(artifactId);
          }
        }
        else if (name.equals("player")) {
          processPlayerGlobalDirectory(file);
        }
        else {
          processLibraryDirectory(file);
        }
      }
    }
  }

  private void processPlayerGlobalDirectory(File directory) throws MojoExecutionException, ArtifactInstallationException {
    boolean added = false;
    for (String version : directory.list()) {
      if (version.charAt(0) != '.') {
        Artifact artifact = artifactFactory.createArtifactWithClassifier(FLEX_GROUP_ID, "playerglobal", version, SWC_TYPE, null);
        if (added) {
          throw new IllegalArgumentException("sort playerglobal version");
        }
        added = true;
        smallFlex.add(artifact);

        publish(artifact, new File(directory, version + "/playerglobal.swc"));
      }
    }
  }

  private void publishSource(String artifactId) throws MojoExecutionException, IOException, ArchiverException, ArtifactInstallationException {
    final String path;
    if (artifactId.equals("applicationupdater")) {
      path = "air/ApplicationUpdater/src/ApplicationUpdater";
    }
    else if (artifactId.equals("servicemonitor")) {
      path = "air/Core/src";
    }
    else {
      path = artifactId + "/src";
    }

    File source = new File(sources, path);
    if (!source.isDirectory()) {
      return;
    }

    zipArchiver.reset();
    zipArchiver.setIncludeEmptyDirs(false);
    zipArchiver.addDirectory(source);
    zipArchiver.setDestFile(tempFile);
    zipArchiver.createArchive();
    publish(createArtifact(artifactId, "jar", "sources"), tempFile);
  }

  private Artifact createArtifact(String artifactId, String type, String classifier) {
    return artifactFactory.createArtifactWithClassifier(FLEX_GROUP_ID, artifactId, artifactId.equals("airglobal") ? airVersion : version, type, classifier);
  }

  private Artifact createArtifact(String artifactId, String type) {
    //noinspection NullableProblems
    return createArtifact(artifactId, type, null);
  }
  
  protected void publish(Artifact artifact, File file) throws ArtifactInstallationException, MojoExecutionException {
    installer.install(file, artifact, localRepository);
  }

  private String filenameToArtifactId(String filename) {
    return filename.substring(0, filename.length() - SWC_EXTENSION.length());
  }
}
