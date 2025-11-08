plugins {
    `java`
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.yaml:snakeyaml:2.2")
    implementation("org.slf4j:slf4j-api:2.0.9")
}

application {
    mainClass.set("io.papermc.paper.PaperBootstrap")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "io.papermc.paper.PaperBootstrap"
        )
    }
}
