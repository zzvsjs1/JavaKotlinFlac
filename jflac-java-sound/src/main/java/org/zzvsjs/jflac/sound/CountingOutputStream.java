package org.zzvsjs.jflac.sound;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

final class CountingOutputStream extends OutputStream {
    private final OutputStream delegate;
    private long bytesWritten;

    CountingOutputStream(OutputStream delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    long bytesWritten() {
        return bytesWritten;
    }

    @Override
    public void write(int value) throws IOException {
        delegate.write(value);
        bytesWritten = Math.addExact(bytesWritten, 1L);
    }

    @Override
    public void write(byte[] buffer, int offset, int length) throws IOException {
        delegate.write(buffer, offset, length);
        bytesWritten = Math.addExact(bytesWritten, length);
    }

    @Override
    public void flush() throws IOException {
        delegate.flush();
    }

    @Override
    public void close() throws IOException {
        flush();
    }
}
