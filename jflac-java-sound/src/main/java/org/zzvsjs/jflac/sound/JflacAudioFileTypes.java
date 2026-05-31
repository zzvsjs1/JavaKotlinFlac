package org.zzvsjs.jflac.sound;

import javax.sound.sampled.AudioFileFormat;

/**
 * Java Sound file types registered by the jflac service provider.
 *
 * <p>Pass these constants to {@code AudioSystem.write(...)} when selecting the
 * target container explicitly. The reader also returns them from
 * {@code AudioFileFormat.getType()} after probing an input source.</p>
 */
public final class JflacAudioFileTypes {
    /** Native FLAC bitstream, normally written with the {@code .flac} extension. */
    public static final AudioFileFormat.Type FLAC = new AudioFileFormat.Type("FLAC", "flac");
    /**
     * Ogg container carrying FLAC frames.
     *
     * <p>The extension is {@code .oga}, matching the common Ogg audio
     * convention. The writer does not infer Ogg FLAC from a file name; callers
     * must pass this type explicitly.</p>
     */
    public static final AudioFileFormat.Type OGG_FLAC = new AudioFileFormat.Type("Ogg FLAC", "oga");

    private JflacAudioFileTypes() {
    }
}
