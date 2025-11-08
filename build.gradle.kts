plugins {
    id("java")
}

repositories {
    mavenCentral()
}

dependencies {
    // YAML 配置
    implementation("org.yaml:snakeyaml:2.2")

    // ✅ BouncyCastle（加密/证书）
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    // ✅ 日志支持
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

    // ✅ 排除签名文件（防止 Invalid signature digest 错误）
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")

    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })

    with(tasks.jar.get())
}
