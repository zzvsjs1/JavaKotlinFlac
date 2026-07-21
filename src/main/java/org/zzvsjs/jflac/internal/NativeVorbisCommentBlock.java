package org.zzvsjs.jflac.internal;

/**
 * Ordered Vorbis-comment transport value for JNI metadata writes.
 *
 * <p>
 * The vendor is nullable for new encodes because libFLAC normally writes its
 * own encoder vendor. Existing-file metadata edits pass a non-null vendor when
 * the caller wants the block to round-trip exactly.
 *
 * @hidden
 */
final class NativeVorbisCommentBlock {
    private final String vendor;
    private final String[] entries;

    public NativeVorbisCommentBlock(String vendor, String[] entries) {
        NativeTransportChecks.requireNoEmbeddedNul(vendor, "vendor");
        this.vendor = vendor;
        this.entries = NativeTransportChecks.copyOf(entries, "entries");
        for (int index = 0; index < this.entries.length; index += 1) {
            if (this.entries[index] == null) {
                throw new NullPointerException("entries[" + index + "]");
            }

            NativeTransportChecks.requireNoEmbeddedNul(this.entries[index], "entries[" + index + "]");
        }
    }

    public String getVendor() {
        return vendor;
    }

    public String[] getEntries() {
        return entries.clone();
    }
}
