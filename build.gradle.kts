import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.kotlin.dsl.withType

plugins {
    kotlin("jvm") version "1.9.25"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:2.3.12")
    implementation("io.ktor:ktor-server-cio-jvm:2.3.12")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:2.3.12")
    implementation("io.ktor:ktor-serialization-jackson:2.3.12")
    implementation("io.ktor:ktor-server-call-logging-jvm:2.3.12")
    implementation("ch.qos.logback:logback-classic:1.5.6")
    implementation("com.typesafe:config:1.4.3")
    implementation("com.rometools:rome:1.18.0")
    implementation("org.postgresql:postgresql:42.7.3")
    implementation("org.xerial:sqlite-jdbc:3.46.0.0")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-tests-jvm:2.3.12")
    testImplementation("io.ktor:ktor-client-content-negotiation:2.3.12")
    testImplementation("io.ktor:ktor-serialization-jackson:2.3.12")
}

tasks.withType<Jar> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

application {
    mainClass.set("com.mazekine.rss.shell.MainKt")
}

val shadowJar = tasks.withType<ShadowJar> {
    archiveBaseName.set("rss-shell")
    archiveClassifier.set("all")
    manifest {
        attributes(
            "Main-Class" to "com.mazekine.rss.shell.MainKt",
            "Implementation-Title" to "rss-shell",
            "Implementation-Version" to project.version
        )
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

/*
tasks.named<org.gradle.api.tasks.bundling.Zip>("distZip") {
    dependsOn(shadowJar)
}

tasks.named<org.gradle.api.tasks.bundling.Tar>("distTar") {
    dependsOn(shadowJar)
}

tasks.named<org.gradle.jvm.application.tasks.CreateStartScripts>("startScripts") {
    dependsOn(shadowJar)
}*/
