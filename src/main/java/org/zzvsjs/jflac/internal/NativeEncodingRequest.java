package org.zzvsjs.jflac.internal;

import org.zzvsjs.jflac.FlacApplicationBlock;
import org.zzvsjs.jflac.FlacCueSheet;
import org.zzvsjs.jflac.FlacPaddingBlock;
import org.zzvsjs.jflac.FlacPicture;
import org.zzvsjs.jflac.FlacSeekTable;
import org.zzvsjs.jflac.FlacUnknownMetadataBlock;

/**
 * Immutable JVM-to-JNI transport object for encoder configuration.
 *
 * Boxing the optional numeric fields here keeps the native signatures compact
 * and lets the Kotlin API preserve nullable semantics for "use libFLAC
 * defaults" style options.
 *
 * @hidden
 */
public final class NativeEncodingRequest {
    private final int sampleRate;
    private final int channels;
    private final int bitsPerSample;
    private final Long totalSamplesEstimate;
    private final int compressionLevel;
    private final boolean verify;
    private final boolean streamableSubset;
    private final Integer blockSize;
    private final int container;
    private final Integer oggSerialNumber;
    private final String[] commentEntries;
    private final FlacPicture[] pictures;
    private final FlacApplicationBlock[] applicationBlocks;
    private final FlacSeekTable[] seekTables;
    private final FlacCueSheet[] cueSheets;
    private final FlacPaddingBlock[] paddingBlocks;
    private final FlacUnknownMetadataBlock[] unknownBlocks;
    private final int[] metadataBlockTypes;
    private final Object[] metadataBlockValues;

    public NativeEncodingRequest(
            int sampleRate,
            int channels,
            int bitsPerSample,
            Long totalSamplesEstimate,
            int compressionLevel,
            boolean verify,
            boolean streamableSubset,
            Integer blockSize,
            int container,
            Integer oggSerialNumber,
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
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.bitsPerSample = bitsPerSample;
        this.totalSamplesEstimate = totalSamplesEstimate;
        this.compressionLevel = compressionLevel;
        this.verify = verify;
        this.streamableSubset = streamableSubset;
        this.blockSize = blockSize;
        this.container = container;
        this.oggSerialNumber = oggSerialNumber;
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

    public int getSampleRate() {
        return sampleRate;
    }

    public int getChannels() {
        return channels;
    }

    public int getBitsPerSample() {
        return bitsPerSample;
    }

    public Long getTotalSamplesEstimate() {
        return totalSamplesEstimate;
    }

    public int getCompressionLevel() {
        return compressionLevel;
    }

    public boolean isVerify() {
        return verify;
    }

    public boolean isStreamableSubset() {
        return streamableSubset;
    }

    public Integer getBlockSize() {
        return blockSize;
    }

    public int getContainer() {
        return container;
    }

    public Integer getOggSerialNumber() {
        return oggSerialNumber;
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
