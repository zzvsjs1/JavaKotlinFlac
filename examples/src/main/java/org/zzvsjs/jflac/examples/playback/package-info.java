/**
 * Interactive Java Sound playback example.
 *
 * <p>The package is organised as a small application rather than a collection
 * of unrelated examples. {@link org.zzvsjs.jflac.examples.playback.JavaSoundPlaybackDemo}
 * selects the console or terminal frontend. The console owns one
 * {@code PlaybackSession} per track directly. The terminal frontend adds
 * {@code PlaybackController} for view-state policy, then renders that state
 * through Lanterna and a narrow JLine-backed system-terminal adapter. The
 * concrete session owns Java Sound decoding and output on its worker thread.</p>
 *
 * <p>Most implementation types deliberately have package visibility. This
 * keeps the runnable entry point small as a public surface while allowing the
 * frontend, controller, and device boundaries to be tested without opening
 * real audio or terminal devices.</p>
 */
package org.zzvsjs.jflac.examples.playback;
