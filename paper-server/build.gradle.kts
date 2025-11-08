plugins {
    `java`
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("org.yaml:snakeyaml:2.2")
    implementation("net.kyori:adventure-api:4.14.0")
    implementation("net.kyori:adventure-text-serializer-ansi:4.14.0")
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
