import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

fun properties(key: String) = project.findProperty(key).toString()

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.18.1"
    id("io.freefair.lombok") version "9.5.0"
    id("org.jetbrains.changelog") version "2.5.0"
}

group = properties("pluginGroup")
version = properties("pluginVersion")

// The IntelliJ Platform is now compiled for Java 25 (class file version 69), so the
// compiler must run on a JDK 25 toolchain even though we still target Java 21 bytecode
// (keeping the plugin runnable on the `pluginSinceBuild` IDE). Gradle auto-detects the
// JetBrains Runtime from ~/.jdks, so no machine-specific path is hardcoded here.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.JETBRAINS)
    }
}

// Configure project's dependencies
repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
        intellijDependencies()
    }
}

dependencies {
    intellijPlatform {
        zipSigner()

        create(properties("platformType"), properties("platformVersion")) {
            useInstaller = false
        }
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',') })
    }
}

// Configure Gradle IntelliJ Plugin - read more: https://github.com/JetBrains/gradle-intellij-plugin
intellijPlatform {
    // Disable bytecode instrumentation: there are no .form (GUI Designer) files, so it
    // would only add @NotNull runtime assertions, which we don't rely on. It also runs
    // IntelliJ's Javac2 Ant tool in-process in the Gradle daemon, which fails
    // ("<JAVA_HOME>\Packages does not exist") when the daemon JVM is the Microsoft Build
    // of OpenJDK — its non-standard java.ext.dirs points at a non-existent Packages dir
    // (https://github.com/microsoft/openjdk/issues/339). Keeping this off makes the
    // build independent of the daemon JDK vendor.
    instrumentCode = false

    pluginConfiguration {
        name.set(properties("pluginName"))
        version.set(properties("pluginVersion"))

        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        description.set(
            projectDir.resolve("README.md").readText().lines().run {
                val start = "<!-- Plugin description -->"
                val end = "<!-- Plugin description end -->"

                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end))
            }.joinToString("\n").run { markdownToHTML(this) }
        )

        // Get the latest available change notes from the changelog file
        changeNotes.set(provider {
            changelog.renderItem(changelog.run {
                getOrNull(properties("pluginVersion")) ?: getLatest()
            }, outputType = Changelog.OutputType.HTML)
        })

        ideaVersion {
            sinceBuild.set(properties("pluginSinceBuild"))
        }
    }

    signing {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishing {
        token.set(System.getenv("INTELLIJ_TOKEN"))
        channels.set(listOf("default"))
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaUltimate, properties("pluginVerifyVersion"))
        }
    }

    buildSearchableOptions.set(true)
}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    version.set(properties("pluginVersion"))
    groups.set(emptyList())
}

tasks {
    // Set the JVM compatibility versions
    properties("javaVersion").let {
        withType<JavaCompile> {
            sourceCompatibility = it
            targetCompatibility = it
        }
    }

    wrapper {
        gradleVersion = properties("gradleVersion")
    }

    runIde {
        jvmArgs = listOf("-Xmx1500M", "-XX:+AllowEnhancedClassRedefinition")
    }
}
