plugins {
    `java`
    application
}

group = "io.papermc.paper"
version = "1.0"

repositories {
    mavenCentral()
}

dependencies {
    // ✅ YAML 解析库，用于加载 config.yml
    implementation("org.yaml:snakeyaml:2.2")

    // ✅ 可选：用于执行命令、日志输出
    implementation("org.slf4j:slf4j-simple:2.0.13")
}

application {
    // ✅ 主启动类
    mainClass.set("io.papermc.paper.PaperBootstrap")
}

// ✅ 打包所有依赖成单个可运行 JAR（Fat Jar）
tasks.jar {
    manifest {
        attributes["Main-Class"] = "io.papermc.paper.PaperBootstrap"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
}

// ✅ 编译选项优化
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

// ✅ 清理任务
tasks.register("cleanAll") {
    group = "build"
    description = "Clean all build and Gradle cache directories."
    doLast {
        delete("build", ".gradle")
    }
}
