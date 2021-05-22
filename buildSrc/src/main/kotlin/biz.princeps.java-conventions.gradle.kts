plugins {
    `java-library`
    `maven-publish`
}

group = "biz.princeps"
version = "2.0"

repositories {
    mavenCentral()
    // JitPack
    maven { url = uri("https://jitpack.io") }
    // Spigot
    maven { url = uri("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") }
    // EldoUtilitites & Landlord
    maven { url = uri("https://eldonexus.de/repository/maven-releases/") }
    // CodeMc-public
    maven { url = uri("https://repo.codemc.org/repository/maven-public/") }
    // Dynmap
    maven { url = uri("https://repo.mikeprimm.com/") }
}

allprojects {
    java {
        withSourcesJar()
        withJavadocJar()
        sourceCompatibility = JavaVersion.VERSION_1_8
    }
}

tasks {
    publish {
        dependsOn(build)
    }

    compileJava {
        options.encoding = "UTF-8"
    }

    test {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}
