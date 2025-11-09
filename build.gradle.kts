plugins {
    id("java")
    id("com.github.johnrengelman.shadow") version "8.1.1" // fatJar 打包插件
}

group = "io.papermc.paper"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // 解析 YAML 配置文件
    implementation("org.yaml:snakeyaml:2.2")

    // JSON 解析工具
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")

    // Apache Commons IO 工具（处理文件）
    implementation("commons-io:commons-io:2.16.1")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withJavadocJar()
    withSourcesJar()
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

// 打包时指定主类为 PaperBootstrap
tasks.jar {
    manifest {
        attributes["Main-Class"] = "io.papermc.paper.PaperBootstrap"
    }
}

// fatJar 任务 —— 打包依赖并生成固定 server.jar
tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Builds a standalone executable jar (server.jar)"
    archiveBaseName.set("server")
    archiveClassifier.set("")
    archiveVersion.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes["Main-Class"] = "io.papermc.paper.PaperBootstrap"
    }

    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    with(tasks.jar.get() as CopySpec)
}

tasks.build {
    dependsOn("fatJar")
}
