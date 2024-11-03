package org.zzvsjs.jflac.internal;

import org.zzvsjs.jflac.FlacApplicationBlock;
import org.zzvsjs.jflac.FlacCueSheet;
import org.zzvsjs.jflac.FlacPaddingBlock;
import org.zzvsjs.jflac.FlacPicture;
import org.zzvsjs.jflac.FlacSeekTable;
import org.zzvsjs.jflac.FlacUnknownMetadataBlock;

/**
 * Immutable JVM-to-JNI transport object for existing-file metadata edits.
 *
 * @hidden
 */
public final class NativeMetadataEditRequest {
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
        this.commentEntries = commentEntries.clone();
        this.pictures = pictures.clone();
        this.applicationBlocks = applicationBlocks.clone();
        this.seekTables = seekTables.clone();
        this.cueSheets = cueSheets.clone();
        this.paddingBlocks = paddingBlocks.clone();
        this.unknownBlocks = unknownBlocks.clone();
        this.metadataBlockTypes = metadataBlockTypes.clone();
        this.metadataBlockValues = metadataBlockValues.clone();
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
