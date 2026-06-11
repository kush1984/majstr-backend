plugins {
    // Fallback toolchain auto-provisioning: lets Gradle fetch a JDK 21 from the
    // foojay Disco API if the build machine lacks one. With Java 21 (stable,
    // ubiquitous images) the host JDK normally already matches, so this stays
    // dormant — but it's a harmless safety net for a bare CI. An already-installed
    // JDK 21 is used as-is; nothing is downloaded.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "majstr-backend"
