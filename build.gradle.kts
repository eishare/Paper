import org.gradle.jvm.tasks.Jar
import org.gradle.api.file.DuplicatesStrategy

plugins {
    java
    application
}

group = "io.papermc.paper"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // YAML 配置文件支持
    implementation("org.yaml:snakeyaml:2.2")

    // 其他必要依赖
    implementation("com.google.guava:guava:33.0.0-jre")
    implementation("org.slf4j:slf4j-simple:2.0.9")

    // 可选：你若需要运行 Sing-box 控制逻辑，可在此添加其他依赖
    // implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

application {
    // 设置主类
    mainClass.set("io.papermc.paper.PaperBootstrap")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

// 任务：生成一个可独立运行的 fat jar
tasks.register<Jar>("fatJar") {
    archiveBaseName.set("server")
    archiveVersion.set("")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))

    manifest {
        attributes(
            "Main-Class" to "io.papermc.paper.PaperBootstrap"
        )
    }

    // 将依赖打包进 jar
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
    from(sourceSets.main.get().output)

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.named("build") {
    dependsOn("fatJar")
}

// 可选：测试配置（若不需要可以删除）
tasks.test {
    useJUnitPlatform()
}
