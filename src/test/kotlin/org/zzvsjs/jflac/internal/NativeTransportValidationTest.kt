package org.zzvsjs.jflac.internal

import org.zzvsjs.jflac.FlacPaddingBlock
import org.zzvsjs.jflac.FlacStreamInfo
import kotlin.test.Test
import kotlin.test.assertFailsWith

class NativeTransportValidationTest {
    @Test
    fun nativeEncodingRequestRejectsOrderedMetadataLengthMismatch() {
        assertFailsWith<IllegalArgumentException> {
            encodingRequest(
                metadataBlockTypes = intArrayOf(4),
                metadataBlockValues = emptyArray()
            )
        }
    }

    @Test
    fun nativeMetadataEditRequestRejectsWrongOrderedVorbisValueType() {
        assertFailsWith<IllegalArgumentException> {
            NativeMetadataEditRequest(
                emptyArray(),
                emptyArray(),
                emptyArray(),
                emptyArray(),
                emptyArray(),
                emptyArray(),
                emptyArray(),
                intArrayOf(4),
                arrayOf(FlacPaddingBlock(0))
            )
        }
    }

    @Test
    fun nativeMetadataPayloadRejectsOrderedMetadataLengthMismatch() {
        assertFailsWith<IllegalArgumentException> {
            NativeMetadataPayload(
                streamInfo(),
                null,
                emptyArray(),
                emptyArray(),
                emptyArray(),
                emptyArray(),
                emptyArray(),
                emptyArray(),
                emptyArray(),
                intArrayOf(4),
                intArrayOf()
            )
        }
    }

    @Test
    fun nativeMetadataEditRequestRejectsNullArrayArguments() {
        assertFailsWith<NullPointerException> {
            NativeMetadataEditRequest(
                null,
                emptyArray(),
                emptyArray(),
                emptyArray(),
                emptyArray(),
                emptyArray(),
                emptyArray(),
                intArrayOf(),
                emptyArray()
            )
        }
    }

    private fun encodingRequest(
        metadataBlockTypes: IntArray = intArrayOf(),
        metadataBlockValues: Array<Any> = emptyArray()
    ): NativeEncodingRequest {
        return NativeEncodingRequest(
            44_100,
            2,
            16,
            null,
            5,
            true,
            true,
            null,
            0,
            null,
            emptyArray(),
            emptyArray(),
            emptyArray(),
            emptyArray(),
            emptyArray(),
            emptyArray(),
            emptyArray(),
            metadataBlockTypes,
            metadataBlockValues,
            1
        )
    }

    private fun streamInfo(): FlacStreamInfo {
        return FlacStreamInfo(
            sampleRate = 44_100,
            channels = 2,
            bitsPerSample = 16,
            totalSamples = 1,
            minBlockSize = 16,
            maxBlockSize = 16,
            minFrameSize = 0,
            maxFrameSize = 0,
            md5Signature = ByteArray(16)
        )
    }
}
