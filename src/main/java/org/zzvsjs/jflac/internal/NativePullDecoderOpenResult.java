package org.zzvsjs.jflac.internal;

import org.zzvsjs.jflac.FlacStreamInfo;

/**
 * Native result for opening a pull decoder.
 * <p>
 * The native open call reads STREAMINFO before returning the handle. That is
 * important for sequential streams: Java cannot decode once for metadata and
 * then reuse the same already-consumed InputStream for PCM.
 *
 * @hidden
 */
final class NativePullDecoderOpenResult {
    private final long handle;
    private final FlacStreamInfo streamInfo;

    public NativePullDecoderOpenResult(long handle, FlacStreamInfo streamInfo) {
        this.handle = handle;
        this.streamInfo = streamInfo;
    }

    public long getHandle() {
        return handle;
    }

    public FlacStreamInfo getStreamInfo() {
        return streamInfo;
    }
}
