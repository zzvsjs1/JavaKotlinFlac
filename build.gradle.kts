import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.gradle.api.publish.maven.tasks.PublishToMavenLocal
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.jvm.tasks.Jar
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.jar.Manifest
import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "2.0.20"
    id("org.jetbrains.dokka-javadoc") version "2.2.0"
    `maven-publish`
}

group = "org.zzvsjs"
version = "0.1.0-SNAPSHOT"

/*
 * Native compilation uses the Gradle daemon's java.home for JNI headers, so
 * fail during configuration if the build is not running on a supported JDK.
 */
val requiredBuildJavaVersion = JavaVersion.VERSION_21
require(JavaVersion.current().isCompatibleWith(requiredBuildJavaVersion)) {
    "jflac requires JDK 21 or newer to run the Gradle build; " +
        "Gradle is using Java ${JavaVersion.current()} from ${System.getProperty("java.home")}."
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.14.4")
}

data class NativeTarget(
    val id: String,
    val jniIncludeDirectory: String,
    val flacPackagedFileName: String,
    val flacBuildRelativePath: String,
    val jniFileName: String,
    val oggStaticRelativePath: String
)

val hostOsName = System.getProperty("os.name")
val hostArchitectureName = System.getProperty("os.arch")
val isWindows = hostOsName.startsWith("Windows", ignoreCase = true)
val isLinux = hostOsName.equals("Linux", ignoreCase = true)
val isMacOs = hostOsName.contains("Mac", ignoreCase = true) || hostOsName.contains("Darwin", ignoreCase = true)
val hostArchitecture = when (hostArchitectureName.lowercase()) {
    "amd64", "x86_64", "x64" -> "x86_64"
    "aarch64", "arm64" -> "aarch64"
    else -> hostArchitectureName.lowercase()
}
val supportedNativeTargets = listOf(
    NativeTarget(
        id = "windows-x86_64",
        jniIncludeDirectory = "win32",
        flacPackagedFileName = "FLAC.dll",
        flacBuildRelativePath = "objs/FLAC.dll",
        jniFileName = "jflac-jni.dll",
        oggStaticRelativePath = "lib/ogg.lib"
    ),
    NativeTarget(
        id = "linux-x86_64",
        jniIncludeDirectory = "linux",
        flacPackagedFileName = "libFLAC.so.14",
        flacBuildRelativePath = "lib/libFLAC.so.14.0.0",
        jniFileName = "libjflac-jni.so",
        oggStaticRelativePath = "lib/libogg.a"
    ),
    NativeTarget(
        id = "macos-x86_64",
        jniIncludeDirectory = "darwin",
        flacPackagedFileName = "libFLAC.14.dylib",
        flacBuildRelativePath = "lib/libFLAC.14.0.0.dylib",
        jniFileName = "libjflac-jni.dylib",
        oggStaticRelativePath = "lib/libogg.a"
    ),
    NativeTarget(
        id = "macos-aarch64",
        jniIncludeDirectory = "darwin",
        flacPackagedFileName = "libFLAC.14.dylib",
        flacBuildRelativePath = "lib/libFLAC.14.0.0.dylib",
        jniFileName = "libjflac-jni.dylib",
        oggStaticRelativePath = "lib/libogg.a"
    )
)
val detectedNativePlatformId = when {
    isWindows -> "windows-$hostArchitecture"
    isLinux -> "linux-$hostArchitecture"
    isMacOs -> "macos-$hostArchitecture"
    else -> "unsupported-$hostArchitecture"
}
val nativeTarget = supportedNativeTargets.firstOrNull { target -> target.id == detectedNativePlatformId }
val activeNativeTarget = nativeTarget ?: NativeTarget(
    id = "unsupported-$hostArchitecture",
    jniIncludeDirectory = "unsupported",
    flacPackagedFileName = "unsupported-libFLAC",
    flacBuildRelativePath = "unsupported-libFLAC",
    jniFileName = "unsupported-jflac-jni",
    oggStaticRelativePath = "unsupported-libogg"
)

val nativeBuildDir = layout.buildDirectory.dir("native")
val nativeOutputDir = nativeBuildDir.map { it.dir("bin") }
val nativeLibrary = nativeOutputDir.map { it.file(activeNativeTarget.jniFileName) }
val nativeCompileCommands = nativeBuildDir.map { it.file("compile_commands.json") }
val rootCompileCommands = layout.projectDirectory.file("compile_commands.json")
val jniSourceFile = layout.projectDirectory.file("native/src/jflac_jni.c")
val generatedJniHeadersDir = layout.buildDirectory.dir("generated/sources/headers/java/main")
val generatedNativeBindingsHeader = generatedJniHeadersDir.map {
    it.file("org_zzvsjs_jflac_internal_NativeBindings.h")
}
val generatedResourcesDir = layout.buildDirectory.dir("generated-resources/main")
/*
 * A normal developer build produces and packages the current host runtime.
 * Release CI supplies the four independently built resource trees here so one
 * Maven artefact can serve every supported platform without cross-compiling.
 */
val suppliedNativeBundleDirectory = providers.gradleProperty("jflac.nativeBundleDirectory")
    .orNull
    ?.let(::file)
val packagedNativeTargets = if (suppliedNativeBundleDirectory == null) {
    listOfNotNull(nativeTarget)
} else {
    supportedNativeTargets
}
val nativePlatformId = activeNativeTarget.id
val nativeResourceRoot = "META-INF/native/$nativePlatformId"

val flacVersion = "1.5.0"
val flacApiVersionCurrent = 14
val flacApiVersionRevision = 0
val flacApiVersionAge = 0
val flacSourceUrl = "https://github.com/xiph/flac/releases/download/$flacVersion/flac-$flacVersion.tar.xz"
val flacSourceSha256 = "f2c1c76592a82ffff8413ba3c4a1299b6c7ab06c734dee03fd88630485c2b920"
val flacArchive = layout.buildDirectory.file("downloads/flac-$flacVersion.tar.xz")
val flacSourceParentDir = layout.buildDirectory.dir("flac-source")
val flacSourceDir = flacSourceParentDir.map { it.dir("flac-$flacVersion") }
val flacVendorPatches = listOf(
    layout.projectDirectory.file("native/patches/flac-1.5.0-preserve-vorbis-vendor.patch"),
    layout.projectDirectory.file("native/patches/flac-1.5.0-close-decoder-file-after-init-failure.patch")
)
val flacBuildDir = layout.buildDirectory.dir("flac-native")
val flacLibrary = flacBuildDir.map { it.file(activeNativeTarget.flacBuildRelativePath) }
val flacImportLib = flacBuildDir.map { it.file("src/libFLAC/FLAC.lib") }
val flacInteropBuildDir = layout.buildDirectory.dir("flac-interop-tools")
val flacInteropExecutable = flacInteropBuildDir.map { buildDirectory ->
    if (isWindows) buildDirectory.file("objs/flac.exe") else buildDirectory.file("src/flac/flac")
}
val metaflacInteropExecutable = flacInteropBuildDir.map { buildDirectory ->
    if (isWindows) buildDirectory.file("objs/metaflac.exe") else buildDirectory.file("src/metaflac/metaflac")
}
val oggVersion = "1.3.6"
val oggSourceUrl = "https://downloads.xiph.org/releases/ogg/libogg-$oggVersion.tar.xz"
val oggSourceSha256 = "5c8253428e181840cd20d41f3ca16557a9cc04bad4a3d04cce84808677fa1061"
val oggArchive = layout.buildDirectory.file("downloads/libogg-$oggVersion.tar.xz")
val oggSourceParentDir = layout.buildDirectory.dir("ogg-source")
val oggSourceDir = oggSourceParentDir.map { it.dir("libogg-$oggVersion") }
val oggBuildDir = layout.buildDirectory.dir("ogg-native")
val oggBuildIncludeDir = oggBuildDir.map { it.dir("include") }
val oggGeneratedConfigHeader = oggBuildIncludeDir.map { it.file("ogg/config_types.h") }
val oggStaticLibrary = oggBuildDir.map { it.file(activeNativeTarget.oggStaticRelativePath) }
val nativeSanitizerOggBuildDir = layout.buildDirectory.dir("ogg-native-sanitizer")
val nativeSanitizerOggIncludeDir = nativeSanitizerOggBuildDir.map { it.dir("include") }
val nativeSanitizerOggConfigHeader = nativeSanitizerOggIncludeDir.map { it.file("ogg/config_types.h") }
val nativeSanitizerOggLibrary = nativeSanitizerOggBuildDir.map { it.file("lib/libogg.a") }
val nativeSanitizerFlacBuildDir = layout.buildDirectory.dir("flac-native-sanitizer")
val nativeSanitizerFlacLibrary = nativeSanitizerFlacBuildDir.map { it.file("lib/libFLAC.a") }
val nativeSanitizerSharedFlacBuildDir = layout.buildDirectory.dir("flac-native-sanitizer-shared")
val nativeSanitizerSharedFlacLibrary = nativeSanitizerSharedFlacBuildDir.map {
    it.file("lib/libFLAC.so.$flacApiVersionCurrent.0.0")
}
val nativeSanitizerBuildDir = layout.buildDirectory.dir("native-sanitizer")
val nativeSanitizerLibrary = nativeSanitizerBuildDir.map { it.file("bin/libjflac-jni.so") }
val nativeSanitizerResourcesDir = layout.buildDirectory.dir("native-sanitizer-resources")
val nativeSanitizerPlatformResourcesDir = nativeSanitizerResourcesDir.map {
    it.dir("META-INF/native/linux-x86_64")
}
val decoderFuzzExecutable = nativeSanitizerBuildDir.map { it.file("fuzz/jflac-decoder-fuzz") }
val metadataFuzzExecutable = nativeSanitizerBuildDir.map { it.file("fuzz/jflac-metadata-fuzz") }
val nativeFuzzSeedDir = layout.projectDirectory.dir("native/fuzz/seeds")
val nativeFuzzCorpusDir = layout.buildDirectory.dir("native-fuzz-corpus")
val decoderFuzzCorpusDir = nativeFuzzCorpusDir.map { it.dir("decoder") }
val metadataFuzzCorpusDir = nativeFuzzCorpusDir.map { it.dir("metadata") }
val nativeFuzzArtifactDir = layout.buildDirectory.dir("native-fuzz-artifacts")
val nativeSanitizerCompileFlags = listOf(
    "-fsanitize=address,undefined",
    "-fno-omit-frame-pointer",
    "-fno-sanitize-recover=all"
).joinToString(" ")
val nativeSanitizerLinkFlags = listOf(
    "-fsanitize=address,undefined",
    "-fno-sanitize-recover=all"
).joinToString(" ")
val nativeSanitizerSupported = isLinux && hostArchitecture == "x86_64"

data class NativeFuzzSeedExpectation(
    val byteLength: Int,
    val sha256: String,
    val containerMarker: String
)

val nativeFuzzSeedExpectations = mapOf(
    "native-metadata" to NativeFuzzSeedExpectation(
        byteLength = 54,
        sha256 = "27e801cfb44c4739ab90531111081a059fa95f9aaa8300b9268d217460a14a78",
        containerMarker = "fLaC"
    ),
    "native-truncated-block" to NativeFuzzSeedExpectation(
        byteLength = 9,
        sha256 = "b54351ed57b930558026f5ab04e23f028c9be6a1430b3bc4cc8896783d099ca4",
        containerMarker = "fLaC"
    ),
    "ogg-flac" to NativeFuzzSeedExpectation(
        byteLength = 313,
        sha256 = "cde48e15591c66004e50444a3625aa7ec589e9cf491d209b43314d2876b1c5be",
        containerMarker = "OggS"
    )
)
val javaHomeDir = file(System.getProperty("java.home"))
val jniIncludeDir = javaHomeDir.resolve("include")
val jniPlatformIncludeDir = jniIncludeDir.resolve(activeNativeTarget.jniIncludeDirectory)

val msvcRuntimeLibrary = "MultiThreaded"
val msvcRuntimeCmakeOptions = listOf(
    "-DCMAKE_MSVC_RUNTIME_LIBRARY=$msvcRuntimeLibrary"
)
val legacyMsvcRuntimeCmakeOptions = listOf(
    "-DCMAKE_POLICY_DEFAULT_CMP0091=NEW",
    *msvcRuntimeCmakeOptions.toTypedArray()
)

fun File.cmdPath(): String = absolutePath.replace("/", "\\")

fun File.displayPath(): String =
    relativeToOrSelf(projectDir).invariantSeparatorsPath

fun findFirstExistingFile(vararg paths: String): File? =
    paths.asSequence().map(::file).firstOrNull(File::exists)

fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

fun sha256(file: File): String = sha256(file.readBytes())

fun programFilesPath(vararg segments: String): String? =
    listOfNotNull(
        System.getenv("ProgramFiles(x86)"),
        System.getenv("ProgramFiles")
    ).asSequence()
        .distinct()
        .map { base -> file(listOf(base, *segments).joinToString(File.separator)) }
        .firstOrNull(File::exists)
        ?.path

fun findVisualStudioInstallDir(): File? {
    val vswhere = programFilesPath("Microsoft Visual Studio", "Installer", "vswhere.exe")
        ?.let(::file)
        ?: return null

    val output = ByteArrayOutputStream()
    exec {
        commandLine(
            vswhere.absolutePath,
            "-latest",
            "-products",
            "*",
            "-requires",
            "Microsoft.VisualStudio.Component.VC.Tools.x86.x64",
            "-property",
            "installationPath"
        )
        standardOutput = output
        isIgnoreExitValue = true
    }

    val installationPath = output.toString().trim()
    return installationPath.takeIf { it.isNotEmpty() }?.let(::file)
}

fun findLatestMsvcTool(visualStudioInstallDir: File?, executableName: String): File? {
    val toolsRoot = visualStudioInstallDir?.resolve("VC/Tools/MSVC") ?: return null
    val latestToolset = toolsRoot.listFiles()
        ?.filter(File::isDirectory)
        ?.maxByOrNull(File::getName)
        ?: return null
    return latestToolset.resolve("bin/Hostx64/x64/$executableName").takeIf(File::exists)
}

val visualStudioInstallDir by lazy { findVisualStudioInstallDir() }
val vcvars64 by lazy {
    visualStudioInstallDir
        ?.resolve("VC/Auxiliary/Build/vcvars64.bat")
        ?.takeIf(File::exists)
}
val ninjaExe by lazy {
    visualStudioInstallDir
        ?.resolve("Common7/IDE/CommonExtensions/Microsoft/CMake/Ninja/ninja.exe")
        ?.takeIf(File::exists)
}
val dumpbinExe by lazy { findLatestMsvcTool(visualStudioInstallDir, "dumpbin.exe") }

/*
 * The JNI shim's RESOLVE calls are the source of truth for required libFLAC
 * exports. Matching every invocation separately makes a newly formatted or
 * malformed call fail loudly instead of silently weakening the export check.
 */
val flacResolveInvocationPattern = Regex("""(?m)^[ \t]*RESOLVE\s*\(""")
val flacResolvedSymbolPattern = Regex(
    """(?m)^[ \t]*RESOLVE\s*\(\s*[A-Za-z_][A-Za-z0-9_]*\s*,\s*(FLAC(?:__|_API_)[A-Za-z0-9_]+)\s*\)\s*;"""
)

/*
 * dumpbin emits one export per row as "ordinal hint RVA name". Parsing that
 * final column into a set is important: a substring search would incorrectly
 * accept FLAC__metadata_chain_read_ogg when FLAC__metadata_chain_read itself
 * is missing.
 */
val dumpbinExportRowPattern = Regex(
    """^\s*\d+\s+[0-9A-Fa-f]+\s+[0-9A-Fa-f]+\s+(\S+)\s*$"""
)

fun parseDumpbinExportedSymbols(listing: String): Set<String> =
    listing.lineSequence()
        .mapNotNull { line -> dumpbinExportRowPattern.matchEntire(line)?.groupValues?.get(1) }
        .toSet()

fun readNativeExportedSymbols(library: File): Set<String> {
    val output = ByteArrayOutputStream()
    if (isWindows) {
        val resolvedDumpbinExe = dumpbinExe
            ?: error("Unable to locate dumpbin.exe. Install Visual Studio C++ tools to verify native exports.")
        exec {
            commandLine(resolvedDumpbinExe.absolutePath, "/exports", library.absolutePath)
            standardOutput = output
            errorOutput = output
        }
        return parseDumpbinExportedSymbols(output.toString())
    }

    exec {
        if (isMacOs) {
            commandLine("nm", "-gU", library.absolutePath)
        } else {
            commandLine("nm", "-D", "--defined-only", library.absolutePath)
        }
        standardOutput = output
        errorOutput = output
    }
    return output.toString().lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map { line -> line.split(Regex("\\s+")).last() }
        .map { symbol -> symbol.substringBefore('@') }
        .map { symbol -> if (isMacOs) symbol.removePrefix("_") else symbol }
        .filter { symbol -> symbol.isNotEmpty() && ':' !in symbol }
        .toSet()
}

val jniEntryPointPattern = Regex(
    """JNIEXPORT\s+\S+\s+JNICALL\s+(Java_[A-Za-z0-9_]+)\s*\("""
)

fun parseJniEntryPoints(source: String): Set<String> =
    jniEntryPointPattern.findAll(source)
        .map { match -> match.groupValues[1] }
        .toSet()

val requiredFlacSymbols = providers.fileContents(jniSourceFile).asText.map { source ->
    val invocationCount = flacResolveInvocationPattern.findAll(source).count()
    val symbols = flacResolvedSymbolPattern.findAll(source)
        .map { match -> match.groupValues[1] }
        .toList()

    require(invocationCount > 0) {
        "No FLAC RESOLVE calls were found in ${jniSourceFile.asFile.displayPath()}."
    }
    require(symbols.size == invocationCount) {
        "Expected every RESOLVE call in ${jniSourceFile.asFile.displayPath()} to name one libFLAC export, " +
            "but parsed ${symbols.size} of $invocationCount calls."
    }
    require(symbols.distinct().size == symbols.size) {
        "Duplicate FLAC RESOLVE symbols were found in ${jniSourceFile.asFile.displayPath()}."
    }

    symbols.sorted()
}
val forbiddenBundledDllDependencies = listOf(
    "VCRUNTIME",
    "MSVCP",
    "ucrtbase",
    "api-ms-win-crt"
)
val requiredPackagedJarEntries = packagedNativeTargets.flatMap { target ->
    listOf(
        "META-INF/native/${target.id}/${target.flacPackagedFileName}",
        "META-INF/native/${target.id}/${target.jniFileName}"
    )
} + listOf(
    "META-INF/LICENSE",
    "META-INF/THIRD_PARTY_NOTICES.md"
)
val requiredJavadocPublicApiNames = listOf(
    "FlacDecoder",
    "FlacEncoder",
    "FlacMetadataEditor",
    "FlacMetadataReader"
)

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}

dokka {
    dokkaSourceSets.configureEach {
        perPackageOption {
            matchingRegex.set("org\\.zzvsjs\\.jflac\\.internal(?:\\..*)?")
            suppress.set(true)
        }
    }
}

val compileJava = tasks.named<JavaCompile>("compileJava") {
    /*
     * javac owns the JNI declaration contract. Its generated prototype is
     * included by the C implementation so Java signature drift becomes a C
     * compiler error instead of a runtime linkage failure.
     */
    options.headerOutputDirectory.set(generatedJniHeadersDir)
}

val verifyJniSourceContract by tasks.registering {
    group = "verification"
    description = "Verifies that javac-generated JNI declarations and C implementations have the same entry points."
    dependsOn(compileJava)
    inputs.file(generatedNativeBindingsHeader)
    inputs.file(jniSourceFile)

    doLast {
        val header = generatedNativeBindingsHeader.get().asFile
        require(header.isFile) {
            "Missing javac-generated JNI header at ${header.displayPath()}."
        }

        val declaredEntryPoints = parseJniEntryPoints(header.readText())
        val implementedEntryPoints = parseJniEntryPoints(jniSourceFile.asFile.readText())
        require(declaredEntryPoints.isNotEmpty()) {
            "No JNI entry points were found in ${header.displayPath()}."
        }

        val missingImplementations = declaredEntryPoints - implementedEntryPoints
        val undeclaredImplementations = implementedEntryPoints - declaredEntryPoints
        check(missingImplementations.isEmpty() && undeclaredImplementations.isEmpty()) {
            buildString {
                appendLine("JNI declaration and implementation entry points differ.")
                if (missingImplementations.isNotEmpty()) {
                    appendLine("Declared by Java but missing from C:")
                    missingImplementations.sorted().forEach { entry -> appendLine(" - $entry") }
                }
                if (undeclaredImplementations.isNotEmpty()) {
                    appendLine("Implemented by C but missing from Java:")
                    undeclaredImplementations.sorted().forEach { entry -> appendLine(" - $entry") }
                }
            }
        }
    }
}

val javadocJar by tasks.registering(Jar::class) {
    group = "documentation"
    description = "Builds a Kotlin-aware Javadoc JAR from Dokka output."
    archiveClassifier.set("javadoc")
    from(tasks.dokkaGeneratePublicationJavadoc.flatMap { it.outputDirectory })
}

val downloadFlacSource by tasks.registering {
    group = "build setup"
    description = "Downloads the official FLAC $flacVersion source archive."
    outputs.file(flacArchive)

    doLast {
        val archiveFile = flacArchive.get().asFile
        val needsDownload = !archiveFile.exists() || sha256(archiveFile) != flacSourceSha256
        if (needsDownload) {
            archiveFile.parentFile.mkdirs()
            URI(flacSourceUrl).toURL().openStream().use { input ->
                archiveFile.outputStream().use { output -> input.copyTo(output) }
            }
        }

        val actualSha256 = sha256(archiveFile)
        check(actualSha256 == flacSourceSha256) {
            "Downloaded FLAC source checksum mismatch. Expected $flacSourceSha256 but got $actualSha256."
        }
    }
}

val extractFlacSource by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Extracts and patches the FLAC $flacVersion source archive."
    dependsOn(downloadFlacSource)
    inputs.file(flacArchive)
    inputs.files(flacVendorPatches)
    outputs.dir(flacSourceDir)

    doFirst {
        val sourceParent = flacSourceParentDir.get().asFile
        val sourceRoot = flacSourceDir.get().asFile
        sourceRoot.deleteRecursively()
        sourceParent.mkdirs()
        /*
         * Keep the extraction destination relative to the project directory.
         * Windows sandboxed processes can reject an extended-length \\?\ path
         * even when the same workspace directory is writable.
         */
        commandLine(
            "tar",
            "-xf",
            flacArchive.get().asFile.absolutePath,
            "-C",
            sourceParent.relativeTo(projectDir).path
        )
    }

    doLast {
        val sourcePrefix = flacSourceDir.get().asFile.relativeTo(projectDir).invariantSeparatorsPath
        /*
         * Keep the versioned patches in an explicit order because a later
         * patch may depend on source changed by an earlier one. Checking each
         * patch immediately before applying it also makes an upstream layout
         * change fail rather than silently applying only part of a patch.
         */
        flacVendorPatches.forEach { patch ->
            exec {
                commandLine(
                    "git",
                    "apply",
                    "--check",
                    "--directory=$sourcePrefix",
                    patch.asFile.absolutePath
                )
            }

            exec {
                commandLine(
                    "git",
                    "apply",
                    "--directory=$sourcePrefix",
                    patch.asFile.absolutePath
                )
            }
        }
    }
}

val downloadOggSource by tasks.registering {
    group = "build setup"
    description = "Downloads the official libogg $oggVersion source archive."
    outputs.file(oggArchive)

    doLast {
        val archiveFile = oggArchive.get().asFile
        val needsDownload = !archiveFile.exists() || sha256(archiveFile) != oggSourceSha256
        if (needsDownload) {
            archiveFile.parentFile.mkdirs()
            URI(oggSourceUrl).toURL().openStream().use { input ->
                archiveFile.outputStream().use { output -> input.copyTo(output) }
            }
        }

        val actualSha256 = sha256(archiveFile)
        check(actualSha256 == oggSourceSha256) {
            "Downloaded libogg source checksum mismatch. Expected $oggSourceSha256 but got $actualSha256."
        }
    }
}

val extractOggSource by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Extracts the libogg $oggVersion source archive."
    dependsOn(downloadOggSource)
    inputs.file(oggArchive)
    outputs.dir(oggSourceDir)

    doFirst {
        val sourceParent = oggSourceParentDir.get().asFile
        val sourceRoot = oggSourceDir.get().asFile
        sourceRoot.deleteRecursively()
        sourceParent.mkdirs()
        commandLine("tar", "-xf", oggArchive.get().asFile.absolutePath, "-C", sourceParent.absolutePath)
    }
}

val buildOggNative by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the static libogg library used by the bundled libFLAC runtime."
    dependsOn(extractOggSource)
    inputs.dir(oggSourceDir)
    inputs.property("msvcRuntimeLibrary", msvcRuntimeLibrary)
    inputs.property("nativePlatform", nativePlatformId)
    outputs.file(oggStaticLibrary)
    outputs.file(oggGeneratedConfigHeader)
    onlyIf { nativeTarget != null }

    doFirst {
        oggBuildDir.get().asFile.mkdirs()
        oggBuildDir.get().file("CMakeCache.txt").asFile.delete()
        oggBuildDir.get().dir("CMakeFiles").asFile.deleteRecursively()
        if (isWindows) {
            val resolvedVcvars64 = vcvars64
                ?: error("Unable to locate Visual Studio C++ tools. Install Desktop C++ tools or set up Visual Studio Build Tools.")
            val resolvedNinjaExe = ninjaExe
                ?: error("Unable to locate Visual Studio Ninja. Install the CMake tools for Visual Studio.")
            commandLine(
                "cmd",
                "/c",
                listOf(
                    "call \"${resolvedVcvars64.cmdPath()}\" >nul",
                    listOf(
                        "cmake -S \"${oggSourceDir.get().asFile.cmdPath()}\"",
                        "-B \"${oggBuildDir.get().asFile.cmdPath()}\"",
                        "-G Ninja",
                        "-DCMAKE_BUILD_TYPE=Release",
                        "-DCMAKE_MAKE_PROGRAM=\"${resolvedNinjaExe.cmdPath()}\"",
                        *legacyMsvcRuntimeCmakeOptions.toTypedArray(),
                        "-DBUILD_SHARED_LIBS=OFF",
                        "-DCMAKE_POSITION_INDEPENDENT_CODE=ON",
                        "-DCMAKE_ARCHIVE_OUTPUT_DIRECTORY=\"${oggBuildDir.get().dir("lib").asFile.cmdPath()}\"",
                        "-DCMAKE_RUNTIME_OUTPUT_DIRECTORY=\"${oggBuildDir.get().dir("bin").asFile.cmdPath()}\"",
                        "-DINSTALL_DOCS=OFF"
                    ).joinToString(" "),
                    "\"${resolvedNinjaExe.cmdPath()}\" -C \"${oggBuildDir.get().asFile.cmdPath()}\" ogg -v"
                ).joinToString(" && ")
            )
        } else {
            val configureArguments = mutableListOf(
                "cmake",
                "-S", oggSourceDir.get().asFile.absolutePath,
                "-B", oggBuildDir.get().asFile.absolutePath,
                "-DCMAKE_BUILD_TYPE=Release",
                "-DBUILD_SHARED_LIBS=OFF",
                "-DCMAKE_POSITION_INDEPENDENT_CODE=ON",
                "-DCMAKE_ARCHIVE_OUTPUT_DIRECTORY=${oggBuildDir.get().dir("lib").asFile.absolutePath}",
                "-DINSTALL_DOCS=OFF"
            )
            if (isMacOs) {
                configureArguments += "-DCMAKE_OSX_DEPLOYMENT_TARGET=11.0"
            }
            exec { commandLine(configureArguments) }
            commandLine(
                "cmake", "--build", oggBuildDir.get().asFile.absolutePath,
                "--config", "Release", "--target", "ogg", "--verbose"
            )
        }
    }
}

val buildOggNativeSanitized by tasks.registering(Exec::class) {
    group = "verification"
    description = "Builds a Clang ASan/UBSan static libogg for the native fuzz and sanitizer suites."
    dependsOn(extractOggSource)
    inputs.dir(oggSourceDir)
    inputs.property("sanitizerFlags", nativeSanitizerCompileFlags)
    outputs.file(nativeSanitizerOggLibrary)
    outputs.file(nativeSanitizerOggConfigHeader)
    onlyIf { nativeSanitizerSupported }

    doFirst {
        val buildDirectory = nativeSanitizerOggBuildDir.get().asFile
        buildDirectory.mkdirs()
        nativeSanitizerOggBuildDir.get().file("CMakeCache.txt").asFile.delete()
        nativeSanitizerOggBuildDir.get().dir("CMakeFiles").asFile.deleteRecursively()

        exec {
            commandLine(
                "cmake",
                "-S", oggSourceDir.get().asFile.absolutePath,
                "-B", buildDirectory.absolutePath,
                "-DCMAKE_BUILD_TYPE=RelWithDebInfo",
                "-DCMAKE_C_COMPILER=clang",
                "-DCMAKE_C_FLAGS_RELWITHDEBINFO=-O1 -g $nativeSanitizerCompileFlags",
                "-DCMAKE_EXE_LINKER_FLAGS=$nativeSanitizerLinkFlags",
                "-DCMAKE_SHARED_LINKER_FLAGS=$nativeSanitizerLinkFlags",
                "-DBUILD_SHARED_LIBS=OFF",
                "-DCMAKE_POSITION_INDEPENDENT_CODE=ON",
                "-DCMAKE_ARCHIVE_OUTPUT_DIRECTORY=${nativeSanitizerOggBuildDir.get().dir("lib").asFile.absolutePath}",
                "-DINSTALL_DOCS=OFF",
                "-DINSTALL_PKG_CONFIG_MODULE=OFF",
                "-DINSTALL_CMAKE_PACKAGE_MODULE=OFF"
            )
        }

        commandLine(
            "cmake", "--build", buildDirectory.absolutePath,
            "--config", "RelWithDebInfo", "--target", "ogg", "--verbose"
        )
    }
}

val buildFlacNativeSanitized by tasks.registering(Exec::class) {
    group = "verification"
    description = "Builds a Clang ASan/UBSan static libFLAC for the native fuzz targets."
    dependsOn(extractFlacSource, buildOggNativeSanitized)
    inputs.dir(flacSourceDir)
    inputs.dir(oggSourceDir.map { it.dir("include") })
    inputs.file(nativeSanitizerOggConfigHeader)
    inputs.file(nativeSanitizerOggLibrary)
    inputs.property("sanitizerFlags", nativeSanitizerCompileFlags)
    outputs.file(nativeSanitizerFlacLibrary)
    onlyIf { nativeSanitizerSupported }

    doFirst {
        val buildDirectory = nativeSanitizerFlacBuildDir.get().asFile
        buildDirectory.mkdirs()
        nativeSanitizerFlacBuildDir.get().file("CMakeCache.txt").asFile.delete()
        nativeSanitizerFlacBuildDir.get().dir("CMakeFiles").asFile.deleteRecursively()

        exec {
            commandLine(
                "cmake",
                "-S", flacSourceDir.get().asFile.absolutePath,
                "-B", buildDirectory.absolutePath,
                "-DCMAKE_BUILD_TYPE=RelWithDebInfo",
                "-DCMAKE_C_COMPILER=clang",
                "-DCMAKE_C_FLAGS_RELWITHDEBINFO=-O1 -g $nativeSanitizerCompileFlags",
                "-DCMAKE_EXE_LINKER_FLAGS=$nativeSanitizerLinkFlags",
                "-DCMAKE_SHARED_LINKER_FLAGS=$nativeSanitizerLinkFlags",
                "-DCMAKE_ARCHIVE_OUTPUT_DIRECTORY=${nativeSanitizerFlacBuildDir.get().dir("lib").asFile.absolutePath}",
                "-DCMAKE_POSITION_INDEPENDENT_CODE=ON",
                "-DBUILD_SHARED_LIBS=OFF",
                "-DWITH_OGG=ON",
                "-DWITH_ASM=OFF",
                "-DENABLE_MULTITHREADING=ON",
                "-DOGG_INCLUDE_DIR=${oggSourceDir.get().dir("include").asFile.absolutePath};${nativeSanitizerOggIncludeDir.get().asFile.absolutePath}",
                "-DOGG_LIBRARY=${nativeSanitizerOggLibrary.get().asFile.absolutePath}",
                "-DBUILD_CXXLIBS=OFF",
                "-DBUILD_PROGRAMS=OFF",
                "-DBUILD_EXAMPLES=OFF",
                "-DBUILD_TESTING=OFF",
                "-DBUILD_DOCS=OFF",
                "-DINSTALL_MANPAGES=OFF",
                "-DINSTALL_PKGCONFIG_MODULES=OFF",
                "-DINSTALL_CMAKE_CONFIG_MODULE=OFF"
            )
        }

        commandLine(
            "cmake", "--build", buildDirectory.absolutePath,
            "--config", "RelWithDebInfo", "--target", "FLAC", "--verbose"
        )
    }
}

val buildFlacNativeSharedSanitized by tasks.registering(Exec::class) {
    group = "verification"
    description = "Builds the shared Clang ASan/UBSan libFLAC used by the sanitizer JVM test worker."
    dependsOn(extractFlacSource, buildOggNativeSanitized)
    inputs.dir(flacSourceDir)
    inputs.dir(oggSourceDir.map { it.dir("include") })
    inputs.file(nativeSanitizerOggConfigHeader)
    inputs.file(nativeSanitizerOggLibrary)
    inputs.property("sanitizerFlags", nativeSanitizerCompileFlags)
    outputs.file(nativeSanitizerSharedFlacLibrary)
    onlyIf { nativeSanitizerSupported }

    doFirst {
        val buildDirectory = nativeSanitizerSharedFlacBuildDir.get().asFile
        buildDirectory.mkdirs()
        nativeSanitizerSharedFlacBuildDir.get().file("CMakeCache.txt").asFile.delete()
        nativeSanitizerSharedFlacBuildDir.get().dir("CMakeFiles").asFile.deleteRecursively()

        exec {
            commandLine(
                "cmake",
                "-S", flacSourceDir.get().asFile.absolutePath,
                "-B", buildDirectory.absolutePath,
                "-DCMAKE_BUILD_TYPE=RelWithDebInfo",
                "-DCMAKE_C_COMPILER=clang",
                "-DCMAKE_C_FLAGS_RELWITHDEBINFO=-O1 -g $nativeSanitizerCompileFlags",
                "-DCMAKE_EXE_LINKER_FLAGS=$nativeSanitizerLinkFlags",
                "-DCMAKE_SHARED_LINKER_FLAGS=$nativeSanitizerLinkFlags",
                "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${nativeSanitizerSharedFlacBuildDir.get().dir("lib").asFile.absolutePath}",
                "-DCMAKE_POSITION_INDEPENDENT_CODE=ON",
                "-DBUILD_SHARED_LIBS=ON",
                "-DWITH_OGG=ON",
                "-DWITH_ASM=OFF",
                "-DENABLE_MULTITHREADING=ON",
                "-DOGG_INCLUDE_DIR=${oggSourceDir.get().dir("include").asFile.absolutePath};${nativeSanitizerOggIncludeDir.get().asFile.absolutePath}",
                "-DOGG_LIBRARY=${nativeSanitizerOggLibrary.get().asFile.absolutePath}",
                "-DBUILD_CXXLIBS=OFF",
                "-DBUILD_PROGRAMS=OFF",
                "-DBUILD_EXAMPLES=OFF",
                "-DBUILD_TESTING=OFF",
                "-DBUILD_DOCS=OFF",
                "-DINSTALL_MANPAGES=OFF",
                "-DINSTALL_PKGCONFIG_MODULES=OFF",
                "-DINSTALL_CMAKE_CONFIG_MODULE=OFF"
            )
        }

        commandLine(
            "cmake", "--build", buildDirectory.absolutePath,
            "--config", "RelWithDebInfo", "--target", "FLAC", "--verbose"
        )
    }
}

val buildFlacNative by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the shared libFLAC runtime from the official FLAC $flacVersion source archive."
    dependsOn(extractFlacSource, buildOggNative)
    inputs.dir(flacSourceDir)
    inputs.dir(oggSourceDir.map { it.dir("include") })
    inputs.file(oggGeneratedConfigHeader)
    inputs.file(oggStaticLibrary)
    inputs.property("msvcRuntimeLibrary", msvcRuntimeLibrary)
    inputs.property("nativePlatform", nativePlatformId)
    outputs.file(flacLibrary)
    if (isWindows) {
        outputs.file(flacImportLib)
    }
    onlyIf { nativeTarget != null }

    doFirst {
        flacBuildDir.get().asFile.mkdirs()
        flacBuildDir.get().file("CMakeCache.txt").asFile.delete()
        flacBuildDir.get().dir("CMakeFiles").asFile.deleteRecursively()
        if (isWindows) {
            val resolvedVcvars64 = vcvars64
                ?: error("Unable to locate Visual Studio C++ tools. Install Desktop C++ tools or set up Visual Studio Build Tools.")
            val resolvedNinjaExe = ninjaExe
                ?: error("Unable to locate Visual Studio Ninja. Install the CMake tools for Visual Studio.")
            commandLine(
                "cmd",
                "/c",
                listOf(
                    "call \"${resolvedVcvars64.cmdPath()}\" >nul",
                    listOf(
                        "cmake -S \"${flacSourceDir.get().asFile.cmdPath()}\"",
                        "-B \"${flacBuildDir.get().asFile.cmdPath()}\"",
                        "-G Ninja",
                        "-DCMAKE_BUILD_TYPE=Release",
                        "-DCMAKE_MAKE_PROGRAM=\"${resolvedNinjaExe.cmdPath()}\"",
                        *msvcRuntimeCmakeOptions.toTypedArray(),
                        "-DBUILD_SHARED_LIBS=ON",
                        "-DWITH_OGG=ON",
                        "-DENABLE_MULTITHREADING=ON",
                        /*
                         * libogg keeps its public source headers in the source tree, but
                         * CMake generates config_types.h in the build tree. libFLAC needs both.
                         */
                        "-DOGG_INCLUDE_DIR=\"${oggSourceDir.get().dir("include").asFile.cmdPath()};${oggBuildIncludeDir.get().asFile.cmdPath()}\"",
                        "-DOGG_LIBRARY=\"${oggStaticLibrary.get().asFile.cmdPath()}\"",
                        "-DBUILD_CXXLIBS=OFF",
                        "-DBUILD_PROGRAMS=OFF",
                        "-DBUILD_EXAMPLES=OFF",
                        "-DBUILD_TESTING=OFF",
                        "-DBUILD_DOCS=OFF",
                        "-DINSTALL_MANPAGES=OFF",
                        "-DINSTALL_PKGCONFIG_MODULES=OFF",
                        "-DINSTALL_CMAKE_CONFIG_MODULE=OFF"
                    ).joinToString(" "),
                    "\"${resolvedNinjaExe.cmdPath()}\" -C \"${flacBuildDir.get().asFile.cmdPath()}\" FLAC -v"
                ).joinToString(" && ")
            )
        } else {
            val configureArguments = mutableListOf(
                "cmake",
                "-S", flacSourceDir.get().asFile.absolutePath,
                "-B", flacBuildDir.get().asFile.absolutePath,
                "-DCMAKE_BUILD_TYPE=Release",
                "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${flacBuildDir.get().dir("lib").asFile.absolutePath}",
                "-DCMAKE_POSITION_INDEPENDENT_CODE=ON",
                "-DBUILD_SHARED_LIBS=ON",
                "-DWITH_OGG=ON",
                "-DENABLE_MULTITHREADING=ON",
                "-DOGG_INCLUDE_DIR=${oggSourceDir.get().dir("include").asFile.absolutePath};${oggBuildIncludeDir.get().asFile.absolutePath}",
                "-DOGG_LIBRARY=${oggStaticLibrary.get().asFile.absolutePath}",
                "-DBUILD_CXXLIBS=OFF",
                "-DBUILD_PROGRAMS=OFF",
                "-DBUILD_EXAMPLES=OFF",
                "-DBUILD_TESTING=OFF",
                "-DBUILD_DOCS=OFF",
                "-DINSTALL_MANPAGES=OFF",
                "-DINSTALL_PKGCONFIG_MODULES=OFF",
                "-DINSTALL_CMAKE_CONFIG_MODULE=OFF"
            )
            if (isMacOs) {
                configureArguments += "-DCMAKE_OSX_DEPLOYMENT_TARGET=11.0"
            }
            exec { commandLine(configureArguments) }
            commandLine(
                "cmake", "--build", flacBuildDir.get().asFile.absolutePath,
                "--config", "Release", "--target", "FLAC", "--verbose"
            )
        }
    }
}

val buildFlacInteropTools by tasks.registering(Exec::class) {
    group = "verification"
    description = "Builds the pinned official flac and metaflac $flacVersion programs used by interoperability tests."
    dependsOn(extractFlacSource, buildOggNative)
    inputs.dir(flacSourceDir)
    inputs.dir(oggSourceDir.map { it.dir("include") })
    inputs.file(oggGeneratedConfigHeader)
    inputs.file(oggStaticLibrary)
    inputs.property("msvcRuntimeLibrary", msvcRuntimeLibrary)
    inputs.property("nativePlatform", nativePlatformId)
    inputs.property("officialFlacVersion", flacVersion)
    outputs.files(flacInteropExecutable, metaflacInteropExecutable)
    onlyIf { nativeTarget != null }

    doFirst {
        val toolsBuildDirectory = flacInteropBuildDir.get().asFile
        toolsBuildDirectory.mkdirs()
        flacInteropBuildDir.get().file("CMakeCache.txt").asFile.delete()
        flacInteropBuildDir.get().dir("CMakeFiles").asFile.deleteRecursively()

        /*
         * Keep the reference command-line programs in their own static build.
         * This prevents a runner-provided, potentially older flac executable
         * from silently reducing coverage for 32-bit samples and other modern
         * FLAC features while leaving the packaged shared library unchanged.
         */
        if (isWindows) {
            val resolvedVcvars64 = vcvars64
                ?: error("Unable to locate Visual Studio C++ tools. Install Desktop C++ tools or set up Visual Studio Build Tools.")
            val resolvedNinjaExe = ninjaExe
                ?: error("Unable to locate Visual Studio Ninja. Install the CMake tools for Visual Studio.")
            commandLine(
                "cmd",
                "/c",
                listOf(
                    "call \"${resolvedVcvars64.cmdPath()}\" >nul",
                    listOf(
                        "cmake -S \"${flacSourceDir.get().asFile.cmdPath()}\"",
                        "-B \"${toolsBuildDirectory.cmdPath()}\"",
                        "-G Ninja",
                        "-DCMAKE_BUILD_TYPE=Release",
                        "-DCMAKE_MAKE_PROGRAM=\"${resolvedNinjaExe.cmdPath()}\"",
                        *msvcRuntimeCmakeOptions.toTypedArray(),
                        "-DBUILD_SHARED_LIBS=OFF",
                        "-DWITH_OGG=ON",
                        "-DENABLE_MULTITHREADING=ON",
                        "-DOGG_INCLUDE_DIR=\"${oggSourceDir.get().dir("include").asFile.cmdPath()};${oggBuildIncludeDir.get().asFile.cmdPath()}\"",
                        "-DOGG_LIBRARY=\"${oggStaticLibrary.get().asFile.cmdPath()}\"",
                        "-DBUILD_CXXLIBS=OFF",
                        "-DBUILD_PROGRAMS=ON",
                        "-DBUILD_EXAMPLES=OFF",
                        "-DBUILD_TESTING=OFF",
                        "-DBUILD_DOCS=OFF",
                        "-DINSTALL_MANPAGES=OFF",
                        "-DINSTALL_PKGCONFIG_MODULES=OFF",
                        "-DINSTALL_CMAKE_CONFIG_MODULE=OFF"
                    ).joinToString(" "),
                    "\"${resolvedNinjaExe.cmdPath()}\" -C \"${toolsBuildDirectory.cmdPath()}\" flacapp metaflac -v"
                ).joinToString(" && ")
            )
        } else {
            val configureArguments = mutableListOf(
                "cmake",
                "-S", flacSourceDir.get().asFile.absolutePath,
                "-B", toolsBuildDirectory.absolutePath,
                "-DCMAKE_BUILD_TYPE=Release",
                "-DBUILD_SHARED_LIBS=OFF",
                "-DWITH_OGG=ON",
                "-DENABLE_MULTITHREADING=ON",
                "-DOGG_INCLUDE_DIR=${oggSourceDir.get().dir("include").asFile.absolutePath};${oggBuildIncludeDir.get().asFile.absolutePath}",
                "-DOGG_LIBRARY=${oggStaticLibrary.get().asFile.absolutePath}",
                "-DBUILD_CXXLIBS=OFF",
                "-DBUILD_PROGRAMS=ON",
                "-DBUILD_EXAMPLES=OFF",
                "-DBUILD_TESTING=OFF",
                "-DBUILD_DOCS=OFF",
                "-DINSTALL_MANPAGES=OFF",
                "-DINSTALL_PKGCONFIG_MODULES=OFF",
                "-DINSTALL_CMAKE_CONFIG_MODULE=OFF"
            )
            if (isMacOs) {
                configureArguments += "-DCMAKE_OSX_DEPLOYMENT_TARGET=11.0"
            }

            exec { commandLine(configureArguments) }
            commandLine(
                "cmake", "--build", toolsBuildDirectory.absolutePath,
                "--config", "Release", "--target", "flacapp", "metaflac", "--verbose"
            )
        }
    }
}

val buildNative by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the JNI shim for the current supported host platform."
    dependsOn(extractFlacSource, verifyJniSourceContract)
    inputs.dir(file("native"))
    inputs.dir(jniIncludeDir)
    inputs.dir(jniPlatformIncludeDir)
    inputs.dir(generatedJniHeadersDir)
    inputs.dir(flacSourceDir.map { it.dir("include") })
    inputs.property("msvcRuntimeLibrary", msvcRuntimeLibrary)
    inputs.property("expectedFlacVersion", flacVersion)
    inputs.property("expectedFlacApiVersionCurrent", flacApiVersionCurrent)
    inputs.property("expectedFlacApiVersionRevision", flacApiVersionRevision)
    inputs.property("expectedFlacApiVersionAge", flacApiVersionAge)
    inputs.property("nativePlatform", nativePlatformId)
    outputs.files(nativeLibrary, nativeCompileCommands, rootCompileCommands)
    onlyIf { nativeTarget != null }

    doFirst {
        require(jniIncludeDir.resolve("jni.h").isFile) {
            "Unable to locate jni.h under ${jniIncludeDir.displayPath()}."
        }
        require(jniPlatformIncludeDir.resolve("jni_md.h").isFile) {
            "Unable to locate host JNI platform headers under ${jniPlatformIncludeDir.displayPath()}."
        }

        nativeBuildDir.get().asFile.mkdirs()
        nativeBuildDir.get().file("CMakeCache.txt").asFile.delete()
        nativeBuildDir.get().dir("CMakeFiles").asFile.deleteRecursively()
        environment("JAVA_HOME", javaHomeDir.absolutePath)
        if (isWindows) {
            val resolvedVcvars64 = vcvars64
                ?: error("Unable to locate Visual Studio C++ tools. Install Desktop C++ tools or set up Visual Studio Build Tools.")
            val resolvedNinjaExe = ninjaExe
                ?: error("Unable to locate Visual Studio Ninja. Install the CMake tools for Visual Studio.")
            commandLine(
                "cmd",
                "/c",
                listOf(
                    "call \"${resolvedVcvars64.cmdPath()}\" >nul",
                    listOf(
                        "cmake -S \"${file("native").cmdPath()}\"",
                        "-B \"${nativeBuildDir.get().asFile.cmdPath()}\"",
                        "-G Ninja",
                        "-DCMAKE_BUILD_TYPE=Release",
                        "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON",
                        "-DCMAKE_MAKE_PROGRAM=\"${resolvedNinjaExe.cmdPath()}\"",
                        *msvcRuntimeCmakeOptions.toTypedArray(),
                        "-DJFLAC_FLAC_INCLUDE_DIR=\"${flacSourceDir.get().dir("include").asFile.cmdPath()}\"",
                        "-DJFLAC_GENERATED_JNI_INCLUDE_DIR=\"${generatedJniHeadersDir.get().asFile.cmdPath()}\"",
                        "-DJFLAC_EXPECTED_FLAC_VERSION=$flacVersion",
                        "-DJFLAC_EXPECTED_FLAC_API_VERSION_CURRENT=$flacApiVersionCurrent",
                        "-DJFLAC_EXPECTED_FLAC_API_VERSION_REVISION=$flacApiVersionRevision",
                        "-DJFLAC_EXPECTED_FLAC_API_VERSION_AGE=$flacApiVersionAge"
                    ).joinToString(" "),
                    "cmake -E copy_if_different \"${nativeCompileCommands.get().asFile.cmdPath()}\" \"${rootCompileCommands.asFile.cmdPath()}\"",
                    "\"${resolvedNinjaExe.cmdPath()}\" -C \"${nativeBuildDir.get().asFile.cmdPath()}\" -v"
                ).joinToString(" && ")
            )
        } else {
            val configureArguments = mutableListOf(
                "cmake",
                "-S", file("native").absolutePath,
                "-B", nativeBuildDir.get().asFile.absolutePath,
                "-DCMAKE_BUILD_TYPE=Release",
                "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON",
                "-DJFLAC_FLAC_INCLUDE_DIR=${flacSourceDir.get().dir("include").asFile.absolutePath}",
                "-DJFLAC_GENERATED_JNI_INCLUDE_DIR=${generatedJniHeadersDir.get().asFile.absolutePath}",
                "-DJFLAC_EXPECTED_FLAC_VERSION=$flacVersion",
                "-DJFLAC_EXPECTED_FLAC_API_VERSION_CURRENT=$flacApiVersionCurrent",
                "-DJFLAC_EXPECTED_FLAC_API_VERSION_REVISION=$flacApiVersionRevision",
                "-DJFLAC_EXPECTED_FLAC_API_VERSION_AGE=$flacApiVersionAge"
            )
            if (isMacOs) {
                configureArguments += "-DCMAKE_OSX_DEPLOYMENT_TARGET=11.0"
            }
            exec {
                environment("JAVA_HOME", javaHomeDir.absolutePath)
                commandLine(configureArguments)
            }
            commandLine(
                "cmake", "--build", nativeBuildDir.get().asFile.absolutePath,
                "--config", "Release", "--target", "jflac-jni", "--verbose"
            )
        }
    }

    doLast {
        if (nativeCompileCommands.get().asFile.isFile) {
            nativeCompileCommands.get().asFile.copyTo(rootCompileCommands.asFile, overwrite = true)
        }
    }
}

val buildNativeFuzzers by tasks.registering(Exec::class) {
    group = "verification"
    description = "Builds the bounded Clang libFuzzer decoder and metadata targets with ASan/UBSan."
    dependsOn(buildFlacNativeSanitized, verifyJniSourceContract)
    inputs.dir(file("native"))
    inputs.dir(jniIncludeDir)
    inputs.dir(jniPlatformIncludeDir)
    inputs.dir(generatedJniHeadersDir)
    inputs.dir(flacSourceDir.map { it.dir("include") })
    inputs.file(nativeSanitizerFlacLibrary)
    inputs.file(nativeSanitizerOggLibrary)
    inputs.property("sanitizerFlags", nativeSanitizerCompileFlags)
    inputs.property("expectedFlacVersion", flacVersion)
    outputs.files(nativeSanitizerLibrary, decoderFuzzExecutable, metadataFuzzExecutable)
    onlyIf { nativeSanitizerSupported }

    doFirst {
        require(jniIncludeDir.resolve("jni.h").isFile) {
            "Unable to locate jni.h under ${jniIncludeDir.displayPath()}."
        }
        require(jniPlatformIncludeDir.resolve("jni_md.h").isFile) {
            "Unable to locate Linux JNI platform headers under ${jniPlatformIncludeDir.displayPath()}."
        }

        val buildDirectory = nativeSanitizerBuildDir.get().asFile
        buildDirectory.mkdirs()
        nativeSanitizerBuildDir.get().file("CMakeCache.txt").asFile.delete()
        nativeSanitizerBuildDir.get().dir("CMakeFiles").asFile.deleteRecursively()

        exec {
            environment("JAVA_HOME", javaHomeDir.absolutePath)
            commandLine(
                "cmake",
                "-S", file("native").absolutePath,
                "-B", buildDirectory.absolutePath,
                "-DCMAKE_BUILD_TYPE=RelWithDebInfo",
                "-DCMAKE_C_COMPILER=clang",
                "-DCMAKE_C_FLAGS_RELWITHDEBINFO=-O1 -g",
                "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON",
                "-DJFLAC_ENABLE_SANITIZERS=ON",
                "-DJFLAC_BUILD_FUZZERS=ON",
                "-DJFLAC_FLAC_INCLUDE_DIR=${flacSourceDir.get().dir("include").asFile.absolutePath}",
                "-DJFLAC_GENERATED_JNI_INCLUDE_DIR=${generatedJniHeadersDir.get().asFile.absolutePath}",
                "-DJFLAC_FLAC_STATIC_LIBRARY=${nativeSanitizerFlacLibrary.get().asFile.absolutePath}",
                "-DJFLAC_OGG_STATIC_LIBRARY=${nativeSanitizerOggLibrary.get().asFile.absolutePath}",
                "-DJFLAC_EXPECTED_FLAC_VERSION=$flacVersion",
                "-DJFLAC_EXPECTED_FLAC_API_VERSION_CURRENT=$flacApiVersionCurrent",
                "-DJFLAC_EXPECTED_FLAC_API_VERSION_REVISION=$flacApiVersionRevision",
                "-DJFLAC_EXPECTED_FLAC_API_VERSION_AGE=$flacApiVersionAge"
            )
        }

        environment("JAVA_HOME", javaHomeDir.absolutePath)
        commandLine(
            "cmake", "--build", buildDirectory.absolutePath,
            "--config", "RelWithDebInfo",
            "--target", "jflac-jni", "jflac-decoder-fuzz", "jflac-metadata-fuzz",
            "--verbose"
        )
    }
}

val prepareNativeFuzzCorpus by tasks.registering {
    group = "verification"
    description = "Decodes the reviewable hexadecimal and Base64 fuzz seeds into bounded binary corpora."
    inputs.dir(nativeFuzzSeedDir)
    outputs.dir(nativeFuzzCorpusDir)

    doLast {
        val corpusRoot = nativeFuzzCorpusDir.get().asFile
        val decoderCorpus = decoderFuzzCorpusDir.get().asFile
        val metadataCorpus = metadataFuzzCorpusDir.get().asFile
        corpusRoot.deleteRecursively()
        decoderCorpus.mkdirs()
        metadataCorpus.mkdirs()

        val seeds = linkedMapOf<String, ByteArray>()
        nativeFuzzSeedDir.asFile.listFiles()
            .orEmpty()
            .filter(File::isFile)
            .sortedBy(File::getName)
            .forEach { seedSource ->
                val outputName = seedSource.nameWithoutExtension
                val bytes = when (seedSource.extension.lowercase()) {
                    "hex" -> {
                        val tokens = seedSource.readLines()
                            .joinToString(" ") { line -> line.substringBefore('#') }
                            .trim()
                            .split(Regex("\\s+"))
                            .filter(String::isNotEmpty)

                        require(tokens.all { token -> token.matches(Regex("[0-9A-Fa-f]{2}")) }) {
                            "Invalid hexadecimal byte in ${seedSource.displayPath()}."
                        }

                        ByteArray(tokens.size) { index -> tokens[index].toInt(16).toByte() }
                    }
                    "b64" -> Base64.getMimeDecoder().decode(seedSource.readText())
                    else -> error("Unsupported fuzz seed source: ${seedSource.displayPath()}.")
                }

                require(bytes.isNotEmpty()) {
                    "Fuzz seed ${seedSource.displayPath()} decoded to an empty input."
                }

                val expectation = nativeFuzzSeedExpectations[outputName]
                    ?: error("Fuzz seed ${seedSource.displayPath()} has no reviewed expectation.")
                val marker = expectation.containerMarker.toByteArray(Charsets.US_ASCII)
                check(bytes.size == expectation.byteLength) {
                    "Fuzz seed ${seedSource.displayPath()} decoded to ${bytes.size} bytes; " +
                        "expected ${expectation.byteLength}."
                }
                check(sha256(bytes) == expectation.sha256) {
                    "Fuzz seed ${seedSource.displayPath()} does not match its reviewed SHA-256 digest."
                }
                check(bytes.size >= marker.size && bytes.copyOf(marker.size).contentEquals(marker)) {
                    "Fuzz seed ${seedSource.displayPath()} does not start with ${expectation.containerMarker}."
                }

                seeds[outputName] = bytes
            }

        check(seeds.isNotEmpty()) { "No native fuzz seeds were found." }
        check(seeds.keys == nativeFuzzSeedExpectations.keys) {
            "The decoded fuzz seed set does not match the reviewed seed expectations."
        }
        listOf(decoderCorpus, metadataCorpus).forEach { corpus ->
            seeds.forEach { (name, bytes) -> corpus.resolve(name).writeBytes(bytes) }
        }
    }
}

val decoderFuzzSmoke by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs a deterministic bounded decoder libFuzzer smoke budget under ASan/UBSan."
    dependsOn(buildNativeFuzzers, prepareNativeFuzzCorpus)
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Fuzz smoke runs must execute on the current native host") { true }
    onlyIf { nativeSanitizerSupported }

    doFirst {
        val artifactDirectory = nativeFuzzArtifactDir.get().dir("decoder").asFile
        artifactDirectory.mkdirs()
        environment(
            "ASAN_OPTIONS",
            "abort_on_error=1:allocator_may_return_null=0:detect_leaks=1:halt_on_error=1"
        )
        environment("UBSAN_OPTIONS", "halt_on_error=1:print_stacktrace=1")
        commandLine(
            decoderFuzzExecutable.get().asFile.absolutePath,
            decoderFuzzCorpusDir.get().asFile.absolutePath,
            "-seed=1",
            "-runs=512",
            "-max_len=1048576",
            "-timeout=10",
            "-rss_limit_mb=2048",
            "-print_final_stats=1",
            "-artifact_prefix=${artifactDirectory.absolutePath}${File.separator}"
        )
    }
}

val metadataFuzzSmoke by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs a deterministic bounded metadata libFuzzer smoke budget under ASan/UBSan."
    dependsOn(buildNativeFuzzers, prepareNativeFuzzCorpus)
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Fuzz smoke runs must execute on the current native host") { true }
    onlyIf { nativeSanitizerSupported }

    doFirst {
        val artifactDirectory = nativeFuzzArtifactDir.get().dir("metadata").asFile
        artifactDirectory.mkdirs()
        environment(
            "ASAN_OPTIONS",
            "abort_on_error=1:allocator_may_return_null=0:detect_leaks=1:halt_on_error=1"
        )
        environment("UBSAN_OPTIONS", "halt_on_error=1:print_stacktrace=1")
        commandLine(
            metadataFuzzExecutable.get().asFile.absolutePath,
            metadataFuzzCorpusDir.get().asFile.absolutePath,
            "-seed=1",
            "-runs=512",
            "-max_len=1048576",
            "-timeout=10",
            "-rss_limit_mb=2048",
            "-print_final_stats=1",
            "-artifact_prefix=${artifactDirectory.absolutePath}${File.separator}"
        )
    }
}

val verifyBundledJniLibrary by tasks.registering {
    group = "verification"
    description = "Verifies that the JNI library exports every javac-generated entry point."
    dependsOn(buildNative)
    inputs.file(nativeLibrary)
    inputs.file(generatedNativeBindingsHeader)
    onlyIf { nativeTarget != null }

    doLast {
        val bundledJniLibrary = nativeLibrary.get().asFile
        require(bundledJniLibrary.isFile) {
            "Missing bundled JNI library at ${bundledJniLibrary.displayPath()}."
        }

        val exportedSymbols = readNativeExportedSymbols(bundledJniLibrary)
        val declaredEntryPoints = parseJniEntryPoints(generatedNativeBindingsHeader.get().asFile.readText())
        val missingEntryPoints = declaredEntryPoints - exportedSymbols
        check(missingEntryPoints.isEmpty()) {
            buildString {
                appendLine("Built JNI library is missing generated JNI exports.")
                missingEntryPoints.sorted().forEach { entry -> appendLine(" - $entry") }
            }
        }
    }
}

val verifyBundledFlacLibrary by tasks.registering {
    group = "verification"
    description = "Verifies that the built libFLAC exports the symbols required by the JNI wrapper."
    dependsOn(buildFlacNative)
    inputs.file(flacLibrary)
    inputs.file(jniSourceFile)
    onlyIf { nativeTarget != null }

    doLast {
        val bundledFlacLibrary = flacLibrary.get().asFile
        require(bundledFlacLibrary.exists()) {
            "Missing built libFLAC at ${bundledFlacLibrary.displayPath()}"
        }

        val exportedSymbols = readNativeExportedSymbols(bundledFlacLibrary)
        check(exportedSymbols.isNotEmpty()) {
            "The platform symbol tool did not report named exports for ${bundledFlacLibrary.displayPath()}."
        }
        val missingSymbols = requiredFlacSymbols.get().filterNot(exportedSymbols::contains)
        check(missingSymbols.isEmpty()) {
            buildString {
                appendLine("Built libFLAC is missing JNI-required exports.")
                appendLine("Library: ${bundledFlacLibrary.displayPath()}")
                appendLine("Missing symbols:")
                missingSymbols.forEach { symbol -> appendLine(" - $symbol") }
            }
        }
    }
}

val verifyBundledNativeDependencies by tasks.registering {
    group = "verification"
    description = "Verifies that bundled native libraries avoid unbundled runtime dependencies."
    dependsOn(buildNative, buildFlacNative)
    inputs.files(nativeLibrary, flacLibrary)
    onlyIf { nativeTarget != null }

    doLast {
        val bundledJniLibrary = nativeLibrary.get().asFile
        val bundledFlacLibrary = flacLibrary.get().asFile
        listOf(bundledJniLibrary, bundledFlacLibrary).forEach { library ->
            require(library.exists()) {
                "Missing bundled native library at ${library.displayPath()}"
            }
        }

        if (isWindows) {
            val resolvedDumpbinExe = dumpbinExe
                ?: error("Unable to locate dumpbin.exe. Install Visual Studio C++ tools to verify DLL dependencies.")
            listOf(bundledJniLibrary, bundledFlacLibrary).forEach { library ->
                val output = ByteArrayOutputStream()
                exec {
                    commandLine(resolvedDumpbinExe.absolutePath, "/dependents", library.absolutePath)
                    standardOutput = output
                    errorOutput = output
                }

                val dependencyListing = output.toString()
                val forbiddenDependencies = forbiddenBundledDllDependencies.filter {
                    dependencyListing.contains(it, ignoreCase = true)
                }
                check(forbiddenDependencies.isEmpty()) {
                    buildString {
                        appendLine("Bundled DLL has redistributable MSVC runtime dependencies.")
                        appendLine("DLL: ${library.displayPath()}")
                        appendLine("Forbidden dependency patterns:")
                        forbiddenDependencies.forEach { dependency -> appendLine(" - $dependency") }
                        appendLine("Use static MSVC runtime linking for bundled Windows DLLs.")
                    }
                }
            }
        } else {
            fun dependencyListing(library: File): String {
                val output = ByteArrayOutputStream()
                exec {
                    if (isMacOs) {
                        commandLine("otool", "-L", library.absolutePath)
                    } else {
                        commandLine("readelf", "-d", library.absolutePath)
                    }
                    standardOutput = output
                    errorOutput = output
                }
                return output.toString()
            }

            val jniDependencies = dependencyListing(bundledJniLibrary)
            val flacDependencies = dependencyListing(bundledFlacLibrary)
            check(!flacDependencies.contains("libogg", ignoreCase = true)) {
                "Bundled libFLAC must link the bundled static libogg rather than depend on a system libogg."
            }
            check(!jniDependencies.contains("libFLAC", ignoreCase = true)) {
                "The JNI shim must resolve its sibling libFLAC explicitly instead of linking a system libFLAC."
            }
        }
    }
}

val syncNativeResources by tasks.registering(Sync::class) {
    group = "build"
    description = "Stages native runtime libraries into the JAR resources."
    dependsOn(buildNative, verifyBundledFlacLibrary, verifyBundledJniLibrary, verifyBundledNativeDependencies)
    onlyIf { nativeTarget != null }
    into(generatedResourcesDir)
    from(flacLibrary) {
        into(nativeResourceRoot)
        rename { activeNativeTarget.flacPackagedFileName }
    }
    from(nativeLibrary) {
        into(nativeResourceRoot)
    }
}

val verifySuppliedNativeResources by tasks.registering {
    group = "verification"
    description = "Verifies a CI-assembled cross-platform native resource bundle."
    onlyIf { suppliedNativeBundleDirectory != null }

    suppliedNativeBundleDirectory?.let { bundleDirectory ->
        inputs.dir(bundleDirectory)
    }

    doLast {
        val bundleDirectory = requireNotNull(suppliedNativeBundleDirectory)
        require(bundleDirectory.isDirectory) {
            "The supplied native bundle directory does not exist: ${bundleDirectory.displayPath()}."
        }
        val requiredNativeEntries = requiredPackagedJarEntries.filter { entry ->
            entry.startsWith("META-INF/native/")
        }
        val missingEntries = requiredNativeEntries.filterNot { entry ->
            bundleDirectory.resolve(entry).isFile
        }
        check(missingEntries.isEmpty()) {
            buildString {
                appendLine("The supplied native bundle is incomplete.")
                appendLine("Bundle: ${bundleDirectory.displayPath()}")
                appendLine("Missing entries:")
                missingEntries.forEach { entry -> appendLine(" - $entry") }
            }
        }
    }
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version.toString()
        )
    }
    from("LICENSE") {
        into("META-INF")
    }
    from("THIRD_PARTY_NOTICES.md") {
        into("META-INF")
    }
}

val verifyPackagedJar by tasks.registering {
    group = "verification"
    description = "Verifies that the packaged JAR contains the expected native resources, licences, and manifest version."
    dependsOn(tasks.named("jar"))
    onlyIf { packagedNativeTargets.isNotEmpty() }

    val packagedJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    inputs.file(packagedJar)

    doLast {
        ZipFile(packagedJar.get().asFile).use { jar ->
            val entryNames = jar.entries().asSequence().map { entry -> entry.name }.toList()
            val duplicateEntries = entryNames.groupingBy { entry -> entry }
                .eachCount()
                .filterValues { count -> count > 1 }
                .keys
                .sorted()
            check(duplicateEntries.isEmpty()) {
                buildString {
                    appendLine("Packaged JAR contains duplicate entries.")
                    duplicateEntries.forEach { entry -> appendLine(" - $entry") }
                }
            }

            val missingEntries = requiredPackagedJarEntries.filter { entry -> jar.getEntry(entry) == null }
            check(missingEntries.isEmpty()) {
                buildString {
                    appendLine("Packaged JAR is missing required entries.")
                    missingEntries.forEach { entry -> appendLine(" - $entry") }
                }
            }

            val manifestEntry = jar.getEntry("META-INF/MANIFEST.MF")
                ?: error("Packaged JAR is missing META-INF/MANIFEST.MF.")
            val manifest = jar.getInputStream(manifestEntry).use { input -> Manifest(input) }
            val implementationVersion = manifest.mainAttributes.getValue("Implementation-Version")
            val expectedImplementationVersion = project.version.toString()
            check(implementationVersion == expectedImplementationVersion) {
                "Packaged JAR manifest Implementation-Version must be $expectedImplementationVersion but was ${implementationVersion ?: "<missing>"}."
            }
        }
    }
}

val verifyJavadocJar by tasks.registering {
    group = "verification"
    description = "Verifies that the Javadoc JAR contains Kotlin public API documentation encoded as UTF-8 HTML."
    dependsOn(tasks.named("javadocJar"))

    val javadocJar = tasks.named<Jar>("javadocJar").flatMap { it.archiveFile }
    inputs.file(javadocJar)

    doLast {
        ZipFile(javadocJar.get().asFile).use { jar ->
            val htmlDocuments = jar.entries().asSequence()
                .filter { entry -> !entry.isDirectory && entry.name.endsWith(".html") }
                .associate { entry ->
                    entry.name to jar.getInputStream(entry).reader(Charsets.UTF_8).use { reader -> reader.readText() }
                }

            val internalDocuments = htmlDocuments.keys.filter { entry ->
                entry.startsWith("org/zzvsjs/jflac/internal/")
            }
            check(internalDocuments.isEmpty()) {
                "Javadoc JAR must not publish the internal JNI bridge package: ${internalDocuments.joinToString()}"
            }

            check(htmlDocuments.isNotEmpty()) {
                "Javadoc JAR does not contain any HTML files."
            }

            val joinedHtml = htmlDocuments.values.joinToString(separator = "\n")
            val missingApiNames = requiredJavadocPublicApiNames.filterNot(joinedHtml::contains)
            check(missingApiNames.isEmpty()) {
                buildString {
                    appendLine("Javadoc JAR does not include the expected public Kotlin API names.")
                    appendLine("Missing names:")
                    missingApiNames.forEach { apiName -> appendLine(" - $apiName") }
                }
            }

            val htmlWithoutUtf8 = htmlDocuments
                .filterValues { html -> !html.contains("charset", ignoreCase = true) || !html.contains("utf-8", ignoreCase = true) }
                .keys
                .sorted()
            check(htmlWithoutUtf8.isEmpty()) {
                buildString {
                    appendLine("Javadoc JAR contains HTML files without an explicit UTF-8 charset.")
                    htmlWithoutUtf8.forEach { entry -> appendLine(" - $entry") }
                }
            }
        }
    }
}

tasks.processResources {
    if (suppliedNativeBundleDirectory != null) {
        dependsOn(verifySuppliedNativeResources)
        from(suppliedNativeBundleDirectory)
    } else if (nativeTarget != null) {
        dependsOn(syncNativeResources)
        from(syncNativeResources)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).apply {
        charSet = "UTF-8"
        docEncoding = "UTF-8"
        locale = "en_US"
    }
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

data class ExternalInteropTool(
    val displayName: String,
    val commandName: String,
    val gradleProperty: String,
    val environmentVariable: String,
    val pinnedExecutable: File? = null
)

val externalInteropTools = listOf(
    ExternalInteropTool(
        displayName = "official flac",
        commandName = "flac",
        gradleProperty = "jflac.external.flac",
        environmentVariable = "JFLAC_EXTERNAL_FLAC",
        pinnedExecutable = flacInteropExecutable.get().asFile
    ),
    ExternalInteropTool(
        displayName = "FFmpeg",
        commandName = "ffmpeg",
        gradleProperty = "jflac.external.ffmpeg",
        environmentVariable = "JFLAC_EXTERNAL_FFMPEG"
    ),
    ExternalInteropTool(
        displayName = "official metaflac",
        commandName = "metaflac",
        gradleProperty = "jflac.external.metaflac",
        environmentVariable = "JFLAC_EXTERNAL_METAFLAC",
        pinnedExecutable = metaflacInteropExecutable.get().asFile
    )
)

/*
 * Resolves an executable without invoking a platform shell. Explicit Gradle
 * properties and environment variables may contain absolute or project-relative
 * paths; an unqualified command name is searched through PATH. Windows PATHEXT
 * entries are considered because Chocolatey and other package managers may
 * expose either native executables or command shims.
 */
fun findExternalInteropExecutable(requestedValue: String): File? {
    val requested = requestedValue.trim().trim('"')
    if (requested.isEmpty()) {
        return null
    }

    fun usable(file: File): Boolean {
        return file.isFile && (isWindows || file.canExecute())
    }

    val direct = file(requested).absoluteFile.normalize()
    if (usable(direct)) {
        return direct
    }

    val requestedFile = File(requested)
    if (requestedFile.isAbsolute || requested.contains('/') || requested.contains('\\')) {
        return null
    }

    val pathDirectories = System.getenv("PATH")
        .orEmpty()
        .split(File.pathSeparatorChar)
        .asSequence()
        .map(String::trim)
        .map { entry -> entry.trim('"') }
        .filter(String::isNotEmpty)

    val requestedHasExtension = requestedFile.extension.isNotEmpty()
    val windowsExtensions = if (isWindows && !requestedHasExtension) {
        sequenceOf(".exe", ".com", ".cmd", ".bat") +
            System.getenv("PATHEXT")
                .orEmpty()
                .split(File.pathSeparatorChar)
                .asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .map { extension -> extension.lowercase() }
    } else {
        sequenceOf("")
    }

    val candidateNames = windowsExtensions
        .distinct()
        .map { extension -> requested + extension }
        .toList()

    return pathDirectories
        .flatMap { directory -> candidateNames.asSequence().map { name -> File(directory, name) } }
        .firstOrNull(::usable)
        ?.absoluteFile
        ?.normalize()
}

val stageNativeSanitizerResources by tasks.registering(Sync::class) {
    group = "verification"
    description = "Stages the sanitizer-instrumented Linux libFLAC and JNI shim for an isolated test worker."
    dependsOn(buildFlacNativeSharedSanitized, buildNativeFuzzers)
    into(nativeSanitizerPlatformResourcesDir)
    from(nativeSanitizerSharedFlacLibrary) {
        rename { "libFLAC.so.$flacApiVersionCurrent" }
    }
    from(nativeSanitizerLibrary) {
        rename { "libjflac-jni.so" }
    }
    onlyIf { nativeSanitizerSupported }

    doLast {
        val stagedFlac = nativeSanitizerPlatformResourcesDir.get()
            .file("libFLAC.so.$flacApiVersionCurrent")
            .asFile
        val stagedJni = nativeSanitizerPlatformResourcesDir.get()
            .file("libjflac-jni.so")
            .asFile

        require(stagedFlac.isFile && stagedJni.isFile) {
            "The isolated sanitizer resource tree must contain both libFLAC and the JNI shim."
        }
        check(sha256(stagedFlac) == sha256(nativeSanitizerSharedFlacLibrary.get().asFile)) {
            "The staged sanitizer libFLAC does not match the instrumented build output."
        }
        check(sha256(stagedJni) == sha256(nativeSanitizerLibrary.get().asFile)) {
            "The staged sanitizer JNI shim does not match the instrumented build output."
        }
    }
}

tasks.test {
    /* External programs are mandatory only for the dedicated verification task. */
    useJUnitPlatform {
        excludeTags("external-interop", "long-file-performance")
    }

    if (suppliedNativeBundleDirectory == null && nativeTarget != null) {
        dependsOn(syncNativeResources)
    }

    systemProperty("jflac.native.tmpdir", layout.buildDirectory.dir("tmp/native-test").get().asFile.absolutePath)
}

val longFilePerformanceTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs the bounded long-file streaming-memory and repeated-seek performance checks."
    dependsOn(tasks.testClasses)
    shouldRunAfter(tasks.test)

    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Performance measurements must run on the current host") { true }

    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("long-file-performance")
    }
    filter {
        includeTestsMatching("org.zzvsjs.jflac.FlacLongFilePerformanceTest")
        setFailOnNoMatchingTests(true)
    }

    maxHeapSize = "96m"
    maxParallelForks = 1
    forkEvery = 1L
    jvmArgs("-XX:+UseSerialGC")
    testLogging.showStandardStreams = true

    if (suppliedNativeBundleDirectory == null && nativeTarget != null) {
        dependsOn(syncNativeResources)
    }

    systemProperty(
        "jflac.native.tmpdir",
        layout.buildDirectory.dir("tmp/native-long-file-performance-test").get().asFile.absolutePath
    )
    systemProperty("junit.jupiter.execution.timeout.beforeall.method.default", "3 m")
    listOf(
        "jflac.performance.fixtureBudgetMillis",
        "jflac.performance.decodeBudgetMillis",
        "jflac.performance.seekBudgetMillis"
    ).forEach { propertyName ->
        providers.systemProperty(propertyName).orNull?.let { value ->
            systemProperty(propertyName, value)
        }
    }
}

val nativeSanitizerJvmTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs the JVM integration suite against ASan/UBSan libFLAC and JNI libraries."
    dependsOn(tasks.testClasses, stageNativeSanitizerResources)
    shouldRunAfter(tasks.test)

    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Sanitizer instrumentation must execute on the current native host") { true }
    onlyIf { nativeSanitizerSupported }

    testClassesDirs = sourceSets.test.get().output.classesDirs
    val ordinaryMainResources = layout.buildDirectory.dir("resources/main")
        .get()
        .asFile
        .absoluteFile
        .normalize()
    val runtimeWithoutOrdinaryNativeResources = sourceSets.test.get().runtimeClasspath.filter { entry ->
        entry.absoluteFile.normalize() != ordinaryMainResources
    }
    classpath = files(nativeSanitizerResourcesDir) + runtimeWithoutOrdinaryNativeResources
    useJUnitPlatform {
        excludeTags("external-interop", "long-file-performance")
    }
    maxParallelForks = 1
    forkEvery = 0L
    jvmArgs("-Xcheck:jni")

    systemProperty(
        "jflac.native.tmpdir",
        layout.buildDirectory.dir("tmp/native-sanitizer-test").get().asFile.absolutePath
    )

    doFirst {
        val runtimeOutput = ByteArrayOutputStream()
        exec {
            commandLine("clang", "-print-file-name=libclang_rt.asan-x86_64.so")
            standardOutput = runtimeOutput
        }

        val asanRuntime = file(runtimeOutput.toString().trim()).absoluteFile.normalize()
        check(asanRuntime.isFile) {
            "Clang did not provide a loadable x86_64 ASan runtime: ${asanRuntime.displayPath()}."
        }

        val preloadEntries = listOf(
            asanRuntime.absolutePath,
            System.getenv("LD_PRELOAD").orEmpty()
        ).filter(String::isNotBlank)

        environment("LD_PRELOAD", preloadEntries.joinToString(File.pathSeparator))
        environment(
            "ASAN_OPTIONS",
            "abort_on_error=1:allocator_may_return_null=0:detect_leaks=0:halt_on_error=1:strict_string_checks=1"
        )
        environment("UBSAN_OPTIONS", "halt_on_error=1:print_stacktrace=1")
    }
}

decoderFuzzSmoke.configure {
    mustRunAfter(nativeSanitizerJvmTest)
}

metadataFuzzSmoke.configure {
    mustRunAfter(decoderFuzzSmoke)
}

val nativeSanitizerSmoke by tasks.registering {
    group = "verification"
    description = "Runs sanitizer-instrumented JVM tests plus bounded decoder and metadata fuzz smoke budgets."
    dependsOn(nativeSanitizerJvmTest, decoderFuzzSmoke, metadataFuzzSmoke)

    doFirst {
        check(nativeSanitizerSupported) {
            "nativeSanitizerSmoke requires Linux x86_64 with Clang and libFuzzer."
        }
    }
}

val externalInteropTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs mandatory bidirectional interoperability checks with flac, metaflac, and FFmpeg."
    dependsOn(tasks.testClasses, buildFlacInteropTools)
    shouldRunAfter(tasks.test)

    /* External program behaviour is not represented completely by Gradle's file snapshots. */
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("External executables must run on the current host") { true }

    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("external-interop")
    }
    filter {
        includeTestsMatching("org.zzvsjs.jflac.FlacExternalInteropMatrixTest")
        setFailOnNoMatchingTests(true)
    }

    if (suppliedNativeBundleDirectory == null && nativeTarget != null) {
        dependsOn(syncNativeResources)
    }

    systemProperty(
        "jflac.native.tmpdir",
        layout.buildDirectory.dir("tmp/native-external-interop-test").get().asFile.absolutePath
    )

    doFirst {
        val missingTools = mutableListOf<String>()
        externalInteropTools.forEach { tool ->
            val configuredValue = providers.gradleProperty(tool.gradleProperty).orNull
                ?: providers.environmentVariable(tool.environmentVariable).orNull
                ?: tool.pinnedExecutable?.absolutePath
                ?: tool.commandName
            val executable = findExternalInteropExecutable(configuredValue)
            if (executable == null) {
                missingTools += buildString {
                    append(tool.displayName)
                    append(" (set -P")
                    append(tool.gradleProperty)
                    append("=<path> or ")
                    append(tool.environmentVariable)
                    if (tool.pinnedExecutable == null) {
                        append(", or add '")
                        append(tool.commandName)
                        append("' to PATH)")
                    } else {
                        append("; pinned build output was '")
                        append(tool.pinnedExecutable.absolutePath)
                        append("')")
                    }
                }
            } else {
                systemProperty(tool.gradleProperty, executable.absolutePath)
            }
        }

        check(missingTools.isEmpty()) {
            buildString {
                appendLine("External FLAC interoperability verification requires all external tools.")
                appendLine("Missing or non-executable tools:")
                missingTools.forEach { tool -> appendLine(" - $tool") }
            }
        }
    }
}

tasks.check {
    dependsOn(verifyPackagedJar)
    dependsOn(verifyJavadocJar)
}

tasks.withType<PublishToMavenLocal>().configureEach {
    dependsOn(verifyPackagedJar)

    doFirst {
        check(nativeTarget != null || suppliedNativeBundleDirectory != null) {
            "Publishing requires a supported host target or -Pjflac.nativeBundleDirectory; " +
                "detected $hostOsName/$hostArchitectureName."
        }
    }
}

tasks.withType<PublishToMavenRepository>().configureEach {
    dependsOn(verifyPackagedJar)

    doFirst {
        check(suppliedNativeBundleDirectory != null) {
            "Remote publication requires the CI-assembled universal native bundle via " +
                "-Pjflac.nativeBundleDirectory. Host-only JARs are for local development only."
        }
    }
}

val consumerSmokeTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Publishes jflac to Maven local, then verifies a standalone Java consumer can load it."
    dependsOn("publishToMavenLocal")
    dependsOn(":jflac-java-sound:publishToMavenLocal")
    onlyIf { nativeTarget != null }

    doFirst {
        val consumerProjectDir = file("consumer-smoke-test")
        val sampleFile = layout.buildDirectory.file("consumer-smoke/music.flac").get().asFile
        sampleFile.parentFile.mkdirs()
        require(consumerProjectDir.isDirectory) {
            "Missing consumer smoke test project at ${consumerProjectDir.displayPath()}."
        }

        if (isWindows) {
            commandLine(
                "cmd",
                "/c",
                "call \"${file("gradlew.bat").cmdPath()}\" -p \"${consumerProjectDir.cmdPath()}\" run --no-daemon --stacktrace -PjflacVersion=$version -PjflacSamplePath=\"${sampleFile.cmdPath()}\""
            )
        } else {
            commandLine(
                "bash", file("gradlew").absolutePath,
                "-p", consumerProjectDir.absolutePath,
                "run", "--no-daemon", "--stacktrace",
                "-PjflacVersion=$version",
                "-PjflacSamplePath=${sampleFile.absolutePath}"
            )
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifact(javadocJar)
            artifactId = "jflac"

            pom {
                name.set("jflac")
                description.set("Java and Kotlin JNI wrapper for libFLAC with bundled cross-platform native runtimes.")
                url.set("https://github.com/zzvsjs/jflac")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/license/mit")
                    }
                }
                developers {
                    developer {
                        id.set("zzvsjs")
                        name.set("zzvsjs")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/zzvsjs/jflac.git")
                    developerConnection.set("scm:git:ssh://git@github.com/zzvsjs/jflac.git")
                    url.set("https://github.com/zzvsjs/jflac")
                }
            }
        }
    }
}
