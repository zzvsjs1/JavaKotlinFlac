package org.zzvsjs.jflac

import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import org.zzvsjs.jflac.internal.NativeAccess
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

/**
 * Extracts and loads the bundled native runtime for the current platform.
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
     * The dependency runtime is loaded before the JNI shim on every platform.
     * The shim then resolves the exact sibling libFLAC path as a second defence
     * against an unrelated system library satisfying only part of the API.
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

            /*
             * Loading a DLL only proves that the operating-system loader could
             * map it. Resolve the complete libFLAC contract now so version,
             * Ogg capability, and missing-symbol errors are reported by load().
             */
            NativeAccess.verifyRuntime()
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
     * accidentally reuse stale native libraries from a previous version.
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
            val bytes = openResource(resourcePath).use { input -> input.readBytes() }
            digest.update(resourcePath.toByteArray(Charsets.UTF_8))
            digest.update(bytes)
        }

        return digest.digest().joinToString("") { "%02x".format(it) }.take(12)
    }

    /**
     * Copies a bundled resource through a same-directory temporary file and an
     * atomic move. Existing files are accepted only after their content digest
     * matches the resource, which repairs interrupted or externally corrupted
     * extractions instead of attempting to load them.
     */
    private fun extractResource(resourcePath: String, targetDirectory: Path): Path {
        val targetFile = targetDirectory.resolve(resourcePath.substringAfterLast('/'))
        val resourceBytes = openResource(resourcePath).use(InputStream::readBytes)
        val expectedDigest = MessageDigest.getInstance("SHA-256").digest(resourceBytes)
        if (Files.isRegularFile(targetFile, LinkOption.NOFOLLOW_LINKS) &&
            MessageDigest.isEqual(expectedDigest, MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(targetFile)))
        ) {
            return targetFile
        }

        val temporaryFile = Files.createTempFile(targetDirectory, "${targetFile.fileName}.", ".tmp")
        try {
            Files.write(temporaryFile, resourceBytes)
            try {
                Files.move(
                    temporaryFile,
                    targetFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporaryFile, targetFile, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporaryFile)
        }

        val extractedDigest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(targetFile))
        if (!MessageDigest.isEqual(expectedDigest, extractedDigest)) {
            throw NativeLoadException("Extracted native resource failed verification: $resourcePath")
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
        private val LINUX_X64 = NativePlatform(
            id = "linux-x86_64",
            resourceRoot = "META-INF/native/linux-x86_64",
            libraries = listOf(
                NativeLibrary("libFLAC.so.14"),
                NativeLibrary("libjflac-jni.so")
            )
        )
        private val MACOS_X64 = NativePlatform(
            id = "macos-x86_64",
            resourceRoot = "META-INF/native/macos-x86_64",
            libraries = listOf(
                NativeLibrary("libFLAC.14.dylib"),
                NativeLibrary("libjflac-jni.dylib")
            )
        )
        private val MACOS_ARM64 = NativePlatform(
            id = "macos-aarch64",
            resourceRoot = "META-INF/native/macos-aarch64",
            libraries = listOf(
                NativeLibrary("libFLAC.14.dylib"),
                NativeLibrary("libjflac-jni.dylib")
            )
        )
        private val SUPPORTED_PLATFORMS = listOf(WINDOWS_X64, LINUX_X64, MACOS_X64, MACOS_ARM64)

        fun current(): NativePlatform = detect(
            osName = System.getProperty("os.name"),
            osArch = System.getProperty("os.arch")
        )

        fun detect(osName: String, osArch: String): NativePlatform {
            val detectedOs = normaliseOperatingSystem(osName) ?: diagnosticComponent(osName)
            val detectedArch = normaliseArchitecture(osArch) ?: diagnosticComponent(osArch)
            val platformId = "$detectedOs-$detectedArch"

            SUPPORTED_PLATFORMS.firstOrNull { platform -> platform.id == platformId }?.let { return it }

            throw UnsupportedFeatureException(
                "Native FLAC runtime is not available for $platformId. Supported resource targets: " +
                    SUPPORTED_PLATFORMS.joinToString { platform -> platform.id } + "."
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
                "amd64", "x86_64", "x64" -> "x86_64"
                "aarch64", "arm64" -> "aarch64"
                else -> null
            }
        }

        private fun diagnosticComponent(value: String): String = value
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifEmpty { "unknown" }
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
