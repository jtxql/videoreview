plugins { id("com.android.library") }
android {
    namespace = "io.tabletennisreview.player"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies { implementation("io.github.yuroyami:libmpvkt-view:0.3.0") }

val playerCheckClasses = layout.buildDirectory.dir("player-check-classes")
val playerKotlinClasses = layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")
// JVM checks need transformed class JARs, not ambiguous Android project artifacts.
val playerCheckRuntime = files(providers.provider {
    configurations.getByName("debugRuntimeClasspath").incoming.artifactView {
        attributes.attribute(org.gradle.api.artifacts.type.ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "android-classes-jar")
    }.files
})
val compilePlayerChecks by tasks.registering(JavaCompile::class) {
    dependsOn("compileDebugKotlin")
    source("src/testPlayer/java/PlayerCheck.java")
    classpath = files(playerKotlinClasses) + playerCheckRuntime
    destinationDirectory.set(playerCheckClasses)
    options.encoding = "UTF-8"
    options.release.set(17)
}
tasks.register<JavaExec>("testPlayer") {
    dependsOn(compilePlayerChecks)
    classpath = files(playerCheckClasses, playerKotlinClasses) + playerCheckRuntime
    mainClass.set("PlayerCheck")
}
tasks.named("check") { dependsOn("testPlayer") }
