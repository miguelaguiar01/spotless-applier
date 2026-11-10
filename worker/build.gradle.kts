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

dependencies {
    implementation(project(":protocol"))

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.jar {
    manifest {
        attributes("Main-Class" to "com.github.miguelaguiar01.spotless.worker.WorkerMain")
    }
}

tasks.test {
    useJUnitPlatform()
    // The integration test spawns a real worker JVM with the test's own classpath.
    systemProperty("spotless.worker.test.java", System.getProperty("java.home") + "/bin/java")
}
