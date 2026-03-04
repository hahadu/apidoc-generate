plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.0"
    id("maven-publish")
}

group = "com.wokfoy"
version = "1.0.0"

kotlin {
    jvmToolchain(21)
}

val ktor_version: String by project

dependencies {
    implementation("io.ktor:ktor-server-core:$ktor_version")
    implementation("io.ktor:ktor-server-host-common:$ktor_version")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.typesafe:config:1.4.3")
    implementation("io.github.classgraph:classgraph:4.8.172")
    implementation(project(":"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = "com.wokfoy"
            artifactId = "ktor-apidoc-plugin"
            version = "1.0.0"
        }
    }
    repositories {
        maven {
            name = "local"
            url = uri(layout.buildDirectory.dir("repo"))
        }
    }
}

tasks.register<JavaExec>("generate") {
    group = "apidoc"
    description = "Generate API docs and optionally submit to Postman"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.wokfoy.apidoc.ApidocGenerateMainKt")
}
