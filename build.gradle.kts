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
        intellijIdeaCommunity("2025.2.4")
        // Optional language support for the call graph (see META-INF/call-graph-*.xml).
        bundledPlugins("com.intellij.java", "org.jetbrains.kotlin")
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
    pluginVerification { ides { recommended() } }
}
