// 强制将 jar 命名为 server.jar，并把运行时依赖打包进去（fat jar）
tasks.jar {
    // 输出文件名固定为 server.jar（不含版本号）
    archiveBaseName.set("server")
    archiveVersion.set("")

    // Main-Class 清单项（启动类）
    manifest {
        attributes["Main-Class"] = "io.papermc.paper.PaperBootstrap"
    }

    // 若有依赖则打包到 jar 中
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
}
