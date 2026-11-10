plugins {
    id("java")
    id("org.jetbrains.intellij.platform.module")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

evaluationDependsOn(":protocol")

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

tasks.withType<JavaCompile>().configureEach {
    // Runs inside the user's Gradle daemon during IDE sync; keep the bytecode level conservative.
    options.release = 11
    options.encoding = "UTF-8"
}
tasks.named<JavaCompile>("compileTestJava") {
    options.release = 21
}

// Main targets Java 11 (Gradle daemon), but the tests run on the build JDK with Java 17 test deps.
configurations.matching { it.name.startsWith("test") }.configureEach {
    attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
}

dependencies {
    // Gradle API + IntelliJ's tooling-extension API (ModelBuilderService) come from the bundled Gradle plugin.
    intellijPlatform {
        val localIde = providers.gradleProperty("platformLocalPath")
        if (localIde.isPresent) local(localIde) else intellijIdea(providers.gradleProperty("platformVersion"))
        bundledPlugin("com.intellij.gradle")
    }

    testImplementation(project(":protocol"))
    testImplementation(gradleTestKit())
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("spotless.test.fixtures", rootProject.layout.projectDirectory.dir("fixtures").asFile.absolutePath)
    systemProperty("spotless.test.gradleHome", gradle.gradleHomeDir!!.absolutePath)
    systemProperty("spotless.test.toolingJar", tasks.jar.get().archiveFile.get().asFile.absolutePath)
    systemProperty("spotless.test.protocolJar", project(":protocol").tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath)
    dependsOn(tasks.jar, ":protocol:jar")
}

intellijPlatform {
    // See the root build script: no instrumentation when compiling against a local IDE.
    instrumentCode = !providers.gradleProperty("platformLocalPath").isPresent
}
