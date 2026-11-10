import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.10.5"
    id("org.jetbrains.changelog") version "2.5.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// JDK that runs the compilers. Bytecode stays at Java 21 regardless (release / jvmTarget below).
// IntelliJ 2026.3+ ships Java 25 class files, which javac 21 cannot read: when compiling against
// such an IDE (-PplatformLocalPath), pass -PbuildJdk=25 and have a JDK 25 installed.
val buildJdk: Int = providers.gradleProperty("buildJdk").map(String::toInt).getOrElse(21)

kotlin {
    jvmToolchain(buildJdk)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        // The IDE's bundled stdlib is used at runtime (kotlin.stdlib.default.dependency=false).
        // Pin the API level so compiling with a newer compiler cannot leak stdlib calls that the
        // oldest supported IDE (2026.1) does not have.
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Jars that are shipped inside the plugin distribution under lib/worker/ but are NOT part of the
// plugin classloader (IntelliJ only loads jars directly under lib/). The worker JVM is launched
// with an explicit -cp over this directory. See docs/DECISIONS.md ADR-015.
val workerRuntime: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

// Compiled against the Spotless Maven plugin API; loaded by the worker in the per-version child
// classloader next to the user's Spotless jars. Lives in lib/worker/adapter/, off the worker's own -cp.
val workerAdapter: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    implementation(project(":protocol"))
    // Gradle sync-time model builder; its jar is sent to the Gradle daemon (ADR-020).
    implementation(project(":gradle-tooling"))
    workerRuntime(project(":worker"))
    workerAdapter(project(":worker-maven-adapter"))
    workerAdapter(project(":worker-gradle-adapter"))

    intellijPlatform {
        // -PplatformLocalPath=<installed IDE> compiles against a local installation instead of
        // downloading the platform (for networks that block JetBrains' download hosts).
        val localIde = providers.gradleProperty("platformLocalPath")
        if (localIde.isPresent) local(localIde) else intellijIdea(providers.gradleProperty("platformVersion"))
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',').filter(String::isNotBlank) })
        plugins(providers.gradleProperty("platformPlugins").map { it.split(',').filter(String::isNotBlank) })
        bundledModules(providers.gradleProperty("platformBundledModules").map { it.split(',').filter(String::isNotBlank) })
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Maven)
    }

    testImplementation("junit:junit:4.13.2")
}

if (providers.gradleProperty("platformLocalPath").isPresent) {
    // The searchable-options tasks launch the IDE and derive their classpath from the IDE's
    // product-info.json launch section for the *current* OS. A Windows installation used from WSL
    // has none, and even a disabled task gets serialized into the configuration cache, so take the
    // tasks out of the graph altogether. Searchable options only index the settings page.
    gradle.startParameter.excludedTaskNames.addAll(
        listOf("buildSearchableOptions", "prepareJarSearchableOptions", "jarSearchableOptions"),
    )
}

intellijPlatform {
    buildSearchableOptions = !providers.gradleProperty("platformLocalPath").isPresent
    // Instrumentation (@NotNull assertions, GUI forms; this plugin has no forms) downloads
    // java-compiler-ant-tasks from JetBrains' repository, which such networks block as well.
    instrumentCode = !providers.gradleProperty("platformLocalPath").isPresent

    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"
            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        val changelog = project.changelog
        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginVerification {
        failureLevel = listOf(
            FailureLevel.COMPATIBILITY_PROBLEMS,
            FailureLevel.INTERNAL_API_USAGES,
            FailureLevel.OVERRIDE_ONLY_API_USAGES,
        )
        ides {
            recommended()
        }
    }
}

changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
}

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }

    publishPlugin {
        dependsOn("patchChangelog")
    }

    withType<JavaCompile>().configureEach {
        options.release = 21
        options.encoding = "UTF-8"
    }

    withType<PrepareSandboxTask>().configureEach {
        // pluginName here is the sandbox directory name (derived from the jar), not the display name.
        from(workerRuntime) {
            into(pluginName.map { "$it/lib/worker" })
        }
        from(workerAdapter) {
            into(pluginName.map { "$it/lib/worker/adapter" })
        }
    }

    test {
        // Let plugin-side tests find the worker jars without a sandbox.
        systemProperty("spotless.applier.worker.dir", layout.buildDirectory.dir("worker-classpath").get().asFile.absolutePath)
        dependsOn("copyWorkerClasspathForTests")
    }
}

val copyWorkerClasspathForTests by tasks.registering(Sync::class) {
    from(workerRuntime)
    from(workerAdapter) { into("adapter") }
    into(layout.buildDirectory.dir("worker-classpath"))
}
