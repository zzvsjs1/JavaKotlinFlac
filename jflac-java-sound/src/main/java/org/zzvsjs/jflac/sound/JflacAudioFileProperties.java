package org.zzvsjs.jflac.sound;

import org.zzvsjs.jflac.FlacApplicationBlock;
import org.zzvsjs.jflac.FlacCueSheet;
import org.zzvsjs.jflac.FlacMetadataBlock;
import org.zzvsjs.jflac.FlacPaddingBlock;
import org.zzvsjs.jflac.FlacPicture;
import org.zzvsjs.jflac.FlacSeekTable;
import org.zzvsjs.jflac.FlacUnknownMetadataBlock;

import java.util.List;
import java.util.Map;

/**
 * Java Sound property keys used by the jflac service provider.
 *
 * <p>Reader paths expose these keys through {@code AudioFileFormat.properties()}
 * and {@code AudioFormat.properties()}. Writer paths accept the metadata keys
 * from an input {@code AudioFormat} and pass them to the core encoder.</p>
 *
 * <p>Example write-side Vorbis comments:</p>
 *
 * <pre>{@code
 * Map<String, Object> properties = Map.of(
 *         JflacAudioFileProperties.VORBIS_COMMENTS,
 *         Map.of("TITLE", List.of("Example"))
 * );
 * }</pre>
 *
 * <p>When {@link #BLOCKS} is present during writing, it is treated as the
 * authoritative ordered metadata list and grouped metadata keys such as
 * {@link #VORBIS_COMMENTS} and {@link #PICTURES} are ignored.</p>
 */
public final class JflacAudioFileProperties {
    /** {@link String}: {@code "native"} for FLAC or {@code "ogg"} for Ogg FLAC. */
    public static final String CONTAINER = "flac.container";
    /** {@link Integer}: sample rate in hertz. */
    public static final String SAMPLE_RATE = "flac.sampleRate";
    /** {@link Integer}: number of channels in the decoded PCM stream. */
    public static final String CHANNELS = "flac.channels";
    /** {@link Integer}: signed PCM bit depth reported by STREAMINFO. */
    public static final String BITS_PER_SAMPLE = "flac.bitsPerSample";
    /** {@link Long}: STREAMINFO total samples, counted as frames. */
    public static final String TOTAL_SAMPLES = "flac.totalSamples";
    /** {@code byte[]}: 16-byte STREAMINFO MD5 digest. */
    public static final String MD5 = "flac.md5";
    /** {@link String}: Vorbis comment vendor string when present. */
    public static final String VORBIS_VENDOR = "flac.vorbis.vendor";
    /** {@code Map<String, List<String>>}: grouped Vorbis comments. */
    public static final String VORBIS_COMMENTS = "flac.vorbis.comments";
    /** {@code List<FlacPicture>}: picture metadata blocks. */
    public static final String PICTURES = "flac.pictures";
    /** {@code List<FlacApplicationBlock>}: APPLICATION metadata blocks. */
    public static final String APPLICATION_BLOCKS = "flac.applicationBlocks";
    /** {@code List<FlacSeekTable>}: SEEKTABLE metadata blocks. */
    public static final String SEEK_TABLES = "flac.seekTables";
    /** {@code List<FlacCueSheet>}: CUESHEET metadata blocks. */
    public static final String CUE_SHEETS = "flac.cueSheets";
    /** {@code List<FlacPaddingBlock>}: PADDING metadata blocks. */
    public static final String PADDING_BLOCKS = "flac.paddingBlocks";
    /** {@code List<FlacUnknownMetadataBlock>}: opaque reserved metadata blocks. */
    public static final String UNKNOWN_BLOCKS = "flac.unknownBlocks";
    /** {@code List<FlacMetadataBlock>}: ordered non-STREAMINFO metadata blocks. */
    public static final String BLOCKS = "flac.blocks";

    private JflacAudioFileProperties() {
    }
}
