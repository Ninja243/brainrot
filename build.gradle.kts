plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.10.4"
}

group = "local.brainrot"
version = providers.gradleProperty("pluginVersion").getOrElse("0.0.0-dev")

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    intellijPlatform {
        // ponytail: locally compiles against the installed IDE instead of downloading one; set CI=true on a machine without it
        if (providers.environmentVariable("CI").isPresent) {
            intellijIdeaUltimate("2025.3")
        } else {
            local("/Applications/IntelliJ IDEA.app")
        }
    }
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
            untilBuild = provider { null }
        }
    }
}
