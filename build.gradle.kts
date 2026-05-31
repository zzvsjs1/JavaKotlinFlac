import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.gradle.api.publish.maven.tasks.PublishToMavenLocal
import org.gradle.jvm.tasks.Jar
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.jar.Manifest
import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "2.0.20"
    id("org.jetbrains.dokka-javadoc") version "2.2.0"
    `maven-publish`
}

group = "org.zzvsjs"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test-junit"))
}

val nativeBuildDir = layout.buildDirectory.dir("native")
val nativeOutputDir = nativeBuildDir.map { it.dir("bin") }
val nativeDll = nativeOutputDir.map { it.file("jflac-jni.dll") }
val nativeCompileCommands = nativeBuildDir.map { it.file("compile_commands.json") }
val rootCompileCommands = layout.projectDirectory.file("compile_commands.json")
val generatedResourcesDir = layout.buildDirectory.dir("generated-resources/main")
val nativePlatformId = "windows-x86_64"
val nativeResourceRoot = "META-INF/native/$nativePlatformId"

val flacVersion = "1.5.0"
val flacSourceUrl = "https://github.com/xiph/flac/releases/download/$flacVersion/flac-$flacVersion.tar.xz"
val flacSourceSha256 = "f2c1c76592a82ffff8413ba3c4a1299b6c7ab06c734dee03fd88630485c2b920"
val flacArchive = layout.buildDirectory.file("downloads/flac-$flacVersion.tar.xz")
val flacSourceParentDir = layout.buildDirectory.dir("flac-source")
val flacSourceDir = flacSourceParentDir.map { it.dir("flac-$flacVersion") }
val flacBuildDir = layout.buildDirectory.dir("flac-native")
val flacDll = flacBuildDir.map { it.file("objs/FLAC.dll") }
val flacImportLib = flacBuildDir.map { it.file("src/libFLAC/FLAC.lib") }
val oggVersion = "1.3.6"
val oggSourceUrl = "https://downloads.xiph.org/releases/ogg/libogg-$oggVersion.tar.xz"
val oggSourceSha256 = "5c8253428e181840cd20d41f3ca16557a9cc04bad4a3d04cce84808677fa1061"
val oggArchive = layout.buildDirectory.file("downloads/libogg-$oggVersion.tar.xz")
val oggSourceParentDir = layout.buildDirectory.dir("ogg-source")
val oggSourceDir = oggSourceParentDir.map { it.dir("libogg-$oggVersion") }
val oggBuildDir = layout.buildDirectory.dir("ogg-native")
val oggStaticLib = oggBuildDir.map { it.file("lib/ogg.lib") }
val javaHomeDir = file(System.getProperty("java.home"))
val jniIncludeDir = javaHomeDir.resolve("include")
val jniPlatformIncludeDir = jniIncludeDir.resolve("win32")

val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
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

fun sha256(file: File): String =
    MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

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

val requiredFlacDllSymbols = listOf(
    "FLAC__metadata_chain_new",
    "FLAC__metadata_chain_delete",
    "FLAC__metadata_chain_read",
    "FLAC__metadata_chain_read_ogg",
    "FLAC__metadata_chain_status",
    "FLAC__metadata_chain_write",
    "FLAC__metadata_iterator_new",
    "FLAC__metadata_iterator_delete",
    "FLAC__metadata_iterator_init",
    "FLAC__metadata_iterator_get_block",
    "FLAC__metadata_iterator_next",
    "FLAC__metadata_iterator_delete_block",
    "FLAC__metadata_iterator_insert_block_after",
    "FLAC__stream_decoder_new",
    "FLAC__stream_decoder_delete",
    "FLAC__stream_decoder_init_file",
    "FLAC__stream_decoder_init_ogg_file",
    "FLAC__stream_decoder_init_stream",
    "FLAC__stream_decoder_init_ogg_stream",
    "FLAC__stream_decoder_process_until_end_of_metadata",
    "FLAC__stream_decoder_seek_absolute",
    "FLAC__stream_decoder_process_single",
    "FLAC__stream_decoder_process_until_end_of_stream",
    "FLAC__stream_decoder_finish",
    "FLAC__stream_decoder_get_state",
    "FLAC__stream_decoder_get_resolved_state_string",
    "FLAC__stream_encoder_new",
    "FLAC__stream_encoder_delete",
    "FLAC__stream_encoder_set_verify",
    "FLAC__stream_encoder_set_streamable_subset",
    "FLAC__stream_encoder_set_channels",
    "FLAC__stream_encoder_set_bits_per_sample",
    "FLAC__stream_encoder_set_sample_rate",
    "FLAC__stream_encoder_set_compression_level",
    "FLAC__stream_encoder_set_blocksize",
    "FLAC__stream_encoder_set_total_samples_estimate",
    "FLAC__stream_encoder_set_ogg_serial_number",
    "FLAC__stream_encoder_set_metadata",
    "FLAC__stream_encoder_init_file",
    "FLAC__stream_encoder_init_ogg_file",
    "FLAC__stream_encoder_init_stream",
    "FLAC__stream_encoder_init_ogg_stream",
    "FLAC__stream_encoder_process_interleaved",
    "FLAC__stream_encoder_finish",
    "FLAC__stream_encoder_get_resolved_state_string",
    "FLAC__metadata_object_new",
    "FLAC__metadata_object_delete",
    "FLAC__metadata_object_vorbiscomment_entry_from_name_value_pair",
    "FLAC__metadata_object_vorbiscomment_set_vendor_string",
    "FLAC__metadata_object_vorbiscomment_append_comment",
    "FLAC__metadata_object_picture_set_mime_type",
    "FLAC__metadata_object_picture_set_description",
    "FLAC__metadata_object_picture_set_data",
    "FLAC__metadata_object_picture_is_legal",
    "FLAC__format_sample_rate_is_valid",
    "FLAC__format_sample_rate_is_subset",
    "FLAC__format_blocksize_is_subset",
    "FLAC__format_vorbiscomment_entry_name_is_legal",
    "FLAC__format_vorbiscomment_entry_value_is_legal",
    "FLAC__format_vorbiscomment_entry_is_legal"
)
val forbiddenBundledDllDependencies = listOf(
    "VCRUNTIME",
    "MSVCP",
    "ucrtbase",
    "api-ms-win-crt"
)
val requiredPackagedJarEntries = listOf(
    "META-INF/native/$nativePlatformId/FLAC.dll",
    "META-INF/native/$nativePlatformId/jflac-jni.dll",
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
    withSourcesJar()
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
    inputs.property("jflacFlacVendorPatch", "preserve-user-vorbis-vendors-v1")
    outputs.dir(flacSourceDir)

    doFirst {
        val sourceParent = flacSourceParentDir.get().asFile
        val sourceRoot = flacSourceDir.get().asFile
        sourceRoot.deleteRecursively()
        sourceParent.mkdirs()
        commandLine("tar", "-xf", flacArchive.get().asFile.absolutePath, "-C", sourceParent.absolutePath)
    }

    doLast {
        val source = flacSourceDir.get().file("src/libFLAC/stream_encoder.c").asFile
        val original = "FLAC__add_metadata_block(encoder->protected_->metadata[i], encoder->private_->threadtask[0]->frame, true)"
        val patched = "FLAC__add_metadata_block(encoder->protected_->metadata[i], encoder->private_->threadtask[0]->frame, false)"
        val text = source.readText()

        /*
         * libFLAC normally replaces supplied Vorbis vendors during encode.
         * jflac's ordered metadata mode promises exact non-STREAMINFO block
         * preservation, so only the user metadata loop is patched; the
         * auto-created empty Vorbis comment still uses libFLAC's vendor path.
         */
        require(text.contains(original) || text.contains(patched)) {
            "Unable to locate FLAC metadata vendor update call in ${source.displayPath()}."
        }

        if (text.contains(original)) {
            source.writeText(text.replace(original, patched))
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
    description = "Builds a static libogg library used by bundled FLAC.dll Ogg support."
    dependsOn(extractOggSource)
    inputs.dir(oggSourceDir)
    inputs.property("msvcRuntimeLibrary", msvcRuntimeLibrary)
    outputs.file(oggStaticLib)
    onlyIf { isWindows }

    doFirst {
        val resolvedVcvars64 = vcvars64
            ?: error("Unable to locate Visual Studio C++ tools. Install Desktop C++ tools or set up Visual Studio Build Tools.")
        val resolvedNinjaExe = ninjaExe
            ?: error("Unable to locate Visual Studio Ninja. Install the CMake tools for Visual Studio.")

        oggBuildDir.get().asFile.mkdirs()
        oggBuildDir.get().file("CMakeCache.txt").asFile.delete()
        oggBuildDir.get().dir("CMakeFiles").asFile.deleteRecursively()
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
                    "-DCMAKE_ARCHIVE_OUTPUT_DIRECTORY=\"${oggBuildDir.get().dir("lib").asFile.cmdPath()}\"",
                    "-DCMAKE_RUNTIME_OUTPUT_DIRECTORY=\"${oggBuildDir.get().dir("bin").asFile.cmdPath()}\"",
                    "-DINSTALL_DOCS=OFF"
                ).joinToString(" "),
                "\"${resolvedNinjaExe.cmdPath()}\" -C \"${oggBuildDir.get().asFile.cmdPath()}\" ogg -v"
            ).joinToString(" && ")
        )
    }
}

val buildFlacNative by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds FLAC.dll from the official FLAC $flacVersion source archive."
    dependsOn(extractFlacSource, buildOggNative)
    inputs.dir(flacSourceDir)
    inputs.dir(oggSourceDir.map { it.dir("include") })
    inputs.file(oggStaticLib)
    inputs.property("msvcRuntimeLibrary", msvcRuntimeLibrary)
    outputs.files(flacDll, flacImportLib)
    onlyIf { isWindows }

    doFirst {
        val resolvedVcvars64 = vcvars64
            ?: error("Unable to locate Visual Studio C++ tools. Install Desktop C++ tools or set up Visual Studio Build Tools.")
        val resolvedNinjaExe = ninjaExe
            ?: error("Unable to locate Visual Studio Ninja. Install the CMake tools for Visual Studio.")

        flacBuildDir.get().asFile.mkdirs()
        flacBuildDir.get().file("CMakeCache.txt").asFile.delete()
        flacBuildDir.get().dir("CMakeFiles").asFile.deleteRecursively()
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
                    "-DOGG_INCLUDE_DIR=\"${oggSourceDir.get().dir("include").asFile.cmdPath()}\"",
                    "-DOGG_LIBRARY=\"${oggStaticLib.get().asFile.cmdPath()}\"",
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
    }
}

val buildNative by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the Windows x64 JNI shim."
    dependsOn(extractFlacSource)
    inputs.dir(file("native"))
    inputs.dir(jniIncludeDir)
    inputs.dir(jniPlatformIncludeDir)
    inputs.dir(flacSourceDir.map { it.dir("include") })
    inputs.property("msvcRuntimeLibrary", msvcRuntimeLibrary)
    outputs.files(nativeDll, nativeCompileCommands, rootCompileCommands)
    onlyIf { isWindows }

    doFirst {
        val resolvedVcvars64 = vcvars64
            ?: error("Unable to locate Visual Studio C++ tools. Install Desktop C++ tools or set up Visual Studio Build Tools.")
        val resolvedNinjaExe = ninjaExe
            ?: error("Unable to locate Visual Studio Ninja. Install the CMake tools for Visual Studio.")
        require(jniIncludeDir.resolve("jni.h").isFile) {
            "Unable to locate jni.h under ${jniIncludeDir.displayPath()}."
        }
        require(jniPlatformIncludeDir.resolve("jni_md.h").isFile) {
            "Unable to locate Windows JNI platform headers under ${jniPlatformIncludeDir.displayPath()}."
        }

        nativeBuildDir.get().asFile.mkdirs()
        nativeBuildDir.get().file("CMakeCache.txt").asFile.delete()
        nativeBuildDir.get().dir("CMakeFiles").asFile.deleteRecursively()
        environment("JAVA_HOME", javaHomeDir.absolutePath)
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
                    "-DJFLAC_FLAC_INCLUDE_DIR=\"${flacSourceDir.get().dir("include").asFile.cmdPath()}\""
                ).joinToString(" "),
                "cmake -E copy_if_different \"${nativeCompileCommands.get().asFile.cmdPath()}\" \"${rootCompileCommands.asFile.cmdPath()}\"",
                "\"${resolvedNinjaExe.cmdPath()}\" -C \"${nativeBuildDir.get().asFile.cmdPath()}\" -v"
            ).joinToString(" && ")
        )
    }
}

val verifyBundledFlacDll by tasks.registering {
    group = "verification"
    description = "Verifies that the built FLAC.dll exports the symbols required by the JNI wrapper."
    dependsOn(buildFlacNative)
    inputs.file(flacDll)
    onlyIf { isWindows }

    doLast {
        val resolvedDumpbinExe = dumpbinExe
            ?: error("Unable to locate dumpbin.exe. Install Visual Studio C++ tools to verify the built FLAC.dll exports.")
        val bundledFlacDll = flacDll.get().asFile
        require(bundledFlacDll.exists()) {
            "Missing built FLAC.dll at ${bundledFlacDll.displayPath()}"
        }

        val output = ByteArrayOutputStream()
        exec {
            commandLine(resolvedDumpbinExe.absolutePath, "/exports", bundledFlacDll.absolutePath)
            standardOutput = output
            errorOutput = output
        }

        val exportListing = output.toString()
        val missingSymbols = requiredFlacDllSymbols.filterNot(exportListing::contains)
        check(missingSymbols.isEmpty()) {
            buildString {
                appendLine("Built FLAC.dll is missing JNI-required exports.")
                appendLine("DLL: ${bundledFlacDll.displayPath()}")
                appendLine("Missing symbols:")
                missingSymbols.forEach { symbol -> appendLine(" - $symbol") }
            }
        }
    }
}

val verifyBundledDllDependencies by tasks.registering {
    group = "verification"
    description = "Verifies that bundled DLLs do not depend on redistributable MSVC runtime DLLs."
    dependsOn(buildNative, buildFlacNative)
    inputs.files(nativeDll, flacDll)
    onlyIf { isWindows }

    doLast {
        val resolvedDumpbinExe = dumpbinExe
            ?: error("Unable to locate dumpbin.exe. Install Visual Studio C++ tools to verify DLL dependencies.")
        val dlls = listOf(nativeDll.get().asFile, flacDll.get().asFile)

        dlls.forEach { dll ->
            require(dll.exists()) {
                "Missing bundled DLL at ${dll.displayPath()}"
            }

            val output = ByteArrayOutputStream()
            exec {
                commandLine(resolvedDumpbinExe.absolutePath, "/dependents", dll.absolutePath)
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
                    appendLine("DLL: ${dll.displayPath()}")
                    appendLine("Forbidden dependency patterns:")
                    forbiddenDependencies.forEach { dependency -> appendLine(" - $dependency") }
                    appendLine("Use static MSVC runtime linking for bundled Windows DLLs.")
                }
            }
        }
    }
}

val syncNativeResources by tasks.registering(Sync::class) {
    group = "build"
    description = "Stages native runtime libraries into the JAR resources."
    dependsOn(buildNative, verifyBundledFlacDll, verifyBundledDllDependencies)
    onlyIf { isWindows }
    into(generatedResourcesDir)
    from(flacDll) {
        into(nativeResourceRoot)
    }
    from(nativeDll) {
        into(nativeResourceRoot)
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
    onlyIf { isWindows }

    val packagedJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    inputs.file(packagedJar)

    doLast {
        ZipFile(packagedJar.get().asFile).use { jar ->
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
    if (isWindows) {
        dependsOn(syncNativeResources)
        from(syncNativeResources)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
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
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

tasks.test {
    if (isWindows) {
        dependsOn(syncNativeResources)
    }
    systemProperty("jflac.native.tmpdir", layout.buildDirectory.dir("tmp/native-test").get().asFile.absolutePath)
}

tasks.check {
    dependsOn(verifyPackagedJar)
    dependsOn(verifyJavadocJar)
}

tasks.withType<PublishToMavenLocal>().configureEach {
    dependsOn(verifyPackagedJar)

    doFirst {
        check(isWindows) {
            "Publishing is currently Windows-only because this artefact must include bundled Windows x64 native libraries."
        }
    }
}

val consumerSmokeTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Publishes jflac to Maven local, then verifies a standalone Java consumer can load it."
    dependsOn("publishToMavenLocal")
    dependsOn(":jflac-java-sound:publishToMavenLocal")
    onlyIf { isWindows }

    doFirst {
        val consumerProjectDir = file("consumer-smoke-test")
        val sampleFile = layout.buildDirectory.file("consumer-smoke/music.flac").get().asFile
        sampleFile.parentFile.mkdirs()
        require(consumerProjectDir.isDirectory) {
            "Missing consumer smoke test project at ${consumerProjectDir.displayPath()}."
        }

        commandLine(
            "cmd",
            "/c",
            "call \"${file("gradlew.bat").cmdPath()}\" -p \"${consumerProjectDir.cmdPath()}\" run --no-daemon --stacktrace -PjflacVersion=$version -PjflacSamplePath=\"${sampleFile.cmdPath()}\""
        )
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
                description.set("Java and Kotlin JNI wrapper for libFLAC with a bundled Windows x64 native runtime.")
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
