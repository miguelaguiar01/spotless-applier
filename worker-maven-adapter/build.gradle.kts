plugins {
    id("java")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.encoding = "UTF-8"
}

// Compiled against the newest supported Spotless Maven plugin; at runtime the user's version is on the
// classpath instead. Differences between supported versions are handled reflectively in the adapter.
val spotlessMavenVersion = "3.11.0"
val spotlessLibVersion = "4.11.0"
val mavenVersion = "3.10.0"
val resolverVersion = "2.0.25"

dependencies {
    compileOnly(project(":worker"))
    compileOnly(project(":protocol"))
    compileOnly("com.diffplug.spotless:spotless-maven-plugin:$spotlessMavenVersion")
    compileOnly("com.diffplug.spotless:spotless-lib:$spotlessLibVersion")
    compileOnly("com.diffplug.spotless:spotless-lib-extra:$spotlessLibVersion")
    compileOnly("org.apache.maven:maven-core:$mavenVersion")
    compileOnly("org.apache.maven:maven-plugin-api:$mavenVersion")
    compileOnly("org.apache.maven.resolver:maven-resolver-api:$resolverVersion")
    compileOnly("org.codehaus.plexus:plexus-resources:1.4.0")
    compileOnly("org.codehaus.plexus:plexus-utils:4.1.0")
    compileOnly("org.eclipse.sisu:org.eclipse.sisu.plexus:1.1.2")
    compileOnly("org.codehaus.plexus:plexus-classworlds:2.12.1")
    compileOnly("org.eclipse.jgit:org.eclipse.jgit:7.8.0.202609011348-r")
}

tasks.jar {
    archiveBaseName = "worker-maven-adapter"
}
