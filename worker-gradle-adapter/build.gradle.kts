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

// Compiled against spotless-lib; at runtime the build's own lib/lib-extra jars (from the buildscript
// classpath captured at sync time) are on the child classloader instead.
val spotlessLibVersion = "4.11.0"

dependencies {
    compileOnly(project(":worker"))
    compileOnly(project(":protocol"))
    compileOnly("com.diffplug.spotless:spotless-lib:$spotlessLibVersion")
    compileOnly("com.diffplug.spotless:spotless-lib-extra:$spotlessLibVersion")
    compileOnly("org.eclipse.jgit:org.eclipse.jgit:7.8.0.202609011348-r")
}

tasks.jar {
    archiveBaseName = "worker-gradle-adapter"
}
