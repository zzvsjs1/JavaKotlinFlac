import org.gradle.jvm.tasks.Jar
import java.util.zip.ZipFile

plugins {
    `java-library`
    `maven-publish`
}

group = rootProject.group
version = rootProject.version

repositories {
    mavenCentral()
}

dependencies {
    api(project(":"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.14.4")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
    withJavadocJar()
}

tasks.test {
    useJUnitPlatform()
}

val requiredJavaSoundServiceProviders = listOf(
    "META-INF/services/javax.sound.sampled.spi.AudioFileReader" to "org.zzvsjs.jflac.sound.FlacAudioFileReader",
    "META-INF/services/javax.sound.sampled.spi.AudioFileWriter" to "org.zzvsjs.jflac.sound.FlacAudioFileWriter"
)

val verifyJavaSoundServiceJar by tasks.registering {
    group = "verification"
    description = "Verifies that the Java Sound SPI JAR contains reader and writer service descriptors."

    val packagedJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    dependsOn(tasks.named("jar"))
    inputs.file(packagedJar)

    doLast {
        ZipFile(packagedJar.get().asFile).use { jar ->
            val missingEntries = requiredJavaSoundServiceProviders
                .filter { (entry, _) -> jar.getEntry(entry) == null }
                .map { (entry, _) -> entry }
            val missingProviders = requiredJavaSoundServiceProviders.mapNotNull { (entry, provider) ->
                val descriptor = jar.getEntry(entry) ?: return@mapNotNull null
                val content = jar.getInputStream(descriptor).bufferedReader(Charsets.UTF_8).use { it.readText() }
                if (content.contains(provider)) {
                    null
                } else {
                    "$entry is missing provider $provider"
                }
            }

            check(missingEntries.isEmpty() && missingProviders.isEmpty()) {
                buildString {
                    appendLine("Java Sound SPI JAR has incomplete service descriptors.")
                    missingEntries.forEach { entry -> appendLine(" - missing descriptor $entry") }
                    missingProviders.forEach { provider -> appendLine(" - $provider") }
                }
            }
        }
    }
}

tasks.check {
    dependsOn(verifyJavaSoundServiceJar)
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "jflac-java-sound"

            pom {
                name.set("jflac Java Sound")
                description.set("Java Sound SPI integration for the jflac FLAC codec.")
                url.set("https://github.com/zzvsjs/jflac")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/license/mit")
                    }
                }
            }
        }
    }
}
