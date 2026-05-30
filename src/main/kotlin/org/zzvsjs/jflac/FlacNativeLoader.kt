package org.zzvsjs.jflac

import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

/**
 * Extracts and loads bundled Windows x64 native runtime dependencies.
 *
 * The loader is deterministic:
 * - resources are loaded from the JAR in dependency order
 * - extraction paths include version + content hash to avoid collisions
 * - repeated calls are idempotent inside the current JVM
 */
object FlacNativeLoader {
    private const val VERSION_FALLBACK = "dev"

    @Volatile
    private var extractedDirectory: Path? = null

    /**
     * Ensures the native runtime is available and returns the extraction path.
     *
     * Loading order matters on Windows because `jflac-jni.dll` depends on
     * `FLAC.dll`.
     */
    @Synchronized
    fun load(): Path {
        extractedDirectory?.let { return it }

        val platform = NativePlatform.current()
        val targetDirectory = extractionDirectory(platform)
        targetDirectory.createDirectories()

        val extractedLibraries = platform.libraries.associateWith { library ->
            extractResource(library.resourcePath(platform), targetDirectory)
        }

        try {
            platform.libraries.forEach { library ->
                System.load(extractedLibraries.getValue(library).toAbsolutePath().toString())
            }
        } catch (e: UnsatisfiedLinkError) {
            throw NativeLoadException("Failed to load native libraries.", e)
        }

        extractedDirectory = targetDirectory
        return targetDirectory
    }

    /** Exposed mainly for tests so they can verify extraction behaviour. */
    fun loadedDirectory(): Path? = extractedDirectory

    /**
     * Computes the stable directory where native files are extracted.
     *
     * The hash is based on the embedded binary contents so a new build cannot
     * accidentally reuse stale DLLs from a previous version.
     */
    private fun extractionDirectory(platform: NativePlatform): Path {
        val version = javaClass.`package`?.implementationVersion ?: VERSION_FALLBACK
        val hash = bundleHash(platform)
        val baseDirectory = System.getProperty("jflac.native.tmpdir")?.let(Path::of)
            ?: Path.of(System.getProperty("java.io.tmpdir"))
        return baseDirectory.resolve("jflac-$version-${platform.id}-$hash")
    }

    /** Builds a short SHA-256 fingerprint across all embedded native files. */
    private fun bundleHash(platform: NativePlatform): String {
        val digest = MessageDigest.getInstance("SHA-256")
        platform.libraries.map { it.resourcePath(platform) }.forEach { resourcePath ->
            val bytes = openResource(resourcePath).readBytes()
            digest.update(resourcePath.toByteArray(Charsets.UTF_8))
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(12)
    }

    /**
     * Copies a bundled resource to disk if it is not already present.
     *
     * V1 keeps extraction simple: overwrite semantics are allowed and the hash
     * in the directory name is relied on for cache invalidation.
     */
    private fun extractResource(resourcePath: String, targetDirectory: Path): Path {
        val targetFile = targetDirectory.resolve(resourcePath.substringAfterLast('/'))
        openResource(resourcePath).use { input ->
            if (!targetFile.exists()) {
                Files.copy(input, targetFile, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        return targetFile
    }

    private fun openResource(resourcePath: String) =
        javaClass.classLoader.getResourceAsStream(resourcePath)
            ?: throw NativeLoadException("Missing native resource: $resourcePath")
}

internal data class NativePlatform(
    val id: String,
    val resourceRoot: String,
    val libraries: List<NativeLibrary>
) {
    companion object {
        private val WINDOWS_X64 = NativePlatform(
            id = "windows-x86_64",
            resourceRoot = "META-INF/native/windows-x86_64",
            libraries = listOf(
                NativeLibrary("FLAC.dll"),
                NativeLibrary("jflac-jni.dll")
            )
        )

        fun current(): NativePlatform = detect(
            osName = System.getProperty("os.name"),
            osArch = System.getProperty("os.arch")
        )

        fun detect(osName: String, osArch: String): NativePlatform {
            val detectedOs = normaliseOperatingSystem(osName)
            val detectedArch = normaliseArchitecture(osArch)
            val platformId = listOf(detectedOs, detectedArch)
                .filterNotNull()
                .joinToString("-")
                .ifEmpty { "${osName.lowercase(Locale.ROOT)}-${osArch.lowercase(Locale.ROOT)}" }

            if (platformId == WINDOWS_X64.id) {
                return WINDOWS_X64
            }

            throw UnsupportedFeatureException(
                "Native FLAC runtime is not available for $platformId. This build bundles ${WINDOWS_X64.id} only."
            )
        }

        private fun normaliseOperatingSystem(osName: String): String? {
            val value = osName.lowercase(Locale.ROOT)
            return when {
                "windows" in value -> "windows"
                "linux" in value -> "linux"
                "mac" in value || "darwin" in value -> "macos"
                else -> null
            }
        }

        private fun normaliseArchitecture(osArch: String): String? {
            return when (osArch.lowercase(Locale.ROOT)) {
                "amd64", "x86_64" -> "x86_64"
                "aarch64", "arm64" -> "aarch64"
                else -> null
            }
        }
    }
}

internal data class NativeLibrary(val fileName: String) {
    fun resourcePath(platform: NativePlatform): String {
        return "${platform.resourceRoot}/$fileName"
    }
}

internal enum class NativeFlacContainer(val nativeCode: Int) {
    NATIVE(0),
    OGG(1)
}

internal data class NativeFlacPath(
    val path: Path,
    val container: NativeFlacContainer
)

internal data class NativeFlacStream(
    val input: InputStream,
    val container: NativeFlacContainer
)

/**
 * Performs cheap JVM-side validation before control crosses into JNI.
 *
 * This keeps common errors, such as missing files, out of the native layer
 * where diagnostics are harder to evolve. The detected container also lets
 * callers route native FLAC and Ogg FLAC to the matching libFLAC entry point.
 */
internal fun inspectNativeFlacPath(path: Path): NativeFlacPath {
    val normalized = path.toAbsolutePath().normalize()
    if (!normalized.exists() || !normalized.isRegularFile()) {
        throw FlacDecodeException("FLAC file does not exist or is not a regular file.")
    }

    val magic = ByteArray(4)
    try {
        Files.newInputStream(normalized).use { input ->
            val read = input.read(magic)
            return NativeFlacPath(normalized, detectNativeFlacContainer(magic, read))
        }
    } catch (e: IOException) {
        throw FlacDecodeException("Failed to inspect FLAC file.", e)
    }
}

internal fun inspectNativeFlacStream(input: InputStream): NativeFlacStream {
    val magic = ByteArray(4)
    return try {
        if (input.markSupported()) {
            input.mark(magic.size)
            val read = readMagic(input, magic)
            input.reset()
            NativeFlacStream(input, detectNativeFlacContainer(magic, read))
        } else {
            val pushback = PushbackInputStream(input, magic.size)
            val read = readMagic(pushback, magic)
            if (read > 0) {
                pushback.unread(magic, 0, read)
            }
            NativeFlacStream(pushback, detectNativeFlacContainer(magic, read))
        }
    } catch (e: IOException) {
        throw FlacDecodeException("Failed to inspect FLAC stream.", e)
    }
}

internal fun inspectNativeFlacChannel(channel: SeekableByteChannel): NativeFlacContainer {
    val originalPosition = channel.position()
    val magic = ByteArray(4)
    val buffer = ByteBuffer.wrap(magic)
    return try {
        val read = channel.read(buffer)
        detectNativeFlacContainer(magic, read)
    } catch (e: IOException) {
        throw FlacDecodeException("Failed to inspect FLAC channel.", e)
    } finally {
        channel.position(originalPosition)
    }
}

internal fun validateNativeFlacMetadataEditPath(path: Path): Path {
    val inspected = inspectNativeFlacPath(path)
    if (inspected.container == NativeFlacContainer.OGG) {
        throw UnsupportedFeatureException("Ogg FLAC metadata editing is not supported.")
    }
    return inspected.path
}

private fun detectNativeFlacContainer(magic: ByteArray, bytesRead: Int): NativeFlacContainer {
    return if (bytesRead == 4 && magic.contentEquals(byteArrayOf(0x4f, 0x67, 0x67, 0x53))) {
        NativeFlacContainer.OGG
    } else {
        NativeFlacContainer.NATIVE
    }
}

private fun readMagic(input: InputStream, magic: ByteArray): Int {
    var totalRead = 0
    while (totalRead < magic.size) {
        val read = input.read(magic, totalRead, magic.size - totalRead)
        if (read < 0) {
            break
        }
        totalRead += read
    }
    return totalRead
}
