package org.zzvsjs.jflac.sound;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/*
 * Used by getAudioInputStream in a later task: Java Sound may pass a stream
 * without mark/reset support, but native FLAC probing consumes the first header
 * bytes. The decoder must still see those bytes, so this stream replays a saved
 * prefix before delegating to the original source.
 */
final class ReplayPrefixInputStream extends InputStream {
    private final byte[] prefix;
    private final InputStream delegate;
    private int position;

    ReplayPrefixInputStream(byte[] prefix, InputStream delegate) {
        this.prefix = Objects.requireNonNull(prefix, "prefix").clone();
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public int read() throws IOException {
        if (position < prefix.length) {
            return prefix[position++] & 0xff;
        }
        return delegate.read();
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        Objects.requireNonNull(buffer, "buffer");
        Objects.checkFromIndexSize(offset, length, buffer.length);
        if (length == 0) {
            return 0;
        }

        int copied = 0;
        if (position < prefix.length) {
            int prefixBytes = Math.min(length, prefix.length - position);
            System.arraycopy(prefix, position, buffer, offset, prefixBytes);
            position += prefixBytes;
            copied += prefixBytes;
            offset += prefixBytes;
            length -= prefixBytes;
        }
        if (length == 0) {
            return copied;
        }

        int read = delegate.read(buffer, offset, length);
        if (read < 0) {
            return copied == 0 ? -1 : copied;
        }
        return copied + read;
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
