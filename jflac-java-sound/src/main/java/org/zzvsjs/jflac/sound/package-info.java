/**
 * Java Sound service-provider integration for jflac.
 *
 * <p>When this artefact is on the classpath, Java's service loader registers a
 * FLAC {@link javax.sound.sampled.spi.AudioFileReader}. Existing code can then
 * call {@link javax.sound.sampled.AudioSystem#getAudioInputStream(java.io.File)}
 * for native FLAC files and receive signed PCM bytes.</p>
 *
 * <p>The provider is decode-only. Use the direct jflac API for encoding,
 * metadata editing, ranged decode, reusable seek sessions, and exact FLAC
 * metadata block handling.</p>
 */
package org.zzvsjs.jflac.sound;
