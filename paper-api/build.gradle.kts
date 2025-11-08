plugins {
    `java-library`
    `maven-publish`
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper API 只需声明 Adventure 库即可满足构建
    api("net.kyori:adventure-api:4.14.0")
    api("net.kyori:adventure-text-serializer-ansi:4.14.0")
    api("net.kyori:adventure-text-serializer-plain:4.14.0")
    api("net.kyori:adventure-text-minimessage:4.14.0")

    // Bukkit SPI（非必须）
    compileOnly("org.spigotmc:spigot-api:1.21-R0.1-SNAPSHOT")
}

java {
    withSourcesJar()
}

publishing {
    publications.create<MavenPublication>("maven") {
        artifactId = "paper-api"
        from(components["java"])
    }
}
