package org.zzvsjs.jflac.internal;

import org.zzvsjs.jflac.FlacApplicationBlock;
import org.zzvsjs.jflac.FlacCueSheet;
import org.zzvsjs.jflac.FlacPaddingBlock;
import org.zzvsjs.jflac.FlacPicture;
import org.zzvsjs.jflac.FlacSeekTable;
import org.zzvsjs.jflac.FlacStreamInfo;
import org.zzvsjs.jflac.FlacUnknownMetadataBlock;

/**
 * Immutable DTO returned directly from JNI.
 * <p>
 * The native layer fully materialises Java values before returning, so callers
 * never manage native handles for metadata objects.
 *
 * @hidden
 */
final class NativeMetadataPayload {
    private final FlacStreamInfo streamInfo;
    private final String vendor;
    private final String[] commentEntries;
    private final FlacPicture[] pictures;
    private final FlacApplicationBlock[] applicationBlocks;
    private final FlacSeekTable[] seekTables;
    private final FlacCueSheet[] cueSheets;
    private final FlacPaddingBlock[] paddingBlocks;
    private final FlacUnknownMetadataBlock[] unknownBlocks;
    private final int[] metadataBlockTypes;
    private final int[] metadataBlockIndices;

    public NativeMetadataPayload(
            FlacStreamInfo streamInfo,
            String vendor,
            String[] commentEntries,
            FlacPicture[] pictures,
            FlacApplicationBlock[] applicationBlocks,
            FlacSeekTable[] seekTables,
            FlacCueSheet[] cueSheets,
            FlacPaddingBlock[] paddingBlocks,
            FlacUnknownMetadataBlock[] unknownBlocks,
            int[] metadataBlockTypes,
            int[] metadataBlockIndices
    ) {
        // After construction completes, no native ownership remains for these
        // values; the JVM object graph is completely self-contained.
        this.streamInfo = java.util.Objects.requireNonNull(streamInfo, "streamInfo");
        this.vendor = vendor;
        this.commentEntries = NativeTransportChecks.copyOf(commentEntries, "commentEntries");
        this.pictures = NativeTransportChecks.copyOf(pictures, "pictures");
        this.applicationBlocks = NativeTransportChecks.copyOf(applicationBlocks, "applicationBlocks");
        this.seekTables = NativeTransportChecks.copyOf(seekTables, "seekTables");
        this.cueSheets = NativeTransportChecks.copyOf(cueSheets, "cueSheets");
        this.paddingBlocks = NativeTransportChecks.copyOf(paddingBlocks, "paddingBlocks");
        this.unknownBlocks = NativeTransportChecks.copyOf(unknownBlocks, "unknownBlocks");
        this.metadataBlockTypes = NativeTransportChecks.copyOf(metadataBlockTypes, "metadataBlockTypes");
        this.metadataBlockIndices = NativeTransportChecks.copyOf(metadataBlockIndices, "metadataBlockIndices");
        NativeTransportChecks.requireSameLength(
                "metadataBlockTypes",
                this.metadataBlockTypes.length,
                "metadataBlockIndices",
                this.metadataBlockIndices.length
        );
    }

    public FlacStreamInfo getStreamInfo() {
        return streamInfo;
    }

    public String getVendor() {
        return vendor;
    }

    public String[] getCommentEntries() {
        return commentEntries.clone();
    }

    public FlacPicture[] getPictures() {
        return pictures.clone();
    }

    public FlacApplicationBlock[] getApplicationBlocks() {
        return applicationBlocks.clone();
    }

    public FlacSeekTable[] getSeekTables() {
        return seekTables.clone();
    }

    public FlacCueSheet[] getCueSheets() {
        return cueSheets.clone();
    }

    public FlacPaddingBlock[] getPaddingBlocks() {
        return paddingBlocks.clone();
    }

    public FlacUnknownMetadataBlock[] getUnknownBlocks() {
        return unknownBlocks.clone();
    }

    public int[] getMetadataBlockTypes() {
        return metadataBlockTypes.clone();
    }

    public int[] getMetadataBlockIndices() {
        return metadataBlockIndices.clone();
    }
}
