pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

// Lets Gradle auto-provision the JDK 25 toolchain (jvmToolchain(25) in build.gradle.kts)
// when no matching JDK is installed locally — downloads/manages a Temurin 25 build so the
// project compiles the same way on any machine, independent of the IDE's SDK.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "ibms-backend"
