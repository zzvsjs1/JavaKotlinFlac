package org.zzvsjs.jflac.sound;

import org.zzvsjs.jflac.FlacDecoder;
import org.zzvsjs.jflac.FlacException;
import org.zzvsjs.jflac.FlacMetadata;
import org.zzvsjs.jflac.FlacMetadataReader;
import org.zzvsjs.jflac.FlacPullDecodingSession;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.UnsupportedAudioFileException;
import javax.sound.sampled.spi.AudioFileReader;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Objects;

/**
 * Java Sound service provider entry point for FLAC files.
 *
 * <p>Java Sound discovers providers through {@code META-INF/services}, then calls
 * the probing methods before it can expose an {@link AudioInputStream}. This
 * task implements conservative native FLAC format probing only; decoded stream
 * creation remains separate until the pull decoder is wrapped as Java Sound
 * PCM.</p>
 */
public final class FlacAudioFileReader extends AudioFileReader {
    private static final String NOT_NATIVE_FLAC = "Input is not a native FLAC stream.";
    private static final int FLAC_HEADER_BYTES = 4;
    private static final byte[] FLAC_MAGIC = new byte[] { 'f', 'L', 'a', 'C' };

    /**
     * Probes stream headers without consuming data from the caller's perspective.
     * Java Sound specifies that this overload must leave the stream positioned so
     * another provider or a later decode attempt can read the same bytes. That is
     * why this method requires mark/reset support and resets after both the cheap
     * magic check and the native STREAMINFO read.
     */
    @Override
    public AudioFileFormat getAudioFileFormat(InputStream stream) throws UnsupportedAudioFileException, IOException {
        verifyFormatStreamHeader(stream);
        stream.mark(Integer.MAX_VALUE);
        /*
         * The caller stream owns the Java Sound restore mark. The core decoder
         * also performs container inspection, and if it sees the same
         * mark-capable object it will install its own short mark on top of ours.
         * Wrapping the stream as non-markable forces the core pushback path, so
         * core probing can replay its own header bytes without replacing the SPI
         * mark that restores the caller-visible stream position.
         */
        InputStream decoderStream = new NonMarkingInputStream(stream);
        try (FlacPullDecodingSession session = new FlacDecoder().openPull(decoderStream)) {
            return FlacAudioFormats.fileFormat(session.getStreamInfo());
        } catch (FlacException e) {
            throw unsupported(e);
        } finally {
            stream.reset();
        }
    }

    /**
     * Probes URL-backed sources through a buffered stream so the stream overload
     * gets mark/reset support for Java Sound's non-consuming format contract.
     */
    @Override
    public AudioFileFormat getAudioFileFormat(URL url) throws UnsupportedAudioFileException, IOException {
        try (InputStream stream = new BufferedInputStream(url.openStream())) {
            return getAudioFileFormat(stream);
        }
    }

    /**
     * Probes file-backed sources with the metadata reader, which can obtain
     * STREAMINFO directly without constructing a decoded Java Sound stream.
     */
    @Override
    public AudioFileFormat getAudioFileFormat(File file) throws UnsupportedAudioFileException, IOException {
        verifyFileHeader(file);
        try {
            FlacMetadata metadata = new FlacMetadataReader().read(file.toPath());
            return FlacAudioFormats.fileFormat(metadata.getStreamInfo());
        } catch (FlacException e) {
            throw unsupported(e);
        }
    }

    /**
     * Creates a lazy PCM stream over the caller's FLAC stream. This overload must
     * not take ownership of the supplied stream: closing the returned
     * {@link AudioInputStream} releases the native pull session, while the caller
     * keeps responsibility for the original Java stream.
     */
    @Override
    public AudioInputStream getAudioInputStream(InputStream stream) throws UnsupportedAudioFileException, IOException {
        FlacPullDecodingSession session = null;
        boolean success = false;
        try {
            InputStream checked = checkedStream(stream);
            session = new FlacDecoder().openPull(checked);
            AudioInputStream audioInputStream = new AudioInputStream(
                    new FlacPcmInputStream(session),
                    FlacAudioFormats.pcmFormat(session.getStreamInfo()),
                    FlacAudioFormats.frameLength(session.getStreamInfo())
            );
            success = true;
            return audioInputStream;
        } catch (FlacException e) {
            Throwable failure = closeSession(session, unsupported(e));
            session = null;
            throwFailure(failure);
            throw new AssertionError("unreachable");
        } catch (RuntimeException | Error e) {
            Throwable failure = closeSession(session, e);
            session = null;
            throwFailure(failure);
            throw new AssertionError("unreachable");
        } finally {
            if (!success) {
                closeSessionQuietly(session);
            }
        }
    }

    /**
     * Opens a URL source and transfers ownership to the returned
     * {@link AudioInputStream}. If any validation or native session setup fails
     * before the stream can be returned, the URL stream is closed in this method.
     */
    @Override
    public AudioInputStream getAudioInputStream(URL url) throws UnsupportedAudioFileException, IOException {
        InputStream stream = url.openStream();
        FlacPullDecodingSession session = null;
        try {
            InputStream checked = checkedStream(stream);
            session = new FlacDecoder().openPull(checked);
            return new AudioInputStream(
                    new FlacPcmInputStream(session, stream),
                    FlacAudioFormats.pcmFormat(session.getStreamInfo()),
                    FlacAudioFormats.frameLength(session.getStreamInfo())
            );
        } catch (FlacException e) {
            closeSessionAndStreamThenThrow(session, stream, unsupported(e));
            throw new AssertionError("unreachable");
        } catch (UnsupportedAudioFileException | IOException | RuntimeException | Error e) {
            closeSessionAndStreamThenThrow(session, stream, e);
            throw new AssertionError("unreachable");
        }
    }

    /**
     * Opens a file-backed native pull session. The native decoder owns the file
     * handle internally, so closing the returned PCM stream is enough to release
     * the decode lifecycle.
     */
    @Override
    public AudioInputStream getAudioInputStream(File file) throws UnsupportedAudioFileException, IOException {
        verifyFileHeader(file);
        FlacPullDecodingSession session = null;
        boolean success = false;
        try {
            session = new FlacDecoder().openPull(file.toPath());
            AudioInputStream audioInputStream = new AudioInputStream(
                    new FlacPcmInputStream(session),
                    FlacAudioFormats.pcmFormat(session.getStreamInfo()),
                    FlacAudioFormats.frameLength(session.getStreamInfo())
            );
            success = true;
            return audioInputStream;
        } catch (FlacException e) {
            Throwable failure = closeSession(session, unsupported(e));
            session = null;
            throwFailure(failure);
            throw new AssertionError("unreachable");
        } catch (RuntimeException | Error e) {
            Throwable failure = closeSession(session, e);
            session = null;
            throwFailure(failure);
            throw new AssertionError("unreachable");
        } finally {
            if (!success) {
                closeSessionQuietly(session);
            }
        }
    }

    private static boolean isNativeFlacHeader(byte[] header) {
        return Arrays.equals(header, FLAC_MAGIC);
    }

    private static InputStream checkedStream(InputStream stream) throws IOException, UnsupportedAudioFileException {
        Objects.requireNonNull(stream, "stream");
        if (stream.markSupported()) {
            /*
             * Java Sound may try another provider after rejection, so restore
             * mark-capable caller streams after the format check. Keep the mark
             * to the FLAC magic only, because successful lazy decoding continues
             * through this same stream and must not retain a large buffer.
             */
            stream.mark(FLAC_HEADER_BYTES);
            byte[] header = stream.readNBytes(FLAC_HEADER_BYTES);
            stream.reset();
            if (!isNativeFlacHeader(header)) {
                throw new UnsupportedAudioFileException(NOT_NATIVE_FLAC);
            }
            return stream;
        }

        byte[] header = stream.readNBytes(FLAC_HEADER_BYTES);
        if (!isNativeFlacHeader(header)) {
            throw new UnsupportedAudioFileException(NOT_NATIVE_FLAC);
        }
        return new ReplayPrefixInputStream(header, stream);
    }

    private static void verifyFormatStreamHeader(InputStream stream) throws IOException, UnsupportedAudioFileException {
        Objects.requireNonNull(stream, "stream");
        if (!stream.markSupported()) {
            throw new IOException("FLAC format probing requires an InputStream with mark/reset support.");
        }

        stream.mark(FLAC_HEADER_BYTES);
        byte[] header = stream.readNBytes(FLAC_HEADER_BYTES);
        stream.reset();
        if (!isNativeFlacHeader(header)) {
            throw new UnsupportedAudioFileException(NOT_NATIVE_FLAC);
        }
    }

    private static void verifyFileHeader(File file) throws IOException, UnsupportedAudioFileException {
        Objects.requireNonNull(file, "file");
        try (InputStream stream = Files.newInputStream(file.toPath())) {
            byte[] header = stream.readNBytes(FLAC_HEADER_BYTES);
            if (!isNativeFlacHeader(header)) {
                throw new UnsupportedAudioFileException(NOT_NATIVE_FLAC);
            }
        }
    }

    private static UnsupportedAudioFileException unsupported(FlacException cause) {
        UnsupportedAudioFileException exception = new UnsupportedAudioFileException(cause.getMessage());
        exception.initCause(cause);
        return exception;
    }

    private static void closeSessionAndStreamThenThrow(
            FlacPullDecodingSession session,
            InputStream stream,
            Throwable primary
    ) throws IOException, UnsupportedAudioFileException {
        Throwable failure = closeSession(session, primary);
        failure = closeStream(stream, failure);
        throwFailure(failure);
    }

    private static Throwable closeSession(FlacPullDecodingSession session, Throwable primary) {
        if (session == null) {
            return primary;
        }
        try {
            session.close();
            return primary;
        } catch (RuntimeException e) {
            return addSuppressedOrPrimary(primary, e);
        }
    }

    private static Throwable closeStream(InputStream stream, Throwable primary) {
        try {
            stream.close();
            return primary;
        } catch (IOException e) {
            return addSuppressedOrPrimary(primary, e);
        }
    }

    /*
     * A failed AudioInputStream setup still owns any native session opened in
     * this method. Preserve the construction/probing failure as primary, and add
     * close failures as suppressed diagnostics so cleanup does not hide the root
     * cause.
     */
    private static Throwable addSuppressedOrPrimary(Throwable primary, Throwable cleanupFailure) {
        if (primary == null) {
            return cleanupFailure;
        }
        primary.addSuppressed(cleanupFailure);
        return primary;
    }

    private static void closeSessionQuietly(FlacPullDecodingSession session) {
        if (session != null) {
            try {
                session.close();
            } catch (RuntimeException ignored) {
                // The throwing path already attempted cleanup and preserved close failures.
            }
        }
    }

    private static void throwFailure(Throwable failure) throws IOException, UnsupportedAudioFileException {
        if (failure instanceof UnsupportedAudioFileException e) {
            throw e;
        }
        if (failure instanceof IOException e) {
            throw e;
        }
        if (failure instanceof RuntimeException e) {
            throw e;
        }
        if (failure instanceof Error e) {
            throw e;
        }
        throw new IOException("Failed to create FLAC PCM stream.", failure);
    }

    private static final class NonMarkingInputStream extends InputStream {
        private final InputStream delegate;

        private NonMarkingInputStream(InputStream delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public int read() throws IOException {
            return delegate.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return delegate.read(buffer, offset, length);
        }

        @Override
        public long skip(long n) throws IOException {
            return delegate.skip(n);
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        @Override
        public void reset() throws IOException {
            throw new IOException("mark/reset is not available on this probing wrapper.");
        }

        @Override
        public void close() {
            // Format probing must not close the stream supplied by the Java Sound caller.
        }
    }
}
