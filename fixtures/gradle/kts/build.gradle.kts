plugins {
    java
    id("com.diffplug.spotless") version "8.11.0"
}

repositories {
    mavenCentral()
}

spotless {
    java {
        target("src/**/*.java")
        licenseHeader("/* (c) \$YEAR kts fixture */")
        removeUnusedImports()
        importOrder("java", "javax", "com", "")
        googleJavaFormat("1.33.0").reflowLongStrings()
        formatAnnotations()
    }
    format("misc") {
        target("*.md", "src/main/resources/**/*.properties")
        targetExclude("IGNORED.md")
        trimTrailingWhitespace()
        endWithNewline()
        leadingTabsToSpaces(2)
    }
}
