plugins {
    id("java")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "io.papermc.paper"
version = "1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.yaml:snakeyaml:2.3")
}

tasks {
    jar {
        manifest {
            attributes["Main-Class"] = "io.papermc.paper.PaperBootstrap"
        }
    }

    val fatJar by registering(com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar::class) {
        archiveBaseName.set("server")
        archiveClassifier.set("")
        archiveVersion.set("")
        manifest {
            attributes["Main-Class"] = "io.papermc.paper.PaperBootstrap"
        }
        from(sourceSets.main.get().output)
        configurations = listOf(project.configurations.runtimeClasspath.get())
    }

    build {
        dependsOn(fatJar)
    }
}
