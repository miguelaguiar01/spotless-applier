plugins {
    java
    id("com.diffplug.spotless") version "7.0.4"
}

repositories {
    mavenCentral()
}

spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat()
        endWithNewline()
    }
}
