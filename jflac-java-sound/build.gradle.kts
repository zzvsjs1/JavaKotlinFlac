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
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
    withJavadocJar()
}

tasks.test {
    useJUnitPlatform()
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
