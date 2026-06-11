import java.io.File
import java.net.URI
import java.util.zip.ZipFile

plugins {
    java
    id("org.springframework.boot") version "4.0.6"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.majstr"
version = "0.0.1-SNAPSHOT"
description = "Majstr backend — SaaS for Ukrainian contractors"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Actuator — exposes /actuator/health (DB indicator + liveness/readiness probes)
    // for external monitoring. Exposure is locked down to `health` in application.yml.
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Spring Boot 4 split Flyway auto-config into its own module; no starter pulls it.
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    implementation("com.bucket4j:bucket4j_jdk17-core:8.14.0")

    // Web Push (VAPID / RFC 8291). MIT, github.com/web-push-libs/webpush-java.
    implementation("nl.martijndwars:web-push:5.1.2")
    // web-push declares Apache HttpClient and jose4j as `runtime` scope, but
    // PushService.send() returns org.apache.http.HttpResponse and declares
    // `throws org.jose4j.lang.JoseException` — both compile-time types where we
    // call it. Promote them onto the compile classpath; versions match what
    // web-push pulls transitively at runtime.
    implementation("org.apache.httpcomponents:httpcore:4.4.16")
    implementation("org.bitbucket.b_c:jose4j:0.7.9")
    // web-push 5.1.2 ships BouncyCastle 1.71 (2022); pin to a newer build that
    // is tested on current JDKs so the security provider registers cleanly on
    // Java 21+. The provider classes used (EC key handling) are stable across
    // these versions, so this override is safe.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    // Error monitoring. Core SDK only (no Spring Boot starter) — initialized
    // manually in SentryInitializer so it stays env-gated (blank SENTRY_DSN →
    // disabled, like Resend/VAPID) and avoids Spring Boot 4 auto-config surprises.
    implementation("io.sentry:sentry:8.43.1")

    // Object storage (Cloudflare R2 / any S3-compatible) — used when
    // app.storage.kind=s3. AWS SDK v2, sync client over the lightweight
    // URLConnection HTTP client (no Netty). BOM pins the module versions.
    implementation(platform("software.amazon.awssdk:bom:2.46.8"))
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:url-connection-client")

    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.6")

    // PDF generation. OpenPDF is LGPL fork of iText 4, actively maintained.
    implementation("com.github.librepdf:openpdf:2.0.3")

    runtimeOnly("org.postgresql:postgresql")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // DevTools: hot-reload of static resources + automatic restart on classpath changes.
    // developmentOnly so it never ships in a production jar.
    developmentOnly("org.springframework.boot:spring-boot-devtools")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
}

// PDF generation needs Cyrillic-capable fonts. Bundled JDK fonts cannot do
// Ukrainian, and OpenPDF ships no fonts of its own. The DejaVu source repo
// holds only FontForge .sfd files; pre-built .ttf binaries live only in
// SourceForge release zips. Download the zip on first build and extract
// the two TTFs we need. Both .ttf files are .gitignore'd so the repo stays
// small. If your network blocks SourceForge, see README for manual setup.
val downloadPdfFonts by tasks.registering {
    description = "Download DejaVu fonts used by EstimatePdfService"
    group = "build setup"
    val fontDir = layout.projectDirectory.dir("src/main/resources/fonts")
    val zipUrl = "https://downloads.sourceforge.net/project/dejavu/dejavu/2.37/dejavu-fonts-ttf-2.37.zip"
    val needed = setOf("DejaVuSans.ttf", "DejaVuSans-Bold.ttf")
    outputs.files(needed.map { fontDir.file(it).asFile })
    onlyIf { needed.any { !fontDir.file(it).asFile.exists() } }
    doLast {
        val dir = fontDir.asFile
        dir.mkdirs()
        val temp = File.createTempFile("dejavu-", ".zip")
        try {
            logger.lifecycle("Downloading DejaVu fonts from $zipUrl")
            val connection = URI(zipUrl).toURL().openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", "majstr-backend-gradle")
            connection.instanceFollowRedirects = true
            connection.inputStream.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            ZipFile(temp).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val basename = entry.name.substringAfterLast('/')
                    if (basename in needed) {
                        val target = File(dir, basename)
                        zip.getInputStream(entry).use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                        logger.lifecycle("Extracted $basename")
                    }
                }
            }
        } finally {
            temp.delete()
        }
    }
}

tasks.named("processResources") { dependsOn(downloadPdfFonts) }
