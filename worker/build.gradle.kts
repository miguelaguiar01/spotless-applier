plugins {
    id("java")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
}

tasks.withType<JavaCompile>().configureEach {
    // Runs on the user's JDK: Spotless 3.x/8.x needs 17, so do we.
    options.release = 17
    options.encoding = "UTF-8"
}

val mavenVersion = "3.10.0"
val resolverVersion = "2.0.25"

dependencies {
    implementation(project(":protocol"))

    // Standalone Maven Resolver (ADR-007): repository system + session suppliers for the Maven 3 model.
    implementation("org.apache.maven.resolver:maven-resolver-supplier-mvn3:$resolverVersion")
    implementation("org.apache.maven.resolver:maven-resolver-transport-apache:$resolverVersion")
    implementation("org.apache.maven.resolver:maven-resolver-transport-file:$resolverVersion")
    // settings.xml (mirrors, proxies, servers, offline) and password decryption.
    implementation("org.apache.maven:maven-settings-builder:$mavenVersion")
    // MavenProject/Log/plugin API shared with the Spotless plugin classloader (parent-first packages).
    implementation("org.apache.maven:maven-core:$mavenVersion")
    implementation("org.apache.maven:maven-plugin-api:$mavenVersion")
    // Plexus component configurator (ADR-005) and class realms.
    implementation("org.eclipse.sisu:org.eclipse.sisu.plexus:1.1.2")
    implementation("org.codehaus.plexus:plexus-classworlds:2.12.1")
    // Resolver/Maven log through SLF4J; simple logger writes to System.err, which the worker forwards as log frames.
    implementation("org.slf4j:slf4j-simple:2.0.19")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Parity fixtures with ratchetFrom need a git repository; JGit keeps the test free of a git binary.
    testImplementation("org.eclipse.jgit:org.eclipse.jgit:7.8.0.202609011348-r")
}

configurations.all {
    // maven-core 3.x pulls Resolver 1.9/2.0.24; keep one Resolver version (the supplier's).
    resolutionStrategy.force(
        "org.apache.maven.resolver:maven-resolver-api:$resolverVersion",
        "org.apache.maven.resolver:maven-resolver-spi:$resolverVersion",
        "org.apache.maven.resolver:maven-resolver-util:$resolverVersion",
        "org.apache.maven.resolver:maven-resolver-impl:$resolverVersion",
        "org.apache.maven.resolver:maven-resolver-named-locks:$resolverVersion",
        "org.apache.maven.resolver:maven-resolver-connector-basic:$resolverVersion",
    )
}

tasks.jar {
    manifest {
        attributes("Main-Class" to "com.github.miguelaguiar01.spotless.worker.WorkerMain")
    }
}

// A real Maven distribution for the parity tests (golden outputs come from `mvn spotless:apply`).
val mavenDistribution: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    mavenDistribution("org.apache.maven:apache-maven:$mavenVersion:bin@zip")
}

val unpackMaven by tasks.registering(Sync::class) {
    from(mavenDistribution.map { zipTree(it) })
    into(layout.buildDirectory.dir("maven"))
}

tasks.test {
    useJUnitPlatform()
    // The integration test spawns a real worker JVM with the test's own classpath.
    systemProperty("spotless.worker.test.java", System.getProperty("java.home") + "/bin/java")
    systemProperty("spotless.worker.test.adapterDir", project(":worker-maven-adapter").layout.buildDirectory.dir("libs").get().asFile.absolutePath)
    systemProperty("spotless.test.fixtures", rootProject.layout.projectDirectory.dir("fixtures").asFile.absolutePath)
    systemProperty("spotless.test.mavenHome", layout.buildDirectory.dir("maven/apache-maven-$mavenVersion").get().asFile.absolutePath)
    // google-java-format & friends open javac internals reflectively; same flags the IDE passes the worker.
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
    dependsOn(":worker-maven-adapter:jar", unpackMaven)
    // Parity tests download formatters on first run; give them room.
    maxHeapSize = "1g"
}
