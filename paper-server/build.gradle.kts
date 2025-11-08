import io.papermc.fill.model.BuildChannel
import io.papermc.paperweight.attribute.DevBundleOutput
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.data.FileEntry
import paper.libs.com.google.gson.annotations.SerializedName
import java.time.Instant
import kotlin.io.path.readText

plugins {
    `java-library`
    `maven-publish`
    idea
    id("io.papermc.paperweight.core")
    id("io.papermc.fill.gradle") version "1.0.7"
}

val paperMavenPublicUrl = "https://repo.papermc.io/repository/maven-public/"

repositories {
    mavenCentral()
    maven(paperMavenPublicUrl)
}

dependencies {
    mache("io.papermc:mache:1.21.8+build.2")
    paperclip("io.papermc:paperclip:3.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

paperweight {
    minecraftVersion = providers.gradleProperty("mcVersion")
    gitFilePatches = false

    spigot {
        enabled = true
        buildDataRef = "436eac9815c211be1a2a6ca0702615f995e81c44"
        packageVersion = "v1_21_R5" // also needs to be updated in MappingEnvironment
    }

    reobfPackagesToFix.addAll(
        "co.aikar.timings",
        "com.destroystokyo.paper",
        "com.mojang",
        "io.papermc.paper",
        "ca.spottedleaf",
        "net.kyori.adventure.bossbar",
        "net.minecraft",
        "org.bukkit.craftbukkit",
        "org.spigotmc",
    )
}

tasks.generateDevelopmentBundle {
    libraryRepositories.addAll(
        "https://repo.maven.apache.org/maven2/",
        paperMavenPublicUrl,
    )
}

// ======================================================
// 自定义：Paper server 独立构建兼容调整
// ======================================================
abstract class Services {
    @get:Inject
    abstract val softwareComponentFactory: SoftwareComponentFactory
    @get:Inject
    abstract val archiveOperations: ArchiveOperations
}
val services = objects.newInstance<Services>()

if (project.providers.gradleProperty("publishDevBundle").isPresent) {
    val devBundleComponent = services.softwareComponentFactory.adhoc("devBundle")
    components.add(devBundleComponent)

    val devBundle = configurations.consumable("devBundle") {
        attributes.attribute(DevBundleOutput.ATTRIBUTE, objects.named(DevBundleOutput.ZIP))
        outgoing.artifact(tasks.generateDevelopmentBundle.flatMap { it.devBundleFile })
    }
    devBundleComponent.addVariantsFromConfiguration(devBundle.get()) {}

    val runtime = configurations.consumable("serverRuntimeClasspath") {
        attributes.attribute(DevBundleOutput.ATTRIBUTE, objects.named(DevBundleOutput.SERVER_DEPENDENCIES))
        attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        extendsFrom(configurations.runtimeClasspath.get())
    }
    devBundleComponent.addVariantsFromConfiguration(runtime.get()) {
        mapToMavenScope("runtime")
    }

    val compile = configurations.consumable("serverCompileClasspath") {
        attributes.attribute(DevBundleOutput.ATTRIBUTE, objects.named(DevBundleOutput.SERVER_DEPENDENCIES))
        attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
        extendsFrom(configurations.compileClasspath.get())
    }
    devBundleComponent.addVariantsFromConfiguration(compile.get()) {
        mapToMavenScope("compile")
    }

    tasks.withType(GenerateMavenPom::class).configureEach {
        doLast {
            val text = destination.readText()
            destination.writeText(
                text.substringBefore("<dependencies>") + text.substringAfter("</dependencies>")
            )
        }
    }

    publishing {
        publications.create<MavenPublication>("devBundle") {
            artifactId = "dev-bundle"
            from(devBundleComponent)
        }
    }
}

// ======================================================
// 依赖配置区域（修改重点）
// ======================================================
val log4jPlugins = sourceSets.create("log4jPlugins")
configurations.named(log4jPlugins.compileClasspathConfigurationName) {
    extendsFrom(configurations.compileClasspath.get())
}
val alsoShade: Configuration by configurations.creating
val runtimeConfiguration by configurations.consumable("runtimeConfiguration") {
    attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
}

// mockito agent 配置
val mockitoAgent = configurations.register("mockitoAgent")
abstract class MockitoAgentProvider : CommandLineArgumentProvider {
    @get:CompileClasspath
    abstract val fileCollection: ConfigurableFileCollection
    override fun asArguments(): Iterable<String> =
        listOf("-javaagent:" + fileCollection.files.single().absolutePath)
}

dependencies {
    // ❌ 删除：implementation(project(":paper-api"))
    // ✅ 改为直接引入 API 依赖
    implementation("net.kyori:adventure-api:4.14.0")
    implementation("net.kyori:adventure-text-serializer-ansi:4.14.0")
    implementation("net.kyori:adventure-text-serializer-plain:4.14.0")
    implementation("net.kyori:adventure-text-minimessage:4.14.0")

    implementation("ca.spottedleaf:concurrentutil:0.0.3")
    implementation("org.jline:jline-terminal-ffm:3.27.1")
    implementation("org.jline:jline-terminal-jni:3.27.1")
    implementation("net.minecrell:terminalconsoleappender:1.3.0")
    implementation("org.apache.logging.log4j:log4j-core:2.24.1")
    log4jPlugins.annotationProcessorConfigurationName("org.apache.logging.log4j:log4j-core:2.24.1")
    runtimeOnly(log4jPlugins.output)
    alsoShade(log4jPlugins.output)
    implementation("com.velocitypowered:velocity-native:3.4.0-SNAPSHOT") { isTransitive = false }
    implementation("io.netty:netty-codec-haproxy:4.1.118.Final")
    implementation("org.apache.logging.log4j:log4j-iostreams:2.24.1")
    implementation("org.ow2.asm:asm-commons:9.8")
    implementation("org.spongepowered:configurate-yaml:4.2.0")

    runtimeOnly("commons-lang:commons-lang:2.6")
    runtimeOnly("org.xerial:sqlite-jdbc:3.49.1.0")
    runtimeOnly("com.mysql:mysql-connector-j:9.2.0")
    runtimeOnly("com.lmax:disruptor:3.4.4")
    implementation("com.googlecode.json-simple:json-simple:1.1.1") { isTransitive = false }

    testImplementation("io.github.classgraph:classgraph:4.8.179")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testImplementation("org.junit.platform:junit-platform-suite-engine:1.12.2")
    testImplementation("org.hamcrest:hamcrest:2.2")
    testImplementation("org.mockito:mockito-core:5.14.1")
    mockitoAgent("org.mockito:mockito-core:5.14.1") { isTransitive = false }
    testImplementation("org.ow2.asm:asm-tree:9.8")
    testImplementation("org.junit-pioneer:junit-pioneer:2.2.0")

    implementation("net.neoforged:srgutils:1.0.9")
    implementation("net.neoforged:AutoRenamingTool:2.0.3")

    val reflectionRewriterVersion = "0.0.3"
    implementation("io.papermc:reflection-rewriter:$reflectionRewriterVersion")
    implementation("io.papermc:reflection-rewriter-runtime:$reflectionRewriterVersion")
    implementation("io.papermc:reflection-rewriter-proxy-generator:$reflectionRewriterVersion")

    implementation("me.lucko:spark-api:0.1-20240720.200737-2")
    implementation("me.lucko:spark-paper:1.10.133-20250413.112336-1")
}

// ======================================================
// 任务配置（原封不动保留）
// ======================================================
tasks.jar {
    manifest {
        val git = Git(rootProject.layout.projectDirectory.path)
        val mcVersion = rootProject.providers.gradleProperty("mcVersion").get()
        val build = System.getenv("BUILD_NUMBER") ?: null
        val buildTime = if (build != null) Instant.now() else Instant.EPOCH
        val gitHash = git.exec(providers, "rev-parse", "--short=7", "HEAD").get().trim()
        val implementationVersion = "$mcVersion-${build ?: "DEV"}-$gitHash"
        val date = git.exec(providers, "show", "-s", "--format=%ci", gitHash).get().trim()
        val gitBranch = git.exec(providers, "rev-parse", "--abbrev-ref", "HEAD").get().trim()
        attributes(
            "Main-Class" to "org.bukkit.craftbukkit.Main",
            "Implementation-Title" to "Paper",
            "Implementation-Version" to implementationVersion,
            "Implementation-Vendor" to date,
            "Specification-Title" to "Paper",
            "Specification-Version" to project.version,
            "Specification-Vendor" to "Paper Team",
            "Brand-Id" to "papermc:paper",
            "Brand-Name" to "Paper",
            "Build-Number" to (build ?: ""),
            "Build-Time" to buildTime.toString(),
            "Git-Branch" to gitBranch,
            "Git-Commit" to gitHash,
        )
        for (tld in setOf("net", "com", "org")) {
            attributes("$tld/bukkit", "Sealed" to true)
        }
    }
}

tasks.compileTestJava { options.compilerArgs.add("-parameters") }
tasks.withType<JavaCompile>().configureEach { options.forkOptions.memoryMaximumSize = "1G" }

val scanJarForBadCalls by tasks.registering(io.papermc.paperweight.tasks.ScanJarForBadCalls::class) {
    badAnnotations.add("Lio/papermc/paper/annotation/DoNotUse;")
    jarToScan.set(tasks.jar.flatMap { it.archiveFile })
    classpath.from(configurations.compileClasspath)
}
tasks.check { dependsOn(scanJarForBadCalls) }

tasks.jar {
    val archiveOperations = services.archiveOperations
    from(alsoShade.elements.map {
        it.map { f -> if (f.asFile.isFile) archiveOperations.zipTree(f.asFile) else f.asFile }
    })
}

tasks.test {
    include("**/**TestSuite.class")
    workingDir = temporaryDir
    useJUnitPlatform {
        forkEvery = 1
        excludeTags("Slow")
    }
    val provider = objects.newInstance<MockitoAgentProvider>()
    provider.fileCollection.from(mockitoAgent)
    jvmArgumentProviders.add(provider)
}

val generatedDir: java.nio.file.Path = layout.projectDirectory.dir("src/generated/java").asFile.toPath()
idea { module { generatedSourceDirs.add(generatedDir.toFile()) } }
sourceSets { main { java { srcDir(generatedDir) } } }

if (providers.gradleProperty("updatingMinecraft").getOrElse("false").toBoolean()) {
    val scanJarForOldGeneratedCode by tasks.registering(io.papermc.paperweight.tasks.ScanJarForOldGeneratedCode::class) {
        mcVersion.set(providers.gradleProperty("mcVersion"))
        annotation.set("Lio/papermc/paper/generated/GeneratedFrom;")
        jarToScan.set(tasks.jar.flatMap { it.archiveFile })
        classpath.from(configurations.compileClasspath)
    }
    tasks.check { dependsOn(scanJarForOldGeneratedCode) }
}

// 启动任务配置（原逻辑不动）
fun TaskContainer.registerRunTask(name: String, block: JavaExec.() -> Unit): TaskProvider<JavaExec> = register<JavaExec>(name) {
    group = "runs"
    mainClass.set("org.bukkit.craftbukkit.Main")
    standardInput = System.`in`
    workingDir = rootProject.layout.projectDirectory.dir(providers.gradleProperty("paper.runWorkDir").getOrElse("run")).asFile
    javaLauncher.set(project.javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
        vendor.set(JvmVendorSpec.JETBRAINS)
    })
    jvmArgs("-XX:+AllowEnhancedClassRedefinition")
    args("--nogui")
    systemProperty("net.kyori.adventure.text.warnWhenLegacyFormattingDetected", true)
    systemProperty("io.papermc.paper.suppress.sout.nags", true)
    val memoryGb = providers.gradleProperty("paper.runMemoryGb").getOrElse("2")
    minHeapSize = "${memoryGb}G"
    maxHeapSize = "${memoryGb}G"
    doFirst { workingDir.mkdirs() }
    block(this)
}

tasks.registerRunTask("runServer") {
    description = "Spin up a test server from the Mojang mapped server jar"
    classpath(tasks.includeMappings.flatMap { it.outputJar })
    classpath(configurations.runtimeClasspath)
}

tasks.registerRunTask("runReobfServer") {
    description = "Spin up a test server from the reobfJar output jar"
    classpath(tasks.reobfJar.flatMap { it.outputJar })
    classpath(configurations.runtimeClasspath)
}

tasks.registerRunTask("runDevServer") {
    description = "Spin up a test server without assembling a jar"
    classpath(sourceSets.main.map { it.runtimeClasspath })
}

fill {
    project("paper")
    versionFamily(paperweight.minecraftVersion.map { it.split(".", "-").takeWhile { it.toIntOrNull() != null }.take(2).joinToString(".") })
    version(paperweight.minecraftVersion)
    build {
        channel = BuildChannel.STABLE
        downloads {
            register("server:default") {
                file = tasks.createMojmapPaperclipJar.flatMap { it.outputZip }
                nameResolver.set { project, _, version, build -> "$project-$version-$build.jar" }
            }
        }
    }
}
