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
    .orElse(layout.buildDirectory.file("consumer-smoke/music.flac").map { it.asFile.absolutePath })
val suppliedJflacJar = providers.gradleProperty("jflacJar").orNull?.let(::file)
val suppliedJavaSoundJar = providers.gradleProperty("jflacJavaSoundJar").orNull?.let(::file)

dependencies {
    if (suppliedJflacJar == null && suppliedJavaSoundJar == null) {
        implementation("org.zzvsjs:jflac:$jflacVersion")
        implementation("org.zzvsjs:jflac-java-sound:$jflacVersion")
    } else {
        require(suppliedJflacJar != null && suppliedJavaSoundJar != null) {
            "jflacJar and jflacJavaSoundJar must be supplied together."
        }
        implementation(files(suppliedJflacJar, suppliedJavaSoundJar))
        /* File dependencies have no POM from which to obtain jflac's runtime dependency. */
        implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.20")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
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
        listOfNotNull(suppliedJflacJar, suppliedJavaSoundJar).forEach { suppliedJar ->
            require(suppliedJar.isFile) {
                "Supplied consumer smoke-test JAR does not exist: ${suppliedJar.path}"
            }
        }
        args(samplePath.get())
    }
}
