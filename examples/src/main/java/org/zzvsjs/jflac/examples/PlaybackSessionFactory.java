package org.zzvsjs.jflac.examples;

import java.nio.file.Path;

/** Opens one controllable playback session for a selected music file. */
@FunctionalInterface
interface PlaybackSessionFactory {
    PlaybackSession open(Path input) throws Exception;
}

