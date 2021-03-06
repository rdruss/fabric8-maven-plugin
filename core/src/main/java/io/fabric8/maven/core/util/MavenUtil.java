/**
 * Copyright 2016 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package io.fabric8.maven.core.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

import com.google.common.base.Objects;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.archiver.tar.TarArchiver;
import org.codehaus.plexus.archiver.tar.TarLongFileMode;
import org.codehaus.plexus.archiver.zip.ZipArchiver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author roland
 * @since 31/03/16
 */
public class MavenUtil {
    private static final transient Logger LOG = LoggerFactory.getLogger(MavenUtil.class);

    private static final String DEFAULT_CONFIG_FILE_NAME = "kubernetes.json";

    public static boolean isKubernetesJsonArtifact(String classifier, String type) {
        return "json".equals(type) && "kubernetes".equals(classifier);
    }

    public static boolean hasKubernetesJson(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f); JarInputStream jis = new JarInputStream(fis)) {
            for (JarEntry entry = jis.getNextJarEntry(); entry != null; entry = jis.getNextJarEntry()) {
                if (entry.getName().equals(DEFAULT_CONFIG_FILE_NAME)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static URLClassLoader getCompileClassLoader(MavenProject project) {
        try {
            List<String> classpathElements = project.getCompileClasspathElements();
            return createClassLoader(classpathElements, project.getBuild().getOutputDirectory());
        } catch (DependencyResolutionRequiredException e) {
            throw new IllegalArgumentException("Cannot resolve artifact from compile classpath",e);
        }
    }

    public static URLClassLoader getTestClassLoader(MavenProject project) {
        try {
            List<String> classpathElements = project.getTestClasspathElements();
            return createClassLoader(classpathElements, project.getBuild().getTestOutputDirectory());
        } catch (DependencyResolutionRequiredException e) {
            throw new IllegalArgumentException("Cannot resolve artifact from test classpath", e);
        }
    }

    public static String createDefaultResourceName(String artifactId, String ... suffixes) {
        String suffix = StringUtils.join(suffixes, "-");
        String ret = artifactId + (suffix.length() > 0 ? "-" + suffix : "");
        if (ret.length() > 63) {
            ret = ret.substring(0,63);
        }
        return ret.toLowerCase();
    }

    // ====================================================

    private static URLClassLoader createClassLoader(List<String> classpathElements, String... paths) {
        List<URL> urls = new ArrayList<>();
        for (String path : paths) {
            URL url = pathToUrl(path);
            urls.add(url);
        }
        for (Object object : classpathElements) {
            if (object != null) {
                String path = object.toString();
                URL url = pathToUrl(path);
                urls.add(url);
            }
        }
        return createURLClassLoader(urls);
    }

    private static URLClassLoader createURLClassLoader(Collection<URL> jars) {
        return new URLClassLoader(jars.toArray(new URL[jars.size()]));
    }

    private static URL pathToUrl(String path) {
        try {
            File file = new File(path);
            return file.toURI().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(String.format("Cannot convert %s to a an URL: %s",path,e.getMessage()),e);
        }
    }


    /**
     * Returns true if the maven project has a dependency with the given groupId and artifactId (if not null)
     */
    public static boolean hasDependency(MavenProject project, String groupId, String artifactId) {
        return getDependencyVersion(project, groupId, artifactId) != null;
    }

    /**
     * Returns the version associated to the dependency dependency with the given groupId and artifactId (if present)
     */
    public static String getDependencyVersion(MavenProject project, String groupId, String artifactId) {
        Set<Artifact> artifacts = project.getArtifacts();
        if (artifacts != null) {
            for (Artifact artifact : artifacts) {
                String scope = artifact.getScope();
                if (Objects.equal("test", scope)) {
                    continue;
                }
                if (artifactId != null && !Objects.equal(artifactId, artifact.getArtifactId())) {
                    continue;
                }
                if (Objects.equal(groupId, artifact.getGroupId())) {
                    return artifact.getVersion();
                }
            }
        }
        return null;
    }

    public static boolean hasPlugin(MavenProject project, String groupId, String artifactId) {
        return project.getPlugin(groupId + ":" + artifactId) != null;
    }

    public static boolean hasPluginOfAnyGroupId(MavenProject project, String pluginArtifact) {
        return getPluginOfAnyGroupId(project, pluginArtifact) != null;
    }

    public static Plugin getPluginOfAnyGroupId(MavenProject project, String pluginArtifact) {
        return getPlugin(project, null, pluginArtifact);
    }

    /**
     * Returns the plugin with the given groupId (if present) and artifactId.
     */
    public static Plugin getPlugin(MavenProject project, String groupId, String artifactId) {
        if (artifactId == null) {
            throw new IllegalArgumentException("artifactId cannot be null");
        }

        List<Plugin> plugins = project.getBuildPlugins();
        if (plugins != null) {
            for (Plugin plugin : plugins) {
                boolean matchesArtifactId = artifactId.equals(plugin.getArtifactId());
                boolean matchesGroupId = groupId == null || groupId.equals(plugin.getGroupId());

                if (matchesGroupId && matchesArtifactId) {
                    return plugin;
                }
            }
        }
        return null;
    }

    /**
     * Returns true if any of the given resources could be found on the given class loader
     */
    public static boolean hasResource(MavenProject project, String... paths) {
        URLClassLoader compileClassLoader = getCompileClassLoader(project);
        for (String path : paths) {
            try {
                if (compileClassLoader.getResource(path) != null) {
                    return true;
                }
            } catch (Throwable e) {
                // ignore
            }
        }
        return false;
    }

    public static void createArchive(File sourceDir, File destinationFile, TarArchiver archiver) throws MojoExecutionException {
        try {
            archiver.setCompression(TarArchiver.TarCompressionMethod.gzip);
            archiver.setLongfile(TarLongFileMode.posix);
            archiver.addDirectory(sourceDir);
            archiver.setDestFile(destinationFile);
            archiver.createArchive();
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to create archive " + destinationFile + ": " + e, e);
        }
    }


    public static void createArchive(File sourceDir, File destinationFile, ZipArchiver archiver) throws MojoExecutionException {
        try {
            archiver.addDirectory(sourceDir);
            archiver.setDestFile(destinationFile);
            archiver.createArchive();
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to create archive " + destinationFile + ": " + e, e);
        }
    }

    /**
     * Returns the version from the list of pre-configured versions of common groupId/artifact pairs
     */
    public static String getVersion(String groupId, String artifactId) throws IOException {
        String path = "META-INF/maven/" + groupId + "/" + artifactId + "/pom.properties";
        InputStream in = MavenUtil.class.getClassLoader().getResourceAsStream(path);
        if (in == null) {
            throw new IOException("Could not find " + path + " on classath!");
        }
        Properties properties = new Properties();
        try {
            properties.load(in);
        } catch (IOException e) {
            throw new IOException("Failed to load " + path + ". " + e, e);
        }
        String version = properties.getProperty("version");
        if (StringUtils.isBlank(version)) {
            throw new IOException("No version property in " + path);

        }
        return version;
    }

    public static Optional<List<String>> getCompileClasspathElementsIfRequested(MavenProject project, boolean useProjectClasspath) throws MojoExecutionException {
        if (!useProjectClasspath) {
            return Optional.empty();
        }

        try {
            return Optional.of(project.getCompileClasspathElements());
        } catch (DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("Cannot extra compile class path elements", e);
        }
    }
}
