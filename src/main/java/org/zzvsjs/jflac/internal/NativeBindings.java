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
 * <p>
 * Native methods follow two broad patterns:
 * <ul>
 *     <li>one-shot calls, which finish before returning to Java; and</li>
 *     <li>handle calls, which return a small positive {@code long} registered
 *     in native code instead of exposing a raw libFLAC pointer.</li>
 * </ul>
 * Invalid, stale, or zero handles are rejected in native code with a controlled
 * Java exception. If a Java callback or stream operation already raised an
 * exception, the native layer preserves that exception instead of replacing it
 * with a synthetic wrapper error.
 *
 * @hidden
 */
final class NativeBindings {
    private NativeBindings() {
    }

    /** Forces native symbol, version, and feature validation during loader initialisation. */
    public static native void verifyRuntime();

    public static native NativeMetadataPayload readMetadata(String path, int container);

    public static native void decodeFile(
            String path,
            int container,
            boolean checkMd5,
            boolean decodeChainedOgg,
            PcmConsumer consumer
    );

    public static native void decodeStream(
            InputStream input,
            int container,
            boolean checkMd5,
            boolean decodeChainedOgg,
            PcmConsumer consumer
    );

    public static native void decodeChannel(
            SeekableByteChannel input,
            int container,
            boolean checkMd5,
            boolean decodeChainedOgg,
            PcmConsumer consumer
    );

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

    /*
     * Pull decoder open methods return both a native handle and cached
     * STREAMINFO. The handle remains valid until releasePullDecoder() or until a
     * read failure makes the Kotlin session release it defensively.
     */
    public static native NativePullDecoderOpenResult openPullDecoderFile(String path, int container);

    public static native NativePullDecoderOpenResult openPullDecoderStream(InputStream input, int container);

    public static native NativePullDecoderOpenResult openPullDecoderChannel(SeekableByteChannel input, int container);

    public static native void decodeDecoderFrom(long handle, long firstSample, PcmConsumer consumer);

    public static native void decodeDecoderRange(long handle, long firstSample, long maxFrames, PcmConsumer consumer);

    /*
     * Reads up to maxFrames frames into samples. The array must have room for
     * maxFrames * channels values, where channels comes from the open result's
     * STREAMINFO. Return values are frame counts: positive for decoded data, 0
     * only for a zero-frame request, and -1 after end-of-stream.
     */
    public static native int readPullDecoderInterleaved(long handle, int[] samples, int maxFrames);

    /*
     * Release calls are intentionally tolerant of invalid or already-released
     * handles so close paths can be defensive after setup failures.
     */
    public static native void releaseDecoder(long handle);

    public static native void releasePullDecoder(long handle);

    /*
     * Encoder open methods return an opaque native handle. The Java/Kotlin
     * session must later call finishEncoder() for successful output or
     * releaseEncoder() to abandon a failed encoder. OutputStream and channel
     * targets are held as native global references until that close path runs.
     */
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
