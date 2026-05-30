import org.gradle.api.plugins.ApplicationPlugin
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
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

application {
    applicationName = "jflac-feature-demo"
    mainClass.set("org.zzvsjs.jflac.examples.JavaFeatureDemo")
}

tasks.withType<JavaExec>().configureEach {
    workingDir = rootProject.projectDir
    systemProperty(
        "jflac.native.tmpdir",
        layout.buildDirectory.dir("native-example").get().asFile.absolutePath
    )
}

tasks.register<JavaExec>("runKotlinFeatureDemo") {
    group = ApplicationPlugin.APPLICATION_GROUP
    description = "Runs the Kotlin jflac feature demo. Pass the FLAC path with --args."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.zzvsjs.jflac.examples.KotlinFeatureDemoKt")
}
