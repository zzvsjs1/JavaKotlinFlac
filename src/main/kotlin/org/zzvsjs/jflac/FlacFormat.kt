package org.zzvsjs.jflac

import org.zzvsjs.jflac.internal.NativeAccess

/**
 * Small public wrapper around libFLAC's format legality helpers.
 *
 * These checks are useful before creating native encoder or metadata state,
 * and they keep Java/Kotlin callers aligned with libFLAC's own rules instead
 * of duplicating the specification by hand.
 *
 * Example:
 *
 * ```
 * require(FlacFormat.isSampleRateValid(sampleRate)) { "Unsupported sample rate" }
 * require(FlacFormat.isVorbisCommentEntryLegal("TITLE=Example"))
 * ```
 *
 * All helpers load the native runtime on demand and can throw
 * [NativeLoadException] before returning a Boolean. "Valid" means the value is
 * legal for the FLAC format; "subset" means it also fits the streamable subset
 * used by the encoder when [FlacEncodingOptions.streamableSubset] is true.
 */
object FlacFormat {
    /** Returns whether [sampleRate] is legal for FLAC at all. */
    @JvmStatic
    fun isSampleRateValid(sampleRate: Int): Boolean {
        FlacNativeLoader.load()
        return NativeAccess.isSampleRateValid(sampleRate)
    }

    /** Returns whether [sampleRate] fits the FLAC streamable subset. */
    @JvmStatic
    fun isSampleRateSubset(sampleRate: Int): Boolean {
        FlacNativeLoader.load()
        return NativeAccess.isSampleRateSubset(sampleRate)
    }

    /**
     * Returns whether [blockSize] is streamable for [sampleRate].
     *
     * Use this before accepting a custom [FlacEncodingOptions.blockSize] when
     * streamable-subset output is required.
     */
    @JvmStatic
    fun isBlockSizeSubset(blockSize: Int, sampleRate: Int): Boolean {
        FlacNativeLoader.load()
        return NativeAccess.isBlockSizeSubset(blockSize, sampleRate)
    }

    /**
     * Returns whether a Vorbis comment field name is legal.
     *
     * The name is the part before `=` in a `KEY=value` entry.
     */
    @JvmStatic
    fun isVorbisCommentNameLegal(name: String): Boolean {
        FlacNativeLoader.load()
        return NativeAccess.isVorbisCommentNameLegal(name)
    }

    /** Returns whether a Vorbis comment value is legal UTF-8 text for FLAC. */
    @JvmStatic
    fun isVorbisCommentValueLegal(value: String): Boolean {
        FlacNativeLoader.load()
        return NativeAccess.isVorbisCommentValueLegal(value)
    }

    /** Returns whether a full `KEY=value` Vorbis comment entry is legal. */
    @JvmStatic
    fun isVorbisCommentEntryLegal(entry: String): Boolean {
        FlacNativeLoader.load()
        return NativeAccess.isVorbisCommentEntryLegal(entry)
    }

    /**
     * Returns `null` when libFLAC considers the picture legal, otherwise a
     * human-readable violation message supplied by libFLAC or the JNI bridge.
     */
    @JvmStatic
    fun pictureViolation(picture: FlacPicture): String? {
        FlacNativeLoader.load()
        return NativeAccess.pictureViolation(picture)
    }
}
