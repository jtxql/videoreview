plugins { id("com.android.library") }
android {
    namespace = "io.tabletennisreview.export"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("androidx.media3:media3-transformer:1.11.1")
    implementation("androidx.media3:media3-effect:1.11.1")
    implementation("androidx.media3:media3-common:1.11.1")
}

tasks.register("resolveExportDependencies") {
    doLast {
        val artifacts = configurations.getByName("debugRuntimeClasspath").resolve()
        println("Export dependency artifacts=${artifacts.size} bytes=${artifacts.sumOf { it.length() }}")
    }
}

val exportCheckClasses = layout.buildDirectory.dir("export-check-classes")
val exportKotlinClasses = layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")
val exportCheckRuntime = files(providers.provider {
    configurations.getByName("debugRuntimeClasspath").incoming.artifactView {
        attributes.attribute(org.gradle.api.artifacts.type.ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "android-classes-jar")
    }.files
})
val compileExportChecks by tasks.registering(JavaCompile::class) {
    dependsOn("compileDebugKotlin")
    source("src/testExport/java/ClipExportCheck.java")
    classpath = files(exportKotlinClasses) + exportCheckRuntime
    destinationDirectory.set(exportCheckClasses)
    options.encoding = "UTF-8"
    options.release.set(17)
}
tasks.register<JavaExec>("testClipExport") {
    dependsOn(compileExportChecks)
    classpath = files(exportCheckClasses, exportKotlinClasses) + exportCheckRuntime
    mainClass.set("ClipExportCheck")
}
tasks.named("check") { dependsOn("testClipExport") }
