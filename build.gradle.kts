plugins {
    id("java")
    id("org.gradlex.extra-java-module-info") version "1.9"
}

group = "gd.script"
version = "0.0.2"

val generatedVersionResources = layout.buildDirectory.dir("generated/resources/gdccVersion")
val runtimeLibDir = layout.buildDirectory.dir("libs/lib")
val launcherProjectDir = layout.projectDirectory.dir("src/launcher/zig")
val launcherInstallDir = layout.buildDirectory.dir("launcher/zig")
val launcherBinDir = launcherInstallDir.map { it.dir("bin") }

data class LauncherDistribution(
    val taskSuffix: String,
    val platform: String,
)

val launcherDistributions = listOf(
    LauncherDistribution("WindowsX86_64", "windows-x86_64"),
    LauncherDistribution("LinuxX86_64", "linux-x86_64"),
    LauncherDistribution("LinuxAarch64", "linux-aarch64"),
)

fun gitOutput(vararg args: String): String {
    return try {
        val process = ProcessBuilder(listOf("git") + args.toList())
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
        if (process.waitFor() == 0) output else "unknown"
    } catch (_: Exception) {
        "unknown"
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    compileOnly("org.jetbrains:annotations:24.0.1")
    testCompileOnly("org.jetbrains:annotations:24.0.1")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.freemarker:freemarker:2.3.34")
    implementation("com.github.abrarsyed.jastyle:jAstyle:1.3")
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("info.picocli:picocli:4.7.7")
    annotationProcessor("info.picocli:picocli-codegen:4.7.7")
    implementation("com.github.SuperIceCN:gdparser:0.5.3")
}

tasks.test {
    useJUnitPlatform()
}

val benchmark by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs Godot-backed GDScript benchmarks and writes the merged JSON report."

    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("gd.script.gdcc.test_suite.benchmark.GdScriptBenchmarkMain")
}

val buildAddonNative by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Builds the editor addon native library for the host platform and installs it into src/editor_addon."

    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("gd.script.gdcc.rpc.EditorAddonProjectInstaller")
    workingDir = rootDir
}

val buildAddonAllPlatform by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Builds the editor addon native libraries for Windows x86_64 and Linux x86_64/AArch64 and installs them into src/editor_addon."

    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("gd.script.gdcc.rpc.EditorAddonProjectInstaller")
    args("WINDOWS_X86_64", "LINUX_X86_64", "LINUX_AARCH64")
    workingDir = rootDir
}

val generateVersionResource by tasks.registering {
    val versionFile = generatedVersionResources.map { it.file("gdcc-version.properties") }
    outputs.file(versionFile)
    outputs.upToDateWhen { false }

    doLast {
        val outputFile = versionFile.get().asFile
        outputFile.parentFile.mkdirs()

        val branch = gitOutput("rev-parse", "--abbrev-ref", "HEAD").ifBlank { "unknown" }
        val commit = gitOutput("rev-parse", "--short", "HEAD").ifBlank { "unknown" }
        outputFile.writeText(
            """
                version=$version
                branch=$branch
                commit=$commit
                """.trimIndent() + System.lineSeparator(),
            Charsets.UTF_8
        )
    }
}

tasks.processResources {
    dependsOn(generateVersionResource)
}

val syncRuntimeLibs by tasks.registering(Sync::class) {
    group = "build"
    description = "Copies runtime dependencies next to the generated jar."

    from(configurations.runtimeClasspath)
    into(runtimeLibDir)
}

val buildZigLauncher by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the default native gdcc launchers with Zig."

    dependsOn(tasks.jar)
    workingDir = launcherProjectDir.asFile
    inputs.files(fileTree(launcherProjectDir) {
        exclude(".zig-cache/**", "zig-out/**")
    })
    outputs.dir(launcherBinDir)

    // Zig owns the native target matrix; Gradle only passes the install prefix.
    doFirst {
        delete(launcherInstallDir)
        commandLine(
            "zig",
            "build",
            "--release=small",
            "--prefix",
            launcherInstallDir.get().asFile.absolutePath
        )
    }
}

tasks.jar {
    dependsOn(syncRuntimeLibs)
    manifest {
        attributes(
            "Main-Class" to "gd.script.gdcc.Main",
            "Class-Path" to configurations.runtimeClasspath.get()
                .files
                .sortedBy { it.name }
                .joinToString(" ") { "lib/${it.name}" }
        )
    }
}

fun registerLauncherDistribution(distribution: LauncherDistribution) = tasks.register<Zip>(
    "packageDistribution${distribution.taskSuffix}"
) {
    group = "distribution"
    description = "Packages the ${distribution.platform} gdcc distribution."

    dependsOn(tasks.jar, syncRuntimeLibs, buildZigLauncher)
    archiveBaseName.set(project.name)
    archiveVersion.set(project.version.toString())
    archiveClassifier.set(distribution.platform)
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))

    from(tasks.jar.flatMap { it.archiveFile })
    from(runtimeLibDir) {
        into("lib")
    }
    from(launcherBinDir.map { it.dir(distribution.platform) }) {
        // Keep the selected platform launcher at the archive root next to the jar.
        filePermissions {
            unix("0755")
        }
        dirPermissions {
            unix("0755")
        }
    }
}

val platformDistributionTasks = launcherDistributions.map(::registerLauncherDistribution)

val packageDistribution by tasks.registering {
    group = "distribution"
    description = "Packages all platform-specific gdcc distributions."

    dependsOn(platformDistributionTasks)
}

sourceSets {
    main {
        resources {
            srcDir("src/main/c/codegen")
            srcDir(generatedVersionResources)
        }
    }
    test {
        resources {
            srcDir("src/test/test_suite")
        }
    }
}

extraJavaModuleInfo {
    automaticModule("jAstyle-1.3.jar", "jAstyle")
    automaticModule("tree-sitter-0.26.3.jar", "tree.sitter")
}
