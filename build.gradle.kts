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
    implementation("org.yaml:snakeyaml:2.2")
    implementation("org.slf4j:slf4j-simple:2.0.13")
}

application {
    mainClass.set("io.papermc.paper.PaperBootstrap")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "io.papermc.paper.PaperBootstrap"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
}
