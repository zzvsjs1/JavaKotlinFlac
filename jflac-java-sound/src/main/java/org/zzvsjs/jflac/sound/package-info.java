/**
 * Java Sound service-provider integration for jflac.
 *
 * <p>When this artefact is on the classpath, Java's service loader registers
 * FLAC and Ogg FLAC reader and writer providers. Existing code can use
 * {@link javax.sound.sampled.AudioSystem#getAudioInputStream(java.io.File)}
 * and
 * {@link javax.sound.sampled.AudioSystem#write(javax.sound.sampled.AudioInputStream, javax.sound.sampled.AudioFileFormat.Type, java.io.File)}
 * without calling the direct jflac APIs.</p>
 *
 * <p>The direct jflac API remains the preferred surface for explicit encoder
 * options, ranged decode, reusable seek sessions, metadata editing, and exact
 * block-level workflows.</p>
 */
package org.zzvsjs.jflac.sound;
