package org.zzvsjs.jflac.internal;

import org.zzvsjs.jflac.PcmConsumer;
import org.zzvsjs.jflac.FlacPicture;

/**
 * Java declaration layer for JNI entry points.
 *
 * Keeping these signatures in Java makes the native boundary explicit without
 * leaking external declarations into the public Kotlin API.
 *
 * @hidden
 */
public final class NativeBindings {
    private NativeBindings() {
    }

    public static native NativeMetadataPayload readMetadata(String path);

    public static native void decodeFile(String path, PcmConsumer consumer);

    public static native void decodeFileFrom(String path, long firstSample, PcmConsumer consumer);

    public static native void decodeFileRange(String path, long firstSample, long maxFrames, PcmConsumer consumer);

    public static native long openDecoderFile(String path);

    public static native void decodeDecoderFrom(long handle, long firstSample, PcmConsumer consumer);

    public static native void decodeDecoderRange(long handle, long firstSample, long maxFrames, PcmConsumer consumer);

    public static native void releaseDecoder(long handle);

    public static native long openEncoderFile(String path, NativeEncodingRequest request);

    public static native void writeMetadata(
            String path,
            NativeMetadataEditRequest request,
            boolean usePadding,
            boolean preserveFileStats
    );

    public static native void writeEncoderInterleaved(long handle, int[] samples, int frames);

    public static native void finishEncoder(long handle);

    public static native void releaseEncoder(long handle);

    public static native boolean isSampleRateValid(int sampleRate);

    public static native boolean isSampleRateSubset(int sampleRate);

    public static native boolean isBlockSizeSubset(int blockSize, int sampleRate);

    public static native boolean isVorbisCommentNameLegal(String name);

    public static native boolean isVorbisCommentValueLegal(String value);

    public static native boolean isVorbisCommentEntryLegal(String entry);

    public static native String pictureViolation(FlacPicture picture);
}
