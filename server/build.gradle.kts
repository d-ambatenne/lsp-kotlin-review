plugins {
    kotlin("jvm") version "2.1.0"
    id("com.gradleup.shadow") version "9.0.0-beta4"
}

group = "dev.review"
version = "0.23.0"

val kotlinVersion = "2.1.0"

repositories {
    mavenCentral()
    maven("https://packages.jetbrains.team/maven/p/kt/kotlin-ide-plugin-dependencies")
    maven("https://www.jetbrains.com/intellij-repository/releases")
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
    maven("https://repo.gradle.org/gradle/libs-releases")
}

dependencies {
    // LSP
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.24.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Kotlin Analysis API (standalone, K2/FIR) — all isTransitive=false
    // because POMs reference unpublished internal module names
    listOf(
        "org.jetbrains.kotlin:analysis-api-standalone-for-ide",
        "org.jetbrains.kotlin:analysis-api-for-ide",
        "org.jetbrains.kotlin:analysis-api-k2-for-ide",
        "org.jetbrains.kotlin:analysis-api-platform-interface-for-ide",
        "org.jetbrains.kotlin:analysis-api-impl-base-for-ide",
        "org.jetbrains.kotlin:low-level-api-fir-for-ide",
        "org.jetbrains.kotlin:symbol-light-classes-for-ide",
        "org.jetbrains.kotlin:kotlin-compiler-common-for-ide",
        "org.jetbrains.kotlin:kotlin-compiler-fir-for-ide",
        "org.jetbrains.kotlin:kotlin-compiler-ir-for-ide",
        "org.jetbrains.kotlin:kotlin-compiler-cli-for-ide",
        "org.jetbrains.kotlin:kotlin-compiler-fe10-for-ide",
    ).forEach {
        implementation("$it:$kotlinVersion") { isTransitive = false }
    }

    // IntelliJ Platform (PSI infrastructure needed by Analysis API)
    // All isTransitive=false — transitive deps declared explicitly below
    val intellijVersion = "243.21565.193"
    listOf(
        "com.jetbrains.intellij.platform:util",
        "com.jetbrains.intellij.platform:util-base",
        "com.jetbrains.intellij.platform:util-rt",
        "com.jetbrains.intellij.platform:util-rt-java8",
        "com.jetbrains.intellij.platform:util-class-loader",
        "com.jetbrains.intellij.platform:util-text-matching",
        "com.jetbrains.intellij.platform:util-xml-dom",
        "com.jetbrains.intellij.platform:util-coroutines",
        "com.jetbrains.intellij.platform:util-progress",
        "com.jetbrains.intellij.platform:util-diff",
        "com.jetbrains.intellij.platform:util-jdom",
        "com.jetbrains.intellij.platform:core",
        "com.jetbrains.intellij.platform:core-impl",
        "com.jetbrains.intellij.platform:extensions",
        "com.jetbrains.intellij.platform:diagnostic",
        "com.jetbrains.intellij.platform:diagnostic-telemetry",
        "com.jetbrains.intellij.java:java-frontback-psi",
        "com.jetbrains.intellij.java:java-frontback-psi-impl",
        "com.jetbrains.intellij.java:java-psi",
        "com.jetbrains.intellij.java:java-psi-impl",
    ).forEach {
        implementation("$it:$intellijVersion") { isTransitive = false }
    }

    // Supporting libraries (explicit transitive deps of IntelliJ Platform)
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable-jvm:0.3.8")
    implementation("org.jetbrains:annotations:24.1.0")
    implementation("org.jetbrains.intellij.deps.fastutil:intellij-deps-fastutil:8.5.14-jb1")

    // OpenTelemetry (diagnostic-telemetry)
    implementation("io.opentelemetry:opentelemetry-api:1.41.0")
    implementation("io.opentelemetry:opentelemetry-sdk:1.41.0")
    implementation("io.opentelemetry:opentelemetry-sdk-common:1.41.0")
    implementation("io.opentelemetry:opentelemetry-sdk-trace:1.41.0")
    implementation("io.opentelemetry:opentelemetry-sdk-metrics:1.41.0")
    implementation("io.opentelemetry:opentelemetry-sdk-logs:1.41.0")
    implementation("io.opentelemetry:opentelemetry-context:1.41.0")

    // ASM (java-psi-impl, java-frontback-psi-impl)
    implementation("org.jetbrains.intellij.deps:asm-all:9.6.1")

    // StreamEx (java-psi-impl)
    implementation("one.util:streamex:0.8.2")

    // Guava (util-progress -> core-impl)
    implementation("com.google.guava:guava:33.3.0-jre")

    // XML (util, util-xml-dom)
    implementation("com.fasterxml:aalto-xml:1.3.3")

    // JNA (util)
    implementation("net.java.dev.jna:jna-platform:5.14.0")

    // Compression/IO (util)
    implementation("org.lz4:lz4-java:1.8.0")
    implementation("org.apache.commons:commons-compress:1.26.1")
    implementation("commons-io:commons-io:2.16.1")

    // Caching (util)
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

    // kotlinx-serialization (util-xml-dom, util-progress)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.6.3")

    // XPath (util-jdom)
    implementation("jaxen:jaxen:1.2.0")

    // Automaton (core-impl)
    implementation("dk.brics:automaton:1.12-4")

    // IntelliJ patched coroutines fork (util-base, core, etc.)
    implementation("com.intellij.platform:kotlinx-coroutines-core-jvm:1.8.0-intellij-11")

    // Gradle Tooling API
    implementation("org.gradle:gradle-tooling-api:8.12")

    // Test
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

kotlin {
    jvmToolchain(17)
}

tasks.shadowJar {
    archiveBaseName.set("server")
    archiveClassifier.set("all")
    archiveVersion.set("")
    manifest {
        attributes("Main-Class" to "dev.review.lsp.ServerKt")
    }
    mergeServiceFiles()
}

tasks.test {
    useJUnitPlatform()
    jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED"
    )
}
