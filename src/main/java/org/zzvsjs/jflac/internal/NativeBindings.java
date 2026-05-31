package org.zzvsjs.jflac.internal;

import org.zzvsjs.jflac.PcmConsumer;
import org.zzvsjs.jflac.FlacPicture;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.SeekableByteChannel;

/**
 * Java declaration layer for JNI entry points.
 * <p>
 * Keeping these signatures in Java makes the native boundary explicit without
 * leaking external declarations into the public Kotlin API.
 *
 * @hidden
 */
public final class NativeBindings {
    private NativeBindings() {
    }

    public static native NativeMetadataPayload readMetadata(String path, int container);

    public static native void decodeFile(String path, int container, PcmConsumer consumer);

    public static native void decodeStream(InputStream input, int container, PcmConsumer consumer);

    public static native void decodeChannel(SeekableByteChannel input, int container, PcmConsumer consumer);

    public static native void decodeFileFrom(String path, int container, long firstSample, PcmConsumer consumer);

    public static native void decodeChannelFrom(
            SeekableByteChannel input,
            int container,
            long firstSample,
            PcmConsumer consumer
    );

    public static native void decodeFileRange(
            String path,
            int container,
            long firstSample,
            long maxFrames,
            PcmConsumer consumer
    );

    public static native void decodeChannelRange(
            SeekableByteChannel input,
            int container,
            long firstSample,
            long maxFrames,
            PcmConsumer consumer
    );

    public static native long openDecoderFile(String path, int container);

    public static native long openDecoderChannel(SeekableByteChannel input, int container);

    public static native NativePullDecoderOpenResult openPullDecoderFile(String path, int container);

    public static native NativePullDecoderOpenResult openPullDecoderStream(InputStream input, int container);

    public static native NativePullDecoderOpenResult openPullDecoderChannel(SeekableByteChannel input, int container);

    public static native void decodeDecoderFrom(long handle, long firstSample, PcmConsumer consumer);

    public static native void decodeDecoderRange(long handle, long firstSample, long maxFrames, PcmConsumer consumer);

    public static native int readPullDecoderInterleaved(long handle, int[] samples, int maxFrames);

    public static native void releaseDecoder(long handle);

    public static native void releasePullDecoder(long handle);

    public static native long openEncoderFile(String path, NativeEncodingRequest request);

    public static native long openEncoderStream(OutputStream output, NativeEncodingRequest request);

    public static native long openEncoderChannel(SeekableByteChannel output, NativeEncodingRequest request);

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
