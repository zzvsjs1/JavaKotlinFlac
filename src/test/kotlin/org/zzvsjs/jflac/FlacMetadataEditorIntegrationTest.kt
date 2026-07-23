package org.zzvsjs.jflac

import org.junit.jupiter.api.Assumptions.assumeFalse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.DosFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val POSIX_WRITE_PERMISSIONS = setOf(
    PosixFilePermission.OWNER_WRITE,
    PosixFilePermission.GROUP_WRITE,
    PosixFilePermission.OTHERS_WRITE
)

class FlacMetadataEditorIntegrationTest {
    @Test
    fun editUpdatesVorbisCommentsWithoutChangingAudioFrames() {
        val frames = 21
        val format = FlacAudioFormat(
            sampleRate = 44_100,
            channels = 2,
            bitsPerSample = 16,
            totalSamplesEstimate = frames.toLong()
        )
        val samples = deterministicPcm(frames, format)
        val application = FlacApplicationBlock(
            id = byteArrayOf('J'.code.toByte(), 'F'.code.toByte(), 'L'.code.toByte(), 'C'.code.toByte()),
            data = byteArrayOf(0x01, 0x02, 0x03)
        )
        val output = Files.createTempFile("jflac-metadata-edit", ".flac")

        try {
            FlacEncoder().encode(
                output = output,
                format = format,
                samples = samples,
                metadata = FlacEncodingMetadata(
                    comments = mapOf("TITLE" to listOf("Before")),
                    applicationBlocks = listOf(application),
                    paddingBlocks = listOf(FlacPaddingBlock(length = 256))
                )
            )
            val decodedBefore = FlacDecoder().decode(output)

            FlacMetadataEditor().edit(output) { session ->
                assertEquals(3, session.blocks.size)
                session.setVorbisComments(
                    mapOf(
                        "TITLE" to listOf("After"),
                        "ARTIST" to listOf("Example Artist")
                    )
                )
            }

            val metadataAfter = FlacMetadataReader().read(output)
            val decodedAfter = FlacDecoder().decode(output)

            assertEquals(listOf("After"), metadataAfter.vorbisComment?.comments?.get("TITLE"))
            assertEquals(listOf("Example Artist"), metadataAfter.vorbisComment?.comments?.get("ARTIST"))
            assertEquals(1, metadataAfter.applicationBlocks.size)
            assertContentEquals(application.id, metadataAfter.applicationBlocks.single().id)
            assertContentEquals(application.data, metadataAfter.applicationBlocks.single().data)
            assertContentEquals(decodedBefore.interleavedSamples, decodedAfter.interleavedSamples)
            assertEquals(decodedBefore.streamInfo, decodedAfter.streamInfo)
        } finally {
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun replaceReplacesNonStreamInfoBlocksInCallerOrder() {
        val frames = 12
        val format = FlacAudioFormat(
            sampleRate = 32_000,
            channels = 1,
            bitsPerSample = 16,
            totalSamplesEstimate = frames.toLong()
        )
        val samples = deterministicPcm(frames, format)
        val padding = FlacPaddingBlock(length = 11)
        val application = FlacApplicationBlock(
            id = byteArrayOf('E'.code.toByte(), 'D'.code.toByte(), 'I'.code.toByte(), 'T'.code.toByte()),
            data = byteArrayOf(0x10, 0x20)
        )
        val comment = FlacVorbisComment(
            vendor = "ignored",
            comments = mapOf("TITLE" to listOf("Edited order"))
        )
        val output = Files.createTempFile("jflac-metadata-block-edit", ".flac")

        try {
            FlacEncoder().encode(
                output = output,
                format = format,
                samples = samples,
                metadata = FlacEncodingMetadata(comments = mapOf("TITLE" to listOf("Original")))
            )
            val decodedBefore = FlacDecoder().decode(output)

            FlacMetadataEditor().replace(
                output,
                FlacEncodingMetadata(
                    blocks = listOf(
                        FlacMetadataBlock.Padding(padding),
                        FlacMetadataBlock.Application(application),
                        FlacMetadataBlock.VorbisComment(comment)
                    )
                )
            )

            val blocks = FlacMetadataReader().read(output).blocks
            val decodedAfter = FlacDecoder().decode(output)

            assertIs<FlacMetadataBlock.Padding>(blocks[0])
            assertEquals(padding, (blocks[0] as FlacMetadataBlock.Padding).padding)
            assertIs<FlacMetadataBlock.Application>(blocks[1])
            assertContentEquals(application.id, (blocks[1] as FlacMetadataBlock.Application).application.id)
            assertContentEquals(application.data, (blocks[1] as FlacMetadataBlock.Application).application.data)
            assertIs<FlacMetadataBlock.VorbisComment>(blocks[2])
            assertEquals(listOf("Edited order"), (blocks[2] as FlacMetadataBlock.VorbisComment).comment.comments["TITLE"])
            assertContentEquals(decodedBefore.interleavedSamples, decodedAfter.interleavedSamples)
        } finally {
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun replacePreservesOrderedVorbisVendor() {
        val output = createSmallFlac("jflac-vorbis-vendor-edit")
        val requestedVendor = "metadata-editor-vendor"

        try {
            FlacMetadataEditor().replace(
                output,
                FlacEncodingMetadata(
                    blocks = listOf(
                        FlacMetadataBlock.VorbisComment(
                            FlacVorbisComment(
                                vendor = requestedVendor,
                                comments = mapOf("TITLE" to listOf("Vendor preserved"))
                            )
                        )
                    )
                )
            )

            val metadata = FlacMetadataReader().read(output)
            assertEquals(requestedVendor, metadata.vorbisComment?.vendor)
            assertEquals(listOf("Vendor preserved"), metadata.vorbisComment?.comments?.get("TITLE"))
        } finally {
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun replaceRejectsMalformedFlac() {
        val malformed = Files.createTempFile("jflac-malformed-metadata-edit", ".flac")

        try {
            Files.write(
                malformed,
                byteArrayOf('n'.code.toByte(), 'o'.code.toByte(), 'p'.code.toByte(), 'e'.code.toByte())
            )

            assertFailsWith<FlacMetadataEditException> {
                FlacMetadataEditor().replace(
                    malformed,
                    FlacEncodingMetadata(comments = mapOf("TITLE" to listOf("Invalid")))
                )
            }
        } finally {
            Files.deleteIfExists(malformed)
        }
    }

    @Test
    fun editRejectsMalformedFlacBeforeOpeningEditSession() {
        val malformed = Files.createTempFile("jflac-malformed-edit-session", ".flac")

        try {
            Files.write(
                malformed,
                byteArrayOf('n'.code.toByte(), 'o'.code.toByte(), 'p'.code.toByte(), 'e'.code.toByte())
            )

            assertFailsWith<FlacDecodeException> {
                FlacMetadataEditor().edit(malformed) { session ->
                    session.setVorbisComments(mapOf("TITLE" to listOf("Invalid")))
                }
            }
        } finally {
            Files.deleteIfExists(malformed)
        }
    }

    @Test
    fun editRejectsInvalidVorbisCommentKeysBeforeNativeWrite() {
        val output = createSmallFlac("jflac-invalid-metadata-edit")

        try {
            assertFailsWith<IllegalArgumentException> {
                FlacMetadataEditor().edit(output) { session ->
                    session.setVorbisComments(mapOf("BAD=KEY" to listOf("value")))
                }
            }

            val metadataAfter = FlacMetadataReader().read(output)
            assertEquals(listOf("Original"), metadataAfter.vorbisComment?.comments?.get("TITLE"))
        } finally {
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun editReportsReadOnlyFileWriteFailure() {
        val output = createSmallFlac("jflac-read-only-metadata-edit")

        try {
            withReadOnlyFile(output) {
                assertFailsWith<FlacMetadataEditException> {
                    /*
                     * Keeping the replacement value at the same UTF-8 length as
                     * "Original" and disabling padding forces libFLAC down its
                     * in-place r+b path. A length-changing edit may instead use
                     * a sibling temporary file, whose replacement permissions
                     * are controlled by the parent directory on POSIX systems.
                     */
                    FlacMetadataEditor().edit(
                        output,
                        FlacMetadataEditOptions(usePadding = false)
                    ) { session ->
                        session.setVorbisComments(mapOf("TITLE" to listOf("Rejected")))
                    }
                }
            }

            val metadataAfter = FlacMetadataReader().read(output)
            assertEquals(listOf("Original"), metadataAfter.vorbisComment?.comments?.get("TITLE"))
        } finally {
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun oggFlacMetadataCanBeReadButNotEdited() {
        val oggFile = createOggFlacFixture("jflac-ogg-edit")

        try {
            val metadata = FlacMetadataReader().read(oggFile)

            assertEquals(8_000, metadata.streamInfo.sampleRate)
            val error = assertFailsWith<UnsupportedFeatureException> {
                FlacMetadataEditor().replace(
                    oggFile,
                    FlacEncodingMetadata(comments = mapOf("TITLE" to listOf("Edited")))
                )
            }

            assertTrue(error.message!!.contains("Ogg FLAC metadata editing"))
        } finally {
            Files.deleteIfExists(oggFile)
        }
    }

    private fun createSmallFlac(prefix: String): Path {
        val frames = 8
        val format = FlacAudioFormat(
            sampleRate = 22_050,
            channels = 1,
            bitsPerSample = 16,
            totalSamplesEstimate = frames.toLong()
        )
        val output = Files.createTempFile(prefix, ".flac")
        FlacEncoder().encode(
            output = output,
            format = format,
            samples = deterministicPcm(frames, format),
            metadata = FlacEncodingMetadata(comments = mapOf("TITLE" to listOf("Original")))
        )
        return output
    }

    private fun withReadOnlyFile(path: Path, action: () -> Unit) {
        val posixView = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
        if (posixView != null) {
            val originalPermissions = posixView.readAttributes().permissions().toSet()

            try {
                posixView.setPermissions(originalPermissions - POSIX_WRITE_PERMISSIONS)
                assumeFalse(
                    Files.isWritable(path),
                    "The current process can bypass POSIX write permissions, so this test cannot verify a write failure."
                )

                action()
            } finally {
                posixView.setPermissions(originalPermissions)
            }

            return
        }

        val dosView = requireNotNull(Files.getFileAttributeView(path, DosFileAttributeView::class.java)) {
            "The test filesystem exposes neither POSIX nor DOS file attributes."
        }
        val originalReadOnly = dosView.readAttributes().isReadOnly

        try {
            dosView.setReadOnly(true)
            action()
        } finally {
            dosView.setReadOnly(originalReadOnly)
        }
    }

    private fun deterministicPcm(frames: Int, format: FlacAudioFormat): IntArray {
        val minSample = -(1 shl (format.bitsPerSample - 1))
        val range = 1 shl format.bitsPerSample
        return IntArray(frames * format.channels) { sampleIndex ->
            val frame = sampleIndex / format.channels
            val channel = sampleIndex % format.channels
            ((frame * 89 + channel * 131) % range) + minSample
        }
    }
}
