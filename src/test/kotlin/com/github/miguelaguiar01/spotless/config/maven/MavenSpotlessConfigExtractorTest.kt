package com.github.miguelaguiar01.spotless.config.maven

import com.github.miguelaguiar01.spotless.config.SpotlessConfigService
import com.github.miguelaguiar01.spotless.engine.ConfigLookup
import com.github.miguelaguiar01.spotless.engine.FallbackReason
import com.github.miguelaguiar01.spotless.protocol.SpotlessConfig
import com.intellij.maven.testFramework.MavenImportingTestCase
import kotlinx.coroutines.runBlocking

/** Imports fixture POMs with IntelliJ's real Maven importer and checks the extracted DTOs. */
class MavenSpotlessConfigExtractorTest : MavenImportingTestCase() {

    /** The suspend importer needs the EDT; run test bodies on a background thread like IntelliJ's own Maven tests. */
    override fun runInDispatchThread(): Boolean = false

    private fun found(lookup: ConfigLookup): SpotlessConfig {
        assertTrue("expected Found but got $lookup", lookup is ConfigLookup.Found)
        return (lookup as ConfigLookup.Found).config
    }

    private fun xmlOf(config: SpotlessConfig) = config.maven!!.configurationXml.replace(Regex("\\s+"), "")

    fun testSingleModuleWithPlaceholders() = runBlocking {
        createProjectSubFile("license-header.txt", "/* header */")
        importProjectAsync(
            """
            <groupId>test</groupId>
            <artifactId>app</artifactId>
            <version>1</version>
            <properties>
              <gjf.version>1.33.0</gjf.version>
            </properties>
            <build>
              <plugins>
                <plugin>
                  <groupId>com.diffplug.spotless</groupId>
                  <artifactId>spotless-maven-plugin</artifactId>
                  <version>3.11.0</version>
                  <configuration>
                    <java>
                      <googleJavaFormat><version>${'$'}{gjf.version}</version></googleJavaFormat>
                      <licenseHeader><file>${'$'}{project.basedir}/license-header.txt</file></licenseHeader>
                    </java>
                  </configuration>
                </plugin>
              </plugins>
            </build>
            """.trimIndent()
        )
        val mavenProject = projectsManager.findProject(projectPom)!!
        val config = found(MavenSpotlessConfigExtractor.extract(project, mavenProject))

        assertEquals(SpotlessConfig.Kind.MAVEN, config.kind)
        assertEquals("3.11.0", config.spotlessVersion)
        assertEquals(mavenProject.directory, config.projectDir)
        assertEquals(mavenProject.buildDirectory, config.buildDir)
        val xml = xmlOf(config)
        assertTrue(xml, xml.contains("<version>1.33.0</version>"))
        assertTrue(xml, xml.contains("<file>${mavenProject.directory}/license-header.txt</file>"))
        assertFalse(xml, xml.contains("\${"))
        assertEquals(listOf("${mavenProject.directory}/src/main/java"), config.maven!!.sourceRoots)
        assertTrue(config.maven!!.pluginRepositories.any { it.url.contains("repo.maven.apache.org") })
        assertNotNull(config.maven!!.localRepository)
        assertEquals(64, config.configId().length)
    }

    fun testParentConfigurationIsInheritedByChildModule() = runBlocking {
        createProjectPom(
            """
            <groupId>test</groupId>
            <artifactId>parent</artifactId>
            <version>1</version>
            <packaging>pom</packaging>
            <modules><module>child</module></modules>
            <build>
              <plugins>
                <plugin>
                  <groupId>com.diffplug.spotless</groupId>
                  <artifactId>spotless-maven-plugin</artifactId>
                  <version>2.44.5</version>
                  <configuration>
                    <encoding>ISO-8859-1</encoding>
                    <java><palantirJavaFormat/></java>
                  </configuration>
                </plugin>
              </plugins>
            </build>
            """.trimIndent()
        )
        val child = createModulePom(
            "child",
            """
            <parent><groupId>test</groupId><artifactId>parent</artifactId><version>1</version></parent>
            <artifactId>child</artifactId>
            """.trimIndent()
        )
        importProjectAsync()
        val childProject = projectsManager.findProject(child)!!
        val config = found(MavenSpotlessConfigExtractor.extract(project, childProject))
        assertEquals("2.44.5", config.spotlessVersion)
        assertEquals(childProject.directory, config.projectDir)
        val xml = xmlOf(config)
        assertTrue(xml, xml.contains("<encoding>ISO-8859-1</encoding>"))
        assertTrue(xml, xml.contains("<palantirJavaFormat/>"))

        // Parent and child produce different configs (different base dirs) but the same XML.
        val parentConfig = found(MavenSpotlessConfigExtractor.extract(project, projectsManager.findProject(projectPom)!!))
        assertEquals(xmlOf(parentConfig), xml)
        assertFalse(parentConfig.configId() == config.configId())
    }

    fun testPluginManagementConfigurationApplies() = runBlocking {
        importProjectAsync(
            """
            <groupId>test</groupId>
            <artifactId>app</artifactId>
            <version>1</version>
            <build>
              <pluginManagement>
                <plugins>
                  <plugin>
                    <groupId>com.diffplug.spotless</groupId>
                    <artifactId>spotless-maven-plugin</artifactId>
                    <version>3.10.4</version>
                    <configuration><kotlin><ktfmt/></kotlin></configuration>
                  </plugin>
                </plugins>
              </pluginManagement>
              <plugins>
                <plugin>
                  <groupId>com.diffplug.spotless</groupId>
                  <artifactId>spotless-maven-plugin</artifactId>
                </plugin>
              </plugins>
            </build>
            """.trimIndent()
        )
        val config = found(MavenSpotlessConfigExtractor.extract(project, projectsManager.findProject(projectPom)!!))
        assertEquals("3.10.4", config.spotlessVersion)
        assertTrue(xmlOf(config), xmlOf(config).contains("<kotlin><ktfmt/></kotlin>"))
    }

    fun testDefaultCliExecutionIsMergedButPhaseBoundExecutionsAreIgnored() = runBlocking {
        importProjectAsync(
            """
            <groupId>test</groupId>
            <artifactId>app</artifactId>
            <version>1</version>
            <build>
              <plugins>
                <plugin>
                  <groupId>com.diffplug.spotless</groupId>
                  <artifactId>spotless-maven-plugin</artifactId>
                  <version>3.11.0</version>
                  <configuration><encoding>UTF-8</encoding><java><googleJavaFormat/></java></configuration>
                  <executions>
                    <execution>
                      <id>default-cli</id>
                      <configuration><encoding>UTF-16</encoding></configuration>
                    </execution>
                    <execution>
                      <id>check-in-build</id>
                      <phase>verify</phase>
                      <goals><goal>check</goal></goals>
                      <configuration><pom><sortPom/></pom></configuration>
                    </execution>
                  </executions>
                </plugin>
              </plugins>
            </build>
            """.trimIndent()
        )
        val xml = xmlOf(found(MavenSpotlessConfigExtractor.extract(project, projectsManager.findProject(projectPom)!!)))
        assertTrue(xml, xml.contains("<encoding>UTF-16</encoding>"))
        assertTrue(xml, xml.contains("<googleJavaFormat/>"))
        assertFalse(xml, xml.contains("sortPom"))
    }

    fun testNoPluginAndUnsupportedVersion() = runBlocking {
        importProjectAsync(
            """
            <groupId>test</groupId>
            <artifactId>app</artifactId>
            <version>1</version>
            """.trimIndent()
        )
        val none = MavenSpotlessConfigExtractor.extract(project, projectsManager.findProject(projectPom)!!)
        assertEquals(FallbackReason.NO_SPOTLESS_PLUGIN_FOUND, (none as ConfigLookup.Unavailable).reason)

        importProjectAsync(
            """
            <groupId>test</groupId>
            <artifactId>app</artifactId>
            <version>1</version>
            <build><plugins><plugin>
              <groupId>com.diffplug.spotless</groupId>
              <artifactId>spotless-maven-plugin</artifactId>
              <version>2.30.0</version>
            </plugin></plugins></build>
            """.trimIndent()
        )
        val old = MavenSpotlessConfigExtractor.extract(project, projectsManager.findProject(projectPom)!!)
        assertEquals(FallbackReason.SPOTLESS_VERSION_UNSUPPORTED, (old as ConfigLookup.Unavailable).reason)
    }

    fun testServiceCachesAndRefreshesOnReimport() = runBlocking {
        importProjectAsync(
            """
            <groupId>test</groupId>
            <artifactId>app</artifactId>
            <version>1</version>
            <build><plugins><plugin>
              <groupId>com.diffplug.spotless</groupId>
              <artifactId>spotless-maven-plugin</artifactId>
              <version>3.11.0</version>
              <configuration><java><googleJavaFormat/></java></configuration>
            </plugin></plugins></build>
            """.trimIndent()
        )
        val service = SpotlessConfigService.getInstance(project)
        val srcFile = createProjectSubFile("src/main/java/A.java", "class A {}")
        val first = found(service.lookup(srcFile))
        assertSame(first, found(service.lookup(srcFile)))

        importProjectAsync(
            """
            <groupId>test</groupId>
            <artifactId>app</artifactId>
            <version>1</version>
            <build><plugins><plugin>
              <groupId>com.diffplug.spotless</groupId>
              <artifactId>spotless-maven-plugin</artifactId>
              <version>3.11.0</version>
              <configuration><java><palantirJavaFormat/></java></configuration>
            </plugin></plugins></build>
            """.trimIndent()
        )
        val second = found(service.lookup(srcFile))
        assertFalse(first.configId() == second.configId())
        assertTrue(xmlOf(second).contains("palantirJavaFormat"))
    }
}
