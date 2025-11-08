import org.gradle.jvm.tasks.Jar
import org.gradle.api.file.DuplicatesStrategy

tasks.register<Jar>("fatJar") {
    archiveBaseName.set("server")
    archiveVersion.set("")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))

    // 打包运行依赖
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })

    // 包含主类定义
    manifest {
        attributes(
            "Main-Class" to "io.papermc.paper.PaperBootstrap"
        )
    }

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.named("build") {
    dependsOn("fatJar")
}
