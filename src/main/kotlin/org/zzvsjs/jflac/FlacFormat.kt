package org.zzvsjs.jflac

import org.zzvsjs.jflac.internal.NativeBindings

/**
 * Small public wrapper around libFLAC's format legality helpers.
 *
 * These checks are useful before creating native encoder or metadata state,
 * and they keep Java/Kotlin callers aligned with libFLAC's own rules instead
 * of duplicating the specification by hand.
 */
object FlacFormat {
    @JvmStatic
    fun isSampleRateValid(sampleRate: Int): Boolean {
        FlacNativeLoader.load()
        return NativeBindings.isSampleRateValid(sampleRate)
    }

    @JvmStatic
    fun isSampleRateSubset(sampleRate: Int): Boolean {
        FlacNativeLoader.load()
        return NativeBindings.isSampleRateSubset(sampleRate)
    }

    @JvmStatic
    fun isBlockSizeSubset(blockSize: Int, sampleRate: Int): Boolean {
        FlacNativeLoader.load()
        return NativeBindings.isBlockSizeSubset(blockSize, sampleRate)
    }

    @JvmStatic
    fun isVorbisCommentNameLegal(name: String): Boolean {
        FlacNativeLoader.load()
        return NativeBindings.isVorbisCommentNameLegal(name)
    }

    @JvmStatic
    fun isVorbisCommentValueLegal(value: String): Boolean {
        FlacNativeLoader.load()
        return NativeBindings.isVorbisCommentValueLegal(value)
    }

    @JvmStatic
    fun isVorbisCommentEntryLegal(entry: String): Boolean {
        FlacNativeLoader.load()
        return NativeBindings.isVorbisCommentEntryLegal(entry)
    }

    /**
     * Returns `null` when libFLAC considers the picture legal, otherwise a
     * human-readable violation message supplied by libFLAC or the JNI bridge.
     */
    @JvmStatic
    fun pictureViolation(picture: FlacPicture): String? {
        FlacNativeLoader.load()
        return NativeBindings.pictureViolation(picture)
    }
}
