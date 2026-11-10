plugins {
    id("java-library")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
}

java {
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    // Shared by the IDE plugin (JDK 21) and the worker (user JDK, >= 17).
    options.release = 17
    options.encoding = "UTF-8"
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
