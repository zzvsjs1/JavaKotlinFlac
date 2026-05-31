package org.zzvsjs.jflac.sound;

import javax.sound.sampled.AudioFileFormat;

public final class JflacAudioFileTypes {
    public static final AudioFileFormat.Type FLAC = new AudioFileFormat.Type("FLAC", "flac");
    public static final AudioFileFormat.Type OGG_FLAC = new AudioFileFormat.Type("Ogg FLAC", "oga");

    private JflacAudioFileTypes() {
    }
}
