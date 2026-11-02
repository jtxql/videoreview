plugins {
    id("com.android.application")
}

android {
    namespace = "io.tabletennisreview.usbdiagnostics"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "io.tabletennisreview.usbdiagnostics"
        minSdk = 26
        targetSdk = 37
        versionCode = 15
        versionName = "0.5.5-ab-markers"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}


// Device-free unit checks. Uses only existing Kotlin runtime and JDK, no test download.
val inputCheckClasses = layout.buildDirectory.dir("input-check-classes")
val appKotlinClasses = layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")
// JVM checks need transformed class JARs, not ambiguous Android project artifacts.
val inputCheckRuntime = files(providers.provider {
    configurations.getByName("debugRuntimeClasspath").incoming.artifactView {
        attributes.attribute(org.gradle.api.artifacts.type.ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "android-classes-jar")
    }.files
})
val compileInputChecks by tasks.registering(JavaCompile::class) {
    dependsOn("compileDebugKotlin")
    source("src/testInput/java/PlayerUiCheck.java", "src/testInput/java/AutoInitCheck.java", "src/testInput/java/TourBoxPlayerBindingCheck.java", "src/testInput/java/TourBoxInputCheck.java", "../../scripts/tests/Config94Check.java", "src/testInput/java/UsbAsyncCleanupCheck.java", "src/testInput/java/UsbGenerationCheck.java")
    classpath = files(appKotlinClasses) + inputCheckRuntime
    destinationDirectory.set(inputCheckClasses)
    options.encoding = "UTF-8"
    options.release.set(17)
}
tasks.register<JavaExec>("testInputEvents") {
    dependsOn(compileInputChecks)
    classpath = files(inputCheckClasses, appKotlinClasses) + inputCheckRuntime
    mainClass.set("TourBoxInputCheck")
}
tasks.register<JavaExec>("testConfig94") {
    dependsOn(compileInputChecks)
    classpath = files(inputCheckClasses, appKotlinClasses) + inputCheckRuntime
    mainClass.set("Config94Check")
}
tasks.named("check") { dependsOn("testInputEvents", "testConfig94") }

tasks.register<JavaExec>("testUsbAsyncCleanup") {
    dependsOn(compileInputChecks)
    classpath = files(inputCheckClasses, appKotlinClasses) + inputCheckRuntime
    mainClass.set("UsbAsyncCleanupCheck")
}
tasks.named("check") { dependsOn("testUsbAsyncCleanup") }

tasks.register<JavaExec>("testUsbGenerations") {
    dependsOn(compileInputChecks)
    classpath = files(inputCheckClasses, appKotlinClasses) + inputCheckRuntime
    mainClass.set("UsbGenerationCheck")
}
tasks.named("check") { dependsOn("testUsbGenerations") }

dependencies {
    implementation(project(":player-mpv"))
    implementation(project(":video-export"))
}

// The standard app check also includes the new pure player contract checks.
tasks.named("check") { dependsOn(":player-mpv:testPlayer") }

tasks.register<JavaExec>("testTourBoxPlayerBinding") {
    dependsOn(compileInputChecks)
    classpath = files(inputCheckClasses, appKotlinClasses) + inputCheckRuntime
    mainClass.set("TourBoxPlayerBindingCheck")
}
tasks.named("check") { dependsOn("testTourBoxPlayerBinding") }

tasks.register<JavaExec>("testAutoInit") {
    dependsOn(compileInputChecks)
    classpath = files(inputCheckClasses, appKotlinClasses) + inputCheckRuntime
    mainClass.set("AutoInitCheck")
}
tasks.named("check") { dependsOn("testAutoInit") }

tasks.register<JavaExec>("testPlayerUi") {
    dependsOn(compileInputChecks)
    classpath = files(inputCheckClasses, appKotlinClasses) + inputCheckRuntime
    mainClass.set("PlayerUiCheck")
}
tasks.named("check") { dependsOn("testPlayerUi") }

tasks.named("check") { dependsOn(":video-export:testClipExport") }
