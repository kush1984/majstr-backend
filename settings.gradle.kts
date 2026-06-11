plugins {
    // Toolchain auto-provisioning: lets Gradle download a matching JDK from the
    // foojay Disco API (Temurin by default) when the build machine has no Java 25.
    // This is what configures the "toolchain download repositories" — without it
    // Gradle can only *find* a local JDK 25, never fetch one (the Railway failure).
    // Locally, an already-installed JDK 25 is used as-is; nothing is downloaded.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "majstr-backend"
