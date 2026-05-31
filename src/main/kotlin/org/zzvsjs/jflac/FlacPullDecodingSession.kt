package org.zzvsjs.jflac

/**
 * Pull-based FLAC decoder session backed by one native libFLAC decoder.
 *
 * This API is designed for byte-stream adapters such as Java Sound, where the
 * caller asks for more decoded PCM only when its consumer reads. It avoids a
 * background decode thread and avoids buffering the entire FLAC stream in JVM
 * memory.
 *
 * A frame is one sample for every channel at one time position. For stereo, a
 * frame contains one left sample and one right sample. [readInterleaved] writes
 * samples in frame-major order: left, right, left, right, and so on.
 */
interface FlacPullDecodingSession : AutoCloseable {
    /** STREAMINFO read while the native decoder was opened. */
    val streamInfo: FlacStreamInfo

    /**
     * Decodes up to [maxFrames] PCM frames into [interleavedSamples].
     *
     * The buffer must contain space for `maxFrames * streamInfo.channels`
     * integer samples. The returned value is a frame count, not a raw sample
     * count:
     *
     * - positive value: that many PCM frames were written
     * - `0`: only returned when [maxFrames] is `0`
     * - `-1`: end-of-stream has already been reached
     */
    fun readInterleaved(interleavedSamples: IntArray, maxFrames: Int): Int

    /** Releases the native decoder handle. */
    override fun close()
}
