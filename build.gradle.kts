import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.10.5"
}

group = "dev.insight"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        // Ultimate is required to compile against Go and JavaScript/TypeScript (both need
        // com.intellij.modules.ultimate). Every language dependency is optional at runtime,
        // so the artifact still installs in Community, PyCharm, GoLand, WebStorm, etc.
        intellijIdeaUltimate("2025.2.4")
        // Optional language support for the call graph (see META-INF/call-graph-*.xml).
        bundledPlugins("com.intellij.java", "org.jetbrains.kotlin", "JavaScript")
        // PythonCore requires TOML; without it the Python plugin does not load in tests.
        bundledPlugin("org.toml.lang")
        // Optional: comparing the working tree with a named Git revision (see META-INF/diff-git.xml).
        bundledPlugin("Git4Idea")
        // Marketplace plugins matching the target build (IU-252.27397.103).
        plugins("PythonCore:252.27397.103", "org.jetbrains.plugins.go:252.27397.103")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
    }
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            // Build against the lowest supported platform (2025.2, Java 21) so the
            // same artifact stays binary-compatible across the range; the ceiling
            // must cover the newest validated IDE (2026.2, branch 262).
            sinceBuild = "252"
            untilBuild = "262.*"
        }
    }
    pluginVerification { ides { create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaUltimate, "2025.3.6") } }
}
