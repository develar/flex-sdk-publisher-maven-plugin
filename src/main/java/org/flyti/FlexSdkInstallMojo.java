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

import static org.flyti.FlexSdkInstallMojo.Classifier.*;
import static org.flyti.FlexSdkInstallMojo.Type.*;

/**
 * @goal install
 * @requiresProject false
 */
@Component(role=FlexSdkInstallMojo.class)
public class FlexSdkInstallMojo extends AbstractMojo {
  private static final String FRAMEWORK_GROUP_ID = "com.adobe.flex.framework";
  private static final String COMPILER_GROUP_ID = "com.adobe.flex.compiler";

  private static final String SWC_TYPE = "swc";
  private static final String RB_TYPE = "rb.swc";
  private static final String SWC_EXTENSION = ".swc";
  private static final String JAR_EXTENSION = ".jar";

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

  private static final Set<String> EXLUDED_COMPILER_JARS = new HashSet<String>();
  private static final String[] FLEX_COMPILER_OEM_SOURCE_INCLUDES = new String[]{"flex2/tools/oem/**/*", "flex2/tools/flexbuilder/**/*", "flex/license/*"};
  private static final String[] MXMLC_SOURCE_INCLUDES = new String[]{"**/*.properties", "flex2/compiler/**/*", "flex2/license/**/*", "flex2/linker/**/*", "flex2/tools/*", "flash/**/*", "flex/**/*"};

  static {
    @SuppressWarnings({"MismatchedQueryAndUpdateOfCollection"})
    Set<String> set = EXLUDED_COMPILER_JARS;
    set.add("smali.jar");
    set.add("baksmali.jar");
    set.add("compc.jar");
    set.add("javacc.jar");
    set.add("license.jar");
    set.add("flexTasks.jar");
    set.add("copylocale.jar");
    set.add("swfdump.jar");
  }

  private final List<Artifact> smallFlex = new ArrayList<Artifact>();
  private final List<Artifact> smallAir = new ArrayList<Artifact>();

  private final List<Artifact> compiler = new ArrayList<Artifact>();

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
  @SuppressWarnings({"UnusedDeclaration"}) private File home;

  /**
   * File location where targeted Flex SDK is located
   * @parameter expression="${compilerHome}"
   * @required
   */
  @SuppressWarnings({"UnusedDeclaration"}) private File compilerHome;

  /**
   * @parameter expression="${version}"
   * @required
   */
  @SuppressWarnings({"UnusedDeclaration"}) private String version;

  /**
   * @parameter expression="${airVersion}"
   * @required
   */
  @SuppressWarnings({"UnusedDeclaration"}) private String airVersion;

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
  @SuppressWarnings({"UnusedDeclaration"}) private boolean skipRsls;

  private File frameworkSources;
  private File frameworks;
  private File tempFile;

  public void execute() throws MojoExecutionException, MojoFailureException {
    frameworks = new File(home, "frameworks");
    frameworkSources = new File(frameworks, "projects");

    try {
      tempFile = File.createTempFile("tmp", null);
      tempFile.deleteOnExit();
      
      processLibraryDirectory(new File(frameworks, "libs"));
      processLocaleDirectory();

      processConfigs();
      processThemes();
      
      if (!skipRsls) {
        processRsls();
      }

      processCompiler();

      createAggregators();
    }
    catch (Exception e) {
      throw new MojoFailureException("Cannot publish", e);
    }
  }

  private void processCompiler() throws MojoExecutionException, ArtifactInstallationException, IOException, ArchiverException {
    File jars = new File(compilerHome, "lib");
    for (String name : jars.list()) {
      if (name.charAt(0) != '.' && name.endsWith(JAR_EXTENSION) && !EXLUDED_COMPILER_JARS.contains(name) && !isLocaleJar(name)) {
        final String artifactId = filenameToArtifactId(name);
        File file = new File(jars, name);
        Artifact artifact = createArtifact(COMPILER_GROUP_ID, artifactId, jar);
        compiler.add(artifact);
        publish(artifact, file);

        publishJarSource(artifactId);
      }
    }
  }

  private static boolean isLocaleJar(String name) {
    return name.startsWith("mxmlc_") || name.startsWith("batik_") || name.startsWith("xercesImpl_");
  }

  private void createAggregators() throws IOException, MojoExecutionException, ArtifactInstallationException {
    createAggregator(FRAMEWORK_GROUP_ID, "flex-framework-small", smallFlex);
    createAggregator(FRAMEWORK_GROUP_ID, "air-framework-small", smallAir);

    createAggregator("com.adobe.flex", "compiler", compiler);
  }

  private void createAggregator(final String groupId, final String artifactId, final List<Artifact> artifacts) throws IOException, MojoExecutionException, ArtifactInstallationException {
    Model model = new Model();
    model.setModelVersion("4.0.0");
    model.setGroupId(groupId);
    model.setArtifactId(artifactId);
    model.setVersion(version);
    model.setPackaging("pom");

    for (Artifact artifact : artifacts) {
      Dependency dependency = new Dependency();
      dependency.setGroupId(artifact.getGroupId());
      dependency.setArtifactId(artifact.getArtifactId());
      dependency.setClassifier(artifact.getClassifier());
      dependency.setType(artifact.getType());
      dependency.setVersion(artifact.getVersion());
      model.addDependency(dependency);
    }

    FileWriter writer = new FileWriter(tempFile);
    try {
      new MavenXpp3Writer().write(writer, model);
    }
    finally {
      writer.close();
    }

    publish(createArtifact(groupId, artifactId, pom), tempFile);
  }

  private void processRsls() {
    // todo
  }

  private void processThemes() throws MojoExecutionException, ArtifactInstallationException {
    publish(createArtifact(FRAMEWORK_GROUP_ID, "spark", css, theme), new File(frameworks, "themes/Spark/spark.css"));
  }

  private void processConfigs() throws ArchiverException, MojoExecutionException, ArtifactInstallationException, IOException {
    zipArchiver.reset();
    zipArchiver.setIncludeEmptyDirs(false);
    zipArchiver.addDirectory(frameworks, CONFIGS_INCLUDES, CONFIGS_EXCLUDES);
    zipArchiver.setDestFile(tempFile);
    zipArchiver.createArchive();
    Artifact artifact = createArtifact(FRAMEWORK_GROUP_ID, "framework", zip, configs);
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
              publish(artifactFactory.createArtifactWithClassifier(FRAMEWORK_GROUP_ID, artifactId, version, RB_TYPE, locale), file);
              if (isEnUS) {
                Artifact artifact = artifactFactory.createArtifactWithClassifier(FRAMEWORK_GROUP_ID, artifactId, version, RB_TYPE, null);
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
            Artifact artifact = createArtifact(FRAMEWORK_GROUP_ID, artifactId, swc);
            if (SMALL_SDK_ARTIFACTS_IDS.contains(artifactId)) {
              smallAir.add(artifact);
              if (!artifactId.contains("air")) {
                smallFlex.add(artifact);
              }
            }

            publish(artifact, file);
            publishSwcSource(artifactId);
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
        Artifact artifact = artifactFactory.createArtifactWithClassifier(FRAMEWORK_GROUP_ID, "playerglobal", version, SWC_TYPE, null);
        if (added) {
          throw new IllegalArgumentException("sort playerglobal version");
        }
        added = true;
        smallFlex.add(artifact);

        publish(artifact, new File(directory, version + "/playerglobal.swc"));
      }
    }
  }

  private void publishSwcSource(String artifactId) throws MojoExecutionException, IOException, ArchiverException, ArtifactInstallationException {
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

    File source = new File(frameworkSources, path);
    if (!source.isDirectory()) {
      return;
    }

    zipArchiver.reset();
    zipArchiver.setIncludeEmptyDirs(false);
    zipArchiver.addDirectory(source);
    zipArchiver.setDestFile(tempFile);
    zipArchiver.createArchive();
    publish(createArtifact(FRAMEWORK_GROUP_ID, artifactId, jar, sources), tempFile);
  }

  private void publishJarSource(String artifactId) throws MojoExecutionException, IOException, ArchiverException, ArtifactInstallationException {
    String[] includes = null;
    String[] excludes = null;

    final String path;
    if (artifactId.equals("asc")) {
      path = "modules/asc/src/java";
    }
    else if (artifactId.equals("batik-all-flex")) {
      path = "modules/thirdparty/batik/sources";
    }
    else if (artifactId.equals("flex-compiler-oem")) {
      path = "modules/compiler/src/java";
      includes = FLEX_COMPILER_OEM_SOURCE_INCLUDES;
    }
    else if (artifactId.equals("mxmlc")) {
      path = "modules/compiler/src/java";
      includes = MXMLC_SOURCE_INCLUDES;
    }
    else {
      return;
    }

    File source = new File(compilerHome, path);
    if (!source.isDirectory()) {
      return;
    }

    zipArchiver.reset();
    zipArchiver.setIncludeEmptyDirs(false);
    zipArchiver.addDirectory(source, includes, excludes);
    zipArchiver.setDestFile(tempFile);
    zipArchiver.createArchive();
    publish(createArtifact(COMPILER_GROUP_ID, artifactId, jar, sources), tempFile);
  }

  private Artifact createArtifact(String groupId, String artifactId, Type type, Classifier classifier) {
    return artifactFactory.createArtifactWithClassifier(groupId, artifactId, artifactId.equals("airglobal") || artifactId.equals("adt") ? airVersion : version, type.name(), classifier == null ? null : classifier.name());
  }

  private Artifact createArtifact(String groupId, String artifactId, Type type) {
    //noinspection NullableProblems
    return createArtifact(groupId, artifactId, type, null);
  }
  
  protected void publish(Artifact artifact, File file) throws ArtifactInstallationException, MojoExecutionException {
    installer.install(file, artifact, localRepository);
  }

  private String filenameToArtifactId(String filename) {
    return filename.substring(0, filename.length() - 4);
  }

  enum Classifier {
    theme, configs, sources
  }

  enum Type {
    swc, jar, pom, css, zip
  }
}