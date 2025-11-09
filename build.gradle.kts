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

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks {
    // ✅ 确保主类写入 Manifest
    jar {
        manifest {
            attributes["Main-Class"] = "io.papermc.paper.PaperBootstrap"
        }
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    // ✅ 正确创建 fatJar，包含所有依赖
    val fatJar by registering(com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar::class) {
        archiveBaseName.set("server")
        archiveClassifier.set("") // 不带 classifier
        archiveVersion.set("")    // 不带版本号
        manifest {
            attributes["Main-Class"] = "io.papermc.paper.PaperBootstrap"
        }
        from(sourceSets.main.get().output)
        configurations = listOf(project.configurations.runtimeClasspath.get())
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    build {
        dependsOn(fatJar)
    }
}
