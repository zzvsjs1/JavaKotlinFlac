import org.gradle.api.plugins.ApplicationPlugin
import org.gradle.jvm.application.tasks.CreateStartScripts
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":"))
    implementation(project(":jflac-java-sound"))
    implementation("com.googlecode.lanterna:lanterna:3.1.5")
    implementation("org.jline:jline-terminal:4.3.1")
    runtimeOnly("org.jline:jline-terminal-jni:4.3.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.14.4")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

application {
    applicationName = "jflac-feature-demo"
    mainClass.set("org.zzvsjs.jflac.examples.JavaFeatureDemo")
    applicationDistribution.from(rootProject.file("LICENSE"))
    applicationDistribution.from(rootProject.file("THIRD_PARTY_NOTICES.md"))
}

/*
 * Gradle JavaExec connects child processes through pipes, so it cannot offer
 * the Win32/POSIX terminal handle required by a raw, mouse-aware TUI. This
 * second distribution script runs the player as the foreground process of the
 * user's terminal while reusing the normal application distribution's lib/
 * directory. The feature-demo launcher remains unchanged.
 */
val playbackStartScripts by tasks.registering(CreateStartScripts::class) {
    applicationName = "jflac-playback"
    mainClass.set("org.zzvsjs.jflac.examples.JavaSoundPlaybackDemo")
    outputDir = layout.buildDirectory.dir("playback-start-scripts").get().asFile
    classpath = files(tasks.named<Jar>("jar"), configurations.runtimeClasspath)
    defaultJvmOpts = listOf("--enable-native-access=ALL-UNNAMED")
}

application {
    applicationDistribution.into("bin") {
        from(playbackStartScripts)
        filePermissions {
            unix("rwxr-xr-x")
        }
    }
}

tasks.withType<JavaExec>().configureEach {
    workingDir = rootProject.projectDir
    systemProperty(
        "jflac.native.tmpdir",
        layout.buildDirectory.dir("native-example").get().asFile.absolutePath
    )
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("runKotlinFeatureDemo") {
    group = ApplicationPlugin.APPLICATION_GROUP
    description = "Runs the Kotlin jflac feature demo. Pass the FLAC path with --args."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.zzvsjs.jflac.examples.KotlinFeatureDemoKt")
}

tasks.register<JavaExec>("runJavaSoundPlayback") {
    group = ApplicationPlugin.APPLICATION_GROUP
    description = "Runs the Java Sound player; use the installed jflac-playback script for the native TUI."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.zzvsjs.jflac.examples.JavaSoundPlaybackDemo")
    /* Gradle otherwise supplies an empty stdin stream to the child JVM. */
    standardInput = System.`in`
}
