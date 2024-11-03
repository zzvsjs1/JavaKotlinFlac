plugins {
    application
}

repositories {
    mavenLocal()
    mavenCentral()
}

val jflacVersion = providers.gradleProperty("jflacVersion")
    .getOrElse("0.1.0-SNAPSHOT")
val samplePath = providers.gradleProperty("jflacSamplePath")
    .orElse(layout.projectDirectory.file("../music.flac").asFile.absolutePath)

dependencies {
    implementation("org.zzvsjs:jflac:$jflacVersion")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("org.zzvsjs.jflac.consumer.ConsumerSmokeTest")
}

tasks.named<JavaExec>("run") {
    systemProperty(
        "jflac.native.tmpdir",
        layout.buildDirectory.dir("native-smoke").get().asFile.absolutePath
    )

    doFirst {
        args(samplePath.get())
    }
}
