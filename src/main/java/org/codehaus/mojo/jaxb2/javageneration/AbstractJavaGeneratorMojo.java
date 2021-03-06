package org.codehaus.mojo.jaxb2.javageneration;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import com.sun.tools.xjc.Driver;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.settings.Proxy;
import org.apache.maven.settings.Settings;
import org.codehaus.mojo.jaxb2.AbstractJaxbMojo;
import org.codehaus.mojo.jaxb2.NoSchemasException;
import org.codehaus.mojo.jaxb2.shared.FileSystemUtilities;
import org.codehaus.mojo.jaxb2.shared.arguments.ArgumentBuilder;
import org.codehaus.mojo.jaxb2.shared.environment.ToolExecutionEnvironment;
import org.codehaus.mojo.jaxb2.shared.environment.classloading.ThreadContextClassLoaderBuilder;
import org.codehaus.mojo.jaxb2.shared.environment.logging.LoggingHandlerEnvironmentFacet;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.IOUtil;

import java.io.File;
import java.io.FileWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>Abstract superclass for Mojos generating Java source or binaries from XML schema(s) by invoking the JAXB XJC
 * binding compiler. Most of the Configuration options for the AbstractJavaGeneratorMojo are set or copied to the
 * XJC directly; refer to their documentation in the <a href="https://jaxb.java.net/">JAXB Reference Implementation</a>
 * site.</p>
 *
 * @author <a href="mailto:lj@jguru.se">Lennart J&ouml;relid</a>
 * @see <a href="https://jaxb.java.net/">The JAXB Reference Implementation</a>
 */
public abstract class AbstractJavaGeneratorMojo extends AbstractJaxbMojo {

    private static final int XJC_COMPLETED_OK = 0;

    /**
     * <p>Corresponding XJC parameter: {@code catalog}.</p>
     * <p>Specify catalog files to resolve external entity references.
     * Supports TR9401, XCatalog, and OASIS XML Catalog format.</p>
     */
    @Parameter
    protected File catalog;

    /**
     * <p>Corresponding XJC parameter: {@code episode}.</p>
     * <p>Generate an episode file from this compilation, so that other schemas that rely on this schema can be
     * compiled later and rely on classes that are generated from this compilation. The generated episode file is
     * really just a JAXB customization file (but with vendor extensions.)</p>
     * <p>If this parameter is {@code true}, the episode file generated is called {@code META-INF/sun-jaxb.episode},
     * and included in the artifact.</p>
     *
     * @see #STANDARD_EPISODE_FILENAME
     * @since 2.0
     */
    @Parameter(defaultValue = "true")
    protected boolean generateEpisode;

    /**
     * <p>Sets the HTTP/HTTPS proxy to be used by the XJC, on the format
     * {@code [user[:password]@]proxyHost[:proxyPort]}.
     * All information is retrieved from the active proxy within the standard maven settings file.</p>
     */
    @Parameter(defaultValue = "${settings}", readonly = true)
    protected Settings settings;

    /**
     * <p>Defines the content type of sources for the XJC. To simplify usage of the JAXB2 maven plugin,
     * all source files are assumed to have the same type of content.</p>
     * <p>This parameter replaces the previous multiple-choice boolean configuration options for the
     * jaxb2-maven-plugin (i.e. dtd, xmlschema, relaxng, relaxng-compact, wsdl), and
     * corresponds to setting one of those flags as an XJC argument.</p>
     *
     * @since 2.0
     */
    @Parameter(defaultValue = "XmlSchema")
    protected SourceContentType sourceType;

    /**
     * <p>Corresponding XJC parameter: {@code npa}.</p>
     * <p>Suppress the generation of package level annotations into {@code package-info.java}.
     * Using this switch causes the generated code to internalize those annotations into the other
     * generated classes.</p>
     *
     * @since 2.0
     */
    @Parameter(defaultValue = "false")
    protected boolean noPackageLevelAnnotations;

    /**
     * <p>Corresponding XJC parameter: {@code no-header}.</p>
     * <p>Suppress the generation of a file header comment that includes some note and timestamp.
     * Using this makes the generated code more diff-friendly.</p>
     *
     * @since 2.0
     */
    @Parameter(defaultValue = "false")
    protected boolean noGeneratedHeaderComments;

    /**
     * <p>Corresponding XJC parameter: {@code mark-generated}.</p>
     * <p>This feature causes all of the generated code to have {@code @Generated} annotation.</p>
     *
     * @since 2.0
     */
    @Parameter(defaultValue = "false")
    protected boolean addGeneratedAnnotation;

    /**
     * <p>Corresponding XJC parameter: {@code nv}.</p>
     * <p>By default, the XJC binding compiler performs strict validation of the source schema before processing it.
     * Use this option to disable strict schema validation. This does not mean that the binding compiler will not
     * perform any validation, it simply means that it will perform less-strict validation.</p>
     *
     * @since 2.0
     */
    @Parameter(defaultValue = "false")
    protected boolean laxSchemaValidation;

    /**
     * <p>Corresponding XJC parameter: {@code quiet}.</p>
     * <p>Suppress compiler output, such as progress information and warnings.</p>
     */
    @Parameter(defaultValue = "false")
    protected boolean quiet;

    /**
     * <p>Corresponding XJC parameter: {@code verbose}.</p>
     * <p>Tells XJC to be extra verbose, such as printing informational messages or displaying stack traces.</p>
     */
    @Parameter(property = "xjc.verbose", defaultValue = "false")
    protected boolean verbose;

    /**
     * <p>Corresponding XJC parameter: {@code extension}.</p>
     * <p>By default, the XJC binding compiler strictly enforces the rules outlined in the Compatibility chapter of
     * the JAXB Specification. Appendix E.2 defines a set of W3C XML Schema features that are not completely
     * supported by JAXB v1.0. In some cases, you may be allowed to use them in the "-extension" mode enabled by
     * this switch. In the default (strict) mode, you are also limited to using only the binding customizations
     * defined in the specification.</p>
     */
    @Parameter(defaultValue = "false")
    protected boolean extension;

    /**
     * Fails the Mojo execution if no XSDs/schemas are found.
     *
     * @since 1.3
     */
    @Parameter(defaultValue = "true")
    protected boolean failOnNoSchemas;

    /**
     * <p>Removes all files from the output directory before running XJC.</p>
     */
    @Parameter(defaultValue = "true")
    protected boolean clearOutputDir;

    /**
     * <p>Corresponding XJC parameter: {@code readOnly}.</p>
     * <p>By default, the XJC binding compiler does not write-protect the Java source files it generates.
     * Use this option to force the XJC binding compiler to mark the generated Java sources read-only.</p>
     *
     * @since 2.0
     */
    @Parameter(defaultValue = "false")
    protected boolean readOnly;

    /**
     * <p>List of ordered extra arguments to the XJC command. Each extra argument is interpreted as a word, intended
     * to be copied verbatim to the XJC argument list with spaces in between:</p>
     * <pre>
     * <code>
     *   &lt;configuration&gt;
     *   ...
     *       &lt;arguments&gt;
     *          &lt;argument&gt;-Xfluent-api&lt;/argument&gt;
     *          &lt;argument&gt;somefile&lt;/argument&gt;
     *      &lt;/arguments&gt;
     *   &lt;/configuration&gt;
     * </code>
     * </pre>
     * <p>The arguments configured above yields the following extra arguments to the XJC command:
     * <code>-Xfluent-api -episode somefile</code></p>
     *
     * @since 2.0
     * @deprecated This should be removed in the 2.0+ release, as all arguments should be handled by other parameters.
     */
    @Parameter(property = "xjc.arguments")
    protected List<String> arguments;

    /**
     * <p>Corresponding XJC parameter: {@code enableIntrospection}.</p>
     * <p>Enable correct generation of Boolean getters/setters to enable Bean Introspection APIs.</p>
     *
     * @since 1.4
     */
    @Parameter(defaultValue = "false")
    private boolean enableIntrospection;

    /**
     * <p>Corresponding XJC parameter: {@code p}.</p>
     * <p>The package under which the source files will be generated. Quoting the XJC documentation:
     * "Specifying a target package via this command-line option overrides any binding customization for package
     * name and the default package name algorithm defined in the specification".</p>
     */
    @Parameter
    protected String packageName;

    /**
     * <p>Corresponding XJC parameter: {@code target}.</p>
     * <p>Permitted values: {@code "2.0"} and {@code "2.1"}. Avoid generating code that relies on JAXB newer than the
     * version given. This will allow the generated code to run with JAXB 2.0 runtime (such as JavaSE 6.)</p>
     *
     * @since 1.3
     */
    @Parameter
    protected String target;

    /**
     * <p>If provided, this parameter indicates that the XSDs used by XJC to generate Java code should be
     * copied into the resulting artifact of this project (the JAR, WAR or whichever artifact type is generated).
     * The value of the {@code xsdPathWithinArtifact} parameter is the relative path within the artifact where
     * all source XSDs are copied to (hence the name "XSD Path Within Artifact").</p>
     * <p>The target directory is created within the artifact if it does not already exist.
     * If the {@code xsdPathWithinArtifact} parameter is not given, the XSDs used to generate Java code are
     * <em>not</em> included within the project's artifact.</p>
     * <p><em>Example:</em>Adding the sample configuration below would copy all source XSDs to the given directory
     * within the resulting JAR (and/or test-JAR). If the directory {@code META-INF/jaxb/xsd} does not exist, it
     * will be created.</p>
     * <pre>
     *     <code>
     *         &lt;configuration&gt;
     *             ...
     *             &lt;xsdPathWithinArtifact&gt;META-INF/jaxb/xsd&lt;/xsdPathWithinArtifact&gt;
     *         &lt;/configuration&gt;
     *     </code>
     * </pre>
     * <p><strong>Note</strong>: This parameter was previously called {@code includeSchemasOutputPath}
     * in the 1.x versions of this plugin, but was renamed and re-documented for improved usability and clarity.</p>
     *
     * @since 2.0
     */
    @Parameter
    protected String xsdPathWithinArtifact;

    /**
     * <p>Java generation is required if any of the file products is outdated/stale.</p>
     * {@inheritDoc}
     */
    @Override
    protected boolean isReGenerationRequired() {

        //
        // Use the stale flag method to identify if we should re-generate the java source code from the supplied
        // Xml Schema. Basically, we should regenerate the JAXB code if:
        //
        // a) The staleFile does not exist
        // b) The staleFile exists and is older than one of the sources (XSD or XJB files).
        //    "Older" is determined by comparing the modification timestamp of the staleFile and the source files.
        //
        final File staleFile = getStaleFile();
        final String debugPrefix = "StaleFile [" + FileSystemUtilities.getCanonicalPath(staleFile) + "]";

        boolean stale = !staleFile.exists();
        if (stale) {
            getLog().debug(debugPrefix + " not found. JAXB (re-)generation required.");
        } else {

            final List<URL> sourceXSDs = getSources();
            final List<File> sourceXJBs = getSourceXJBs();

            if (getLog().isDebugEnabled()) {
                getLog().debug(debugPrefix + " found. Checking timestamps on source XSD and XJB "
                        + "files to determine if JAXB (re-)generation is required.");
            }

            final long staleFileLastModified = staleFile.lastModified();
            for (URL current : sourceXSDs) {

                final URLConnection sourceXsdConnection;
                try {
                    sourceXsdConnection = current.openConnection();
                    sourceXsdConnection.connect();
                } catch (Exception e) {

                    // Can't determine if the staleFile is younger than this sourceXSD.
                    // Re-generate to be on the safe side.
                    stale = true;
                    break;
                }

                try {
                    if (sourceXsdConnection.getLastModified() > staleFileLastModified) {

                        if (getLog().isDebugEnabled()) {
                            getLog().debug(current.toString() + " is newer than the stale flag file.");
                        }
                        stale = true;
                    }
                } finally {
                    if (sourceXsdConnection instanceof HttpURLConnection) {
                        ((HttpURLConnection) sourceXsdConnection).disconnect();
                    }
                }
            }

            for (File current : sourceXJBs) {
                if (current.lastModified() > staleFileLastModified) {

                    if (getLog().isDebugEnabled()) {
                        getLog().debug(FileSystemUtilities.getCanonicalPath(current)
                                + " is newer than the stale flag file.");
                    }

                    stale = true;
                    break;
                }
            }
        }

        // All done.
        return stale;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean performExecution() throws MojoExecutionException, MojoFailureException {

        boolean updateStaleFileTimestamp = false;

        try {

            // Setup the Tool's execution environment
            ToolExecutionEnvironment environment = null;
            try {

                // Create the ToolExecutionEnvironment
                environment = new ToolExecutionEnvironment(getLog(),
                        ThreadContextClassLoaderBuilder.createFor(this.getClass(), getLog()).addPaths(getClasspath()),
                        LoggingHandlerEnvironmentFacet.create(getLog(), getClass(), getEncoding(false)));
                environment.setup();

                // Compile the XJC arguments
                final String[] xjcArguments = getXjcArguments(
                        environment.getClassPathAsArgument(),
                        STANDARD_EPISODE_FILENAME);

                // Ensure that the outputDirectory exists, but only clear it if does not already
                FileSystemUtilities.createDirectory(getOutputDirectory(), clearOutputDir);

                // Do we need to re-create the episode file's parent directory.
                final boolean reCreateEpisodeFileParentDirectory = generateEpisode && clearOutputDir;
                if (reCreateEpisodeFileParentDirectory) {
                    getEpisodeFile(STANDARD_EPISODE_FILENAME);
                }

                // Fire XJC
                if (XJC_COMPLETED_OK != Driver.run(xjcArguments, new XjcLogAdapter(getLog()))) {

                    final StringBuilder errorMsgBuilder = new StringBuilder();
                    errorMsgBuilder.append("\n+=================== [XJC Error]\n");
                    errorMsgBuilder.append("|\n");

                    final List<URL> sourceXSDs = getSources();
                    for (int i = 0; i < sourceXSDs.size(); i++) {
                        errorMsgBuilder.append("| " + i + ": ").append(sourceXSDs.get(i).toString()).append("\n");
                    }

                    errorMsgBuilder.append("|\n");
                    errorMsgBuilder.append("+=================== [End XJC Error]\n");
                    throw new MojoExecutionException(errorMsgBuilder.toString());
                }

                // Indicate that the output directory was updated.
                getBuildContext().refresh(getOutputDirectory());

                // Update the modification timestamp of the staleFile.
                updateStaleFileTimestamp = true;

            } finally {

                if (environment != null) {
                    environment.restore();
                }
            }

            // Add the generated source root to the project, enabling tooling and other plugins to see them.
            addGeneratedSourcesToProjectSourceRoot();

            // Copy all source XSDs to the resulting artifact?
            if (xsdPathWithinArtifact != null) {

                final String buildOutputDirectory = getProject().getBuild().getOutputDirectory();
                final File targetXsdDirectory = new File(buildOutputDirectory, xsdPathWithinArtifact);
                FileUtils.forceMkdir(targetXsdDirectory);

                for (URL current : getSources()) {

                    String fileName = null;
                    if ("file".equalsIgnoreCase(current.getProtocol())) {
                        fileName = new File(current.getPath()).getName();
                    } else if ("jar".equalsIgnoreCase(current.getProtocol())) {

                        // Typical JAR path
                        // jar:file:/path/to/aJar.jar!/some/path/xsd/aResource.xsd
                        final int bangIndex = current.toString().indexOf("!");
                        if (bangIndex == -1) {
                            throw new MojoExecutionException("Illegal JAR URL [" + current.toString()
                                    + "]: lacks a '!'");
                        }

                        final String internalPath = current.toString().substring(bangIndex + 1);
                        fileName = new File(internalPath).getName();
                    } else {
                        throw new MojoExecutionException("Could not extract FileName from URL [" + current + "]");
                    }

                    final File targetFile = new File(targetXsdDirectory, fileName);
                    if (targetFile.exists()) {

                        // TODO: Should we throw an exception here instead?
                        getLog().warn("File [" + FileSystemUtilities.getCanonicalPath(targetFile)
                                + "] already exists. Not copying XSD file [" + current.getPath() + "] to it.");
                    }
                    IOUtil.copy(current.openStream(), new FileWriter(targetFile));
                }

                // Refresh the BuildContext
                getBuildContext().refresh(targetXsdDirectory);
            }
        } catch (MojoExecutionException e) {
            throw e;
        } catch (NoSchemasException e) {
            if (failOnNoSchemas) {
                throw new MojoExecutionException("", e);
            }
        } catch (Exception e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }

        // All done.
        return updateStaleFileTimestamp;
    }

    /**
     * Override this method to acquire a List holding all URLs to the JAXB sources for which this
     * AbstractJavaGeneratorMojo should generate Java files. Sources are assumed to be in the form given by
     * the {@code sourceType} value.
     *
     * @return A non-null List holding URLs to sources for the XJC generation.
     * @see #sourceType
     */
    @Override
    protected abstract List<URL> getSources();

    /**
     * Override this method to retrieve a list of Files to all XJB files for which this
     * AbstractJavaGeneratorMojo should generate Java files.
     *
     * @return A non-null List holding binding files.
     */
    protected abstract List<File> getSourceXJBs();

    /**
     * Adds any directories containing the generated XJC classes to the appropriate Project compilation sources;
     * either {@code TestCompileSourceRoot} or {@code CompileSourceRoot} depending on the exact Mojo implementation
     * of this AbstractJavaGeneratorMojo.
     */
    protected abstract void addGeneratedSourcesToProjectSourceRoot();

    /**
     * Adds the supplied Resource to the project using the appropriate scope (i.e. resource or testResource)
     * depending on the exact implementation of this AbstractJavaGeneratorMojo.
     *
     * @param resource The resource to add.
     */
    protected abstract void addResource(final Resource resource);

    //
    // Private helpers
    //

    private void addResourceDirectory(final File directoryOrNull) {

        if (directoryOrNull != null) {

            // Wrap the given directory in a Resource
            final Resource toAdd = new Resource();
            toAdd.setDirectory(directoryOrNull.getAbsolutePath());

            // Add the resource to the appropriate location.
            addResource(toAdd);

            // Refresh the build context
            getBuildContext().refresh(directoryOrNull);
        }
    }

    private String[] getXjcArguments(final String classPath, final String episodeFileNameOrNull)
            throws MojoExecutionException, NoSchemasException {

        final ArgumentBuilder builder = new ArgumentBuilder();

        // Add all flags on the form '-flagName'
        builder.withFlag(true, sourceType.getXjcArgument());
        builder.withFlag(noPackageLevelAnnotations, "npa");
        builder.withFlag(laxSchemaValidation, "nv");
        builder.withFlag(verbose, "verbose");
        builder.withFlag(quiet, "quiet");
        builder.withFlag(enableIntrospection, "enableIntrospection");
        builder.withFlag(extension, "extension");
        builder.withFlag(readOnly, "readOnly");
        builder.withFlag(noGeneratedHeaderComments, "no-header");
        builder.withFlag(addGeneratedAnnotation, "mark-generated");

        // Add all arguments on the form '-argumentName argumentValue'
        // (i.e. in 2 separate elements of the returned String[])
        builder.withNamedArgument("httpproxy", getProxyString(settings.getActiveProxy()));
        builder.withNamedArgument("encoding", getEncoding(true));
        builder.withNamedArgument("p", packageName);
        builder.withNamedArgument("target", target);
        builder.withNamedArgument("d", getOutputDirectory().getAbsolutePath());
        builder.withNamedArgument("classpath", classPath);

        if (generateEpisode) {

            // We must use the -extension flag for the episode to work.
            if (!extension) {

                if (getLog().isInfoEnabled()) {
                    getLog().info("Adding 'extension' flag to XJC arguments, since the 'generateEpisode' argument is "
                            + "given. (XJCs 'episode' argument requires that the 'extension' argument is provided).");
                }
                builder.withFlag(true, "extension");
            }

            final File episodeFile = getEpisodeFile(episodeFileNameOrNull);
            builder.withNamedArgument("episode", FileSystemUtilities.getCanonicalPath(episodeFile));
        }
        if (catalog != null) {
            builder.withNamedArgument("catalog", FileSystemUtilities.getCanonicalPath(catalog));
        }

        if (arguments != null) {
            builder.withPreCompiledArguments(arguments);
        }

        for (File current : getSourceXJBs()) {

            // Shorten the argument if possible.
            final String strippedXjbPath = FileSystemUtilities.relativize(
                    current.getAbsolutePath(), getProject().getBasedir());

            // Each XJB must be given as a separate argument.
            builder.withNamedArgument("-b", strippedXjbPath);
        }

        final List<URL> sourceXSDs = getSources();
        if (sourceXSDs.isEmpty()) {

            // If we have no XSDs, we are not going to be able to run XJC.
            getLog().warn("No XSD files found. Please check your plugin configuration.");
            throw new NoSchemasException();

        } else {

            final List<String> unwrappedSourceXSDs = new ArrayList<String>();
            for (URL current : sourceXSDs) {

                // Shorten the argument if possible.
                if ("file".equalsIgnoreCase(current.getProtocol())) {
                    unwrappedSourceXSDs.add(FileSystemUtilities.relativize(
                            current.getPath(),
                            getProject().getBasedir()));
                } else {
                    unwrappedSourceXSDs.add(current.toString());
                }
            }

            builder.withPreCompiledArguments(unwrappedSourceXSDs);
        }

        // All done.
        return logAndReturnToolArguments(builder.build(), "XJC");
    }

    private String getProxyString(final Proxy activeProxy) {

        // Check sanity
        if (activeProxy == null) {
            return null;
        }

        // The XJC proxy argument should be on the form
        // [user[:password]@]proxyHost[:proxyPort]
        //
        // builder.withNamedArgument("httpproxy", httpproxy);
        //
        final StringBuilder proxyBuilder = new StringBuilder();
        if (activeProxy.getUsername() != null) {

            // Start with the username.
            proxyBuilder.append(activeProxy.getUsername());

            // Append the password if provided.
            if (activeProxy.getPassword() != null) {
                proxyBuilder.append(":").append(activeProxy.getPassword());
            }

            proxyBuilder.append("@");
        }

        // Append hostname and port.
        proxyBuilder.append(activeProxy.getHost()).append(":").append(activeProxy.getPort());

        // All done.
        return proxyBuilder.toString();
    }
}
