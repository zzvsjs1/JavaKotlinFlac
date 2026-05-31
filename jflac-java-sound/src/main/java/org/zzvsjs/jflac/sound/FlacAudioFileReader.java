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
 * reader performs conservative FLAC/Ogg FLAC format probing before wrapping the
 * pull decoder as Java Sound PCM.</p>
 *
 * <p>InputStream format probing has a stricter contract than file or URL
 * probing: Java Sound may try several providers against the same stream, so the
 * stream must support mark/reset and must be restored before this provider
 * returns. Lazy decode methods are allowed to consume the stream after they
 * successfully return an {@link AudioInputStream}.</p>
 *
 * <p>Ogg probing starts with the generic {@code OggS} magic. libFLAC performs
 * the final check that the Ogg stream really carries FLAC frames; failures are
 * exposed as {@link UnsupportedAudioFileException} so another Java Sound
 * provider can try the same source where possible.</p>
 */
public final class FlacAudioFileReader extends AudioFileReader {
    private static final String NOT_FLAC_CANDIDATE = "Input is not a FLAC or Ogg FLAC stream.";
    private static final int FLAC_HEADER_BYTES = 4;
    private static final int STREAM_DECODE_SETUP_MARK_LIMIT = 1 << 20;
    private static final byte[] FLAC_MAGIC = new byte[] { 'f', 'L', 'a', 'C' };
    private static final byte[] OGG_MAGIC = new byte[] { 'O', 'g', 'g', 'S' };

    /**
     * Creates the Java Sound FLAC reader service provider.
     *
     * <p>Java Sound instantiates providers reflectively from
     * {@code META-INF/services}; application code normally obtains this reader
     * through {@code AudioSystem} rather than constructing it directly.</p>
     */
    public FlacAudioFileReader() {
    }

    /**
     * Probes stream headers without consuming data from the caller's perspective.
     * Java Sound specifies that this overload must leave the stream positioned so
     * another provider or a later decode attempt can read the same bytes. That is
     * why this method requires mark/reset support and resets after both the cheap
     * magic check and the native STREAMINFO read.
     */
    @Override
    public AudioFileFormat getAudioFileFormat(InputStream stream) throws UnsupportedAudioFileException, IOException {
        HeaderDetails header = verifyFormatStreamHeader(stream);
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
            return FlacAudioFormats.fileFormat(session.getStreamInfo(), header.type(), header.container());
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
        HeaderDetails header = verifyFileHeader(file);
        try {
            FlacMetadata metadata = new FlacMetadataReader().read(file.toPath());
            return FlacAudioFormats.fileFormat(metadata, header.type(), header.container());
        } catch (FlacException e) {
            throw unsupported(e);
        }
    }

    /**
     * Creates a lazy PCM stream over the caller's FLAC stream. This overload must
     * not take ownership of the supplied stream: closing the returned
     * {@link AudioInputStream} releases the native pull session, while the caller
     * keeps responsibility for the original Java stream.
     *
     * If setup fails after marking a caller stream, the method attempts to reset
     * that stream before throwing so another provider can inspect it. Native
     * decode setup failures are wrapped as {@link UnsupportedAudioFileException}
     * to match the Java Sound probing contract.
     */
    @Override
    public AudioInputStream getAudioInputStream(InputStream stream) throws UnsupportedAudioFileException, IOException {
        FlacPullDecodingSession session = null;
        boolean success = false;
        boolean resetOnFailure = false;
        try {
            CheckedStream checked = checkedDecodeStream(stream);
            resetOnFailure = checked.resetOnFailure();
            session = new FlacDecoder().openPull(checked.stream());
            AudioInputStream audioInputStream = new AudioInputStream(
                    new FlacPcmInputStream(session),
                    FlacAudioFormats.pcmFormat(
                            session.getStreamInfo(),
                            FlacAudioFormats.streamInfoProperties(
                                    session.getStreamInfo(),
                                    checked.header().container()
                            )
                    ),
                    FlacAudioFormats.frameLength(session.getStreamInfo())
            );
            success = true;
            return audioInputStream;
        } catch (FlacException e) {
            Throwable failure = closeSession(session, unsupported(e));
            failure = resetStreamOnFailure(stream, resetOnFailure, failure);
            session = null;
            throwFailure(failure);
            throw new AssertionError("unreachable");
        } catch (UnsupportedAudioFileException | IOException e) {
            Throwable failure = closeSession(session, e);
            failure = resetStreamOnFailure(stream, resetOnFailure, failure);
            session = null;
            throwFailure(failure);
            throw new AssertionError("unreachable");
        } catch (RuntimeException | Error e) {
            Throwable failure = closeSession(session, e);
            failure = resetStreamOnFailure(stream, resetOnFailure, failure);
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
     *
     * The returned {@link AudioInputStream} closes both the native pull decoder
     * and the URL stream when it is closed.
     */
    @Override
    public AudioInputStream getAudioInputStream(URL url) throws UnsupportedAudioFileException, IOException {
        InputStream stream = url.openStream();
        FlacPullDecodingSession session = null;
        try {
            CheckedStream checked = checkedStream(stream);
            session = new FlacDecoder().openPull(checked.stream());
            return new AudioInputStream(
                    new FlacPcmInputStream(session, stream),
                    FlacAudioFormats.pcmFormat(
                            session.getStreamInfo(),
                            FlacAudioFormats.streamInfoProperties(
                                    session.getStreamInfo(),
                                    checked.header().container()
                            )
                    ),
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
     *
     * File probing reads metadata first so the Java Sound format exposes the
     * same properties as {@link #getAudioFileFormat(File)}.
     */
    @Override
    public AudioInputStream getAudioInputStream(File file) throws UnsupportedAudioFileException, IOException {
        HeaderDetails header = verifyFileHeader(file);
        FlacMetadata metadata;
        try {
            metadata = new FlacMetadataReader().read(file.toPath());
        } catch (FlacException e) {
            throw unsupported(e);
        }

        FlacPullDecodingSession session = null;
        boolean success = false;
        try {
            session = new FlacDecoder().openPull(file.toPath());
            AudioInputStream audioInputStream = new AudioInputStream(
                    new FlacPcmInputStream(session),
                    FlacAudioFormats.fileFormat(metadata, header.type(), header.container()).getFormat(),
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

    private static HeaderDetails classifyHeader(byte[] header) throws UnsupportedAudioFileException {
        if (Arrays.equals(header, FLAC_MAGIC)) {
            return new HeaderDetails(JflacAudioFileTypes.FLAC, FlacAudioFormats.CONTAINER_NATIVE);
        }
        if (Arrays.equals(header, OGG_MAGIC)) {
            return new HeaderDetails(JflacAudioFileTypes.OGG_FLAC, FlacAudioFormats.CONTAINER_OGG);
        }
        throw new UnsupportedAudioFileException(NOT_FLAC_CANDIDATE);
    }

    private static CheckedStream checkedStream(InputStream stream) throws IOException, UnsupportedAudioFileException {
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
            return new CheckedStream(stream, classifyHeader(header));
        }

        byte[] header = stream.readNBytes(FLAC_HEADER_BYTES);
        HeaderDetails details = classifyHeader(header);
        return new CheckedStream(new ReplayPrefixInputStream(header, stream), details);
    }

    private static CheckedStream checkedDecodeStream(InputStream stream) throws IOException, UnsupportedAudioFileException {
        Objects.requireNonNull(stream, "stream");
        if (!stream.markSupported()) {
            return checkedStream(stream);
        }

        /*
         * Ogg containers share the same OggS magic. Keep a setup-wide restore
         * point for mark-capable caller streams so Ogg Vorbis/Opus can still be
         * offered to later Java Sound providers if libFLAC rejects the stream.
         * The non-marking wrapper prevents core FLAC inspection from replacing
         * this restore point with its own four-byte mark.
         */
        stream.mark(STREAM_DECODE_SETUP_MARK_LIMIT);
        byte[] header = stream.readNBytes(FLAC_HEADER_BYTES);
        stream.reset();
        return new CheckedStream(new NonMarkingInputStream(stream), classifyHeader(header), true);
    }

    private static HeaderDetails verifyFormatStreamHeader(InputStream stream)
            throws IOException, UnsupportedAudioFileException {
        Objects.requireNonNull(stream, "stream");
        if (!stream.markSupported()) {
            throw new IOException("FLAC format probing requires an InputStream with mark/reset support.");
        }

        stream.mark(FLAC_HEADER_BYTES);
        byte[] header = stream.readNBytes(FLAC_HEADER_BYTES);
        stream.reset();
        return classifyHeader(header);
    }

    private static HeaderDetails verifyFileHeader(File file) throws IOException, UnsupportedAudioFileException {
        Objects.requireNonNull(file, "file");
        try (InputStream stream = Files.newInputStream(file.toPath())) {
            byte[] header = stream.readNBytes(FLAC_HEADER_BYTES);
            return classifyHeader(header);
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

    private static Throwable resetStreamOnFailure(InputStream stream, boolean reset, Throwable primary) {
        if (!reset) {
            return primary;
        }
        try {
            stream.reset();
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

    private record HeaderDetails(AudioFileFormat.Type type, String container) {
    }

    private record CheckedStream(InputStream stream, HeaderDetails header, boolean resetOnFailure) {
        private CheckedStream(InputStream stream, HeaderDetails header) {
            this(stream, header, false);
        }
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
