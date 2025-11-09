plugins {
    id("java")
    id("com.github.johnrengelman.shadow") version "8.1.1" // 打包fatJar
}

group = "io.papermc.paper"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // YAML 配置文件解析
    implementation("org.yaml:snakeyaml:2.2")

    // 可选：文件操作/IO 工具类（非必须）
    implementation("commons-io:commons-io:2.16.1")

    // （如需 JSON 序列化支持，可加 Jackson）
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")
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

tasks.jar {
    manifest {
        attributes["Main-Class"] = "io.papermc.paper.PaperBootstrap"
    }
}

tasks.register<Jar>("fatJar") {
    group = "build"
    description = "打包包含依赖的可执行 jar"
    manifest {
        attributes["Main-Class"] = "io.papermc.paper.PaperBootstrap"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    with(tasks.jar.get() as CopySpec)
}

tasks.build {
    dependsOn("fatJar")
}
