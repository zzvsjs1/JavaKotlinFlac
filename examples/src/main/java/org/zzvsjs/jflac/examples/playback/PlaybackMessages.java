package org.zzvsjs.jflac.examples.playback;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Small formatting helpers shared by the console and terminal frontends. */
final class PlaybackMessages {
    private PlaybackMessages() {
    }

    static String rootCauseMessage(Throwable error) {
        if (error == null) {
            return "unknown failure";
        }

        /*
         * Throwable cause chains are normally acyclic, but custom providers can
         * construct malformed chains. Identity tracking prevents error
         * reporting itself from looping forever.
         */
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = error;
        visited.add(current);
        while (current.getCause() != null
                && current.getCause() != current
                && visited.add(current.getCause())) {
            current = current.getCause();
        }

        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName()
                : message;
    }
}
