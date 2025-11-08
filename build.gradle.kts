plugins {
    id("java")
}

repositories {
    mavenCentral()
}

dependencies {
    // YAML 配置解析
    implementation("org.yaml:snakeyaml:2.2")

    // ✅ BouncyCastle 安全与证书生成支持库
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    // 日志支持（SLF4J）
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("org.slf4j:slf4j-simple:2.0.13")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "io.papermc.paper.PaperBootstrap"
    }
}

tasks.register<Jar>("fatJar") {
    archiveBaseName.set("server")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "io.papermc.paper.PaperBootstrap"
    }
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
    with(tasks.jar.get())
}
