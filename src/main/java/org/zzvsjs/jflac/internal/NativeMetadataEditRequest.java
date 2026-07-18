package org.zzvsjs.jflac.internal;

import org.zzvsjs.jflac.FlacApplicationBlock;
import org.zzvsjs.jflac.FlacCueSheet;
import org.zzvsjs.jflac.FlacPaddingBlock;
import org.zzvsjs.jflac.FlacPicture;
import org.zzvsjs.jflac.FlacSeekTable;
import org.zzvsjs.jflac.FlacUnknownMetadataBlock;

/**
 * Immutable JVM-to-JNI transport object for existing-file metadata edits.
 * <p>
 * The object mirrors {@link NativeEncodingRequest}'s metadata transport without
 * audio-format fields. Grouped arrays are used for convenience edits, while
 * {@code metadataBlockTypes}/{@code metadataBlockValues} carry an exact ordered
 * non-STREAMINFO block list. The constructor clones arrays and validates that
 * ordered type/value arrays have matching shapes before native code reads them.
 *
 * @hidden
 */
final class NativeMetadataEditRequest {
    private final String[] commentEntries;
    private final FlacPicture[] pictures;
    private final FlacApplicationBlock[] applicationBlocks;
    private final FlacSeekTable[] seekTables;
    private final FlacCueSheet[] cueSheets;
    private final FlacPaddingBlock[] paddingBlocks;
    private final FlacUnknownMetadataBlock[] unknownBlocks;
    private final int[] metadataBlockTypes;
    private final Object[] metadataBlockValues;

    public NativeMetadataEditRequest(
            String[] commentEntries,
            FlacPicture[] pictures,
            FlacApplicationBlock[] applicationBlocks,
            FlacSeekTable[] seekTables,
            FlacCueSheet[] cueSheets,
            FlacPaddingBlock[] paddingBlocks,
            FlacUnknownMetadataBlock[] unknownBlocks,
            int[] metadataBlockTypes,
            Object[] metadataBlockValues
    ) {
        this.commentEntries = NativeTransportChecks.copyOf(commentEntries, "commentEntries");
        this.pictures = NativeTransportChecks.copyOf(pictures, "pictures");
        this.applicationBlocks = NativeTransportChecks.copyOf(applicationBlocks, "applicationBlocks");
        this.seekTables = NativeTransportChecks.copyOf(seekTables, "seekTables");
        this.cueSheets = NativeTransportChecks.copyOf(cueSheets, "cueSheets");
        this.paddingBlocks = NativeTransportChecks.copyOf(paddingBlocks, "paddingBlocks");
        this.unknownBlocks = NativeTransportChecks.copyOf(unknownBlocks, "unknownBlocks");
        this.metadataBlockTypes = NativeTransportChecks.copyOf(metadataBlockTypes, "metadataBlockTypes");
        this.metadataBlockValues = NativeTransportChecks.copyOf(metadataBlockValues, "metadataBlockValues");
        NativeTransportChecks.requireOrderedMetadataValues(this.metadataBlockTypes, this.metadataBlockValues);
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

    public Object[] getMetadataBlockValues() {
        return metadataBlockValues.clone();
    }
}
