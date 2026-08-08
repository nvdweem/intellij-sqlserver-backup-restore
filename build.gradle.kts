import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

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

        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.JUnit5)
    }

    // 5.12 at the very least: the platform's fixture extension calls ExtensionContext.getEnclosingTestClasses(),
    // which does not exist before that, and every platform test fails to initialise.
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // The platform's test framework is still rooted in JUnit 3's TestCase, and its JUnit 5 session listener fails to
    // load without it - taking every test in the build down with it, including the ones that never touch the platform.
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")

    // The IDE downloads this driver on demand at runtime, so it is not on the plugin's compile classpath. The
    // integration tests need it to talk to a real server, and pinning it here keeps them reproducible.
    testImplementation("com.microsoft.sqlserver:mssql-jdbc:12.8.1.jre11")
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
            // Left empty on purpose: without this the plugin would inherit the build branch of whatever platform it
            // was compiled against as an upper bound, so every new IDE release would mark it incompatible.
            untilBuild.set(providers.gradleProperty("pluginUntilBuild").map { it.trim() }.filter { it.isNotEmpty() })
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
    // `release` rather than source/target compatibility: those only pick the language level and bytecode version while
    // still compiling against the JDK 25 class library, so a Java 22+ API would compile here and fail on a Java 21 IDE.
    withType<JavaCompile> {
        options.release.set(properties("javaVersion").toInt())
        // serial and this-escape fire on every DataRequest and DialogWrapper subclass, which is simply how those are
        // meant to be used. Left on, they'd drown out the warnings that do mean something.
        options.compilerArgs.add("-Xlint:all,-serial,-this-escape,-processing")
    }

    test {
        useJUnitPlatform()

        // Forwarded explicitly: a -D on the Gradle command line reaches the Gradle JVM, not the forked test JVM, so
        // without this the integration tests would silently keep using their defaults. Registered as task inputs too,
        // or pointing them at a different server would be answered with UP-TO-DATE.
        // (The IT_SQLSERVER_* environment variables need none of this - the test JVM inherits the environment.)
        listOf("it.sqlserver.url", "it.sqlserver.user", "it.sqlserver.password").forEach { key ->
            val value = providers.systemProperty(key)
            inputs.property(key, value).optional(true)
            if (value.isPresent) {
                systemProperty(key, value.get())
            }
        }

        // The IDE fetches JDBC drivers on demand, which a test must not do. ClientIT points DataGrip's driver
        // definition at this jar instead; the platform's classloader hides it from getCodeSource(), so pass the path.
        systemProperty("it.mssql.jdbc.jar", configurations.testRuntimeClasspath.map { classpath ->
            classpath.files.first { it.name.startsWith("mssql-jdbc") }.absolutePath
        }.get())
    }

    wrapper {
        gradleVersion = properties("gradleVersion")
    }

    runIde {
        jvmArgs = listOf("-Xmx1500M", "-XX:+AllowEnhancedClassRedefinition")
    }
}
