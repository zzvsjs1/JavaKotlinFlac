package org.zzvsjs.jflac.internal;

import org.zzvsjs.jflac.FlacApplicationBlock;
import org.zzvsjs.jflac.FlacCueSheet;
import org.zzvsjs.jflac.FlacPaddingBlock;
import org.zzvsjs.jflac.FlacPicture;
import org.zzvsjs.jflac.FlacSeekTable;
import org.zzvsjs.jflac.FlacUnknownMetadataBlock;

import java.util.Objects;

final class NativeTransportChecks {
    private static final int FLAC_METADATA_TYPE_STREAMINFO = 0;
    private static final int FLAC_METADATA_TYPE_PADDING = 1;
    private static final int FLAC_METADATA_TYPE_APPLICATION = 2;
    private static final int FLAC_METADATA_TYPE_SEEKTABLE = 3;
    private static final int FLAC_METADATA_TYPE_VORBIS_COMMENT = 4;
    private static final int FLAC_METADATA_TYPE_CUESHEET = 5;
    private static final int FLAC_METADATA_TYPE_PICTURE = 6;
    private static final int FLAC_UNKNOWN_METADATA_MIN_TYPE = 7;
    private static final int FLAC_UNKNOWN_METADATA_MAX_TYPE = 126;

    private NativeTransportChecks() {
    }

    static int[] copyOf(int[] values, String name) {
        return Objects.requireNonNull(values, name).clone();
    }

    static <T> T[] copyOf(T[] values, String name) {
        return Objects.requireNonNull(values, name).clone();
    }

    static void requireSameLength(String firstName, int firstLength, String secondName, int secondLength) {
        if (firstLength != secondLength) {
            throw new IllegalArgumentException(firstName + " and " + secondName + " must have the same length.");
        }
    }

    static void requireOrderedMetadataValues(int[] metadataBlockTypes, Object[] metadataBlockValues) {
        requireSameLength(
                "metadataBlockTypes",
                metadataBlockTypes.length,
                "metadataBlockValues",
                metadataBlockValues.length
        );

        for (int index = 0; index < metadataBlockTypes.length; index += 1) {
            int blockType = metadataBlockTypes[index];
            Object blockValue = Objects.requireNonNull(
                    metadataBlockValues[index],
                    "metadataBlockValues[" + index + "]"
            );
            Class<?> expectedClass = orderedMetadataValueClass(blockType);
            if (!expectedClass.isInstance(blockValue)) {
                throw new IllegalArgumentException(
                        "metadataBlockValues[" + index + "] must be " + expectedClass.getSimpleName() +
                                " for metadata type " + blockType + "."
                );
            }
        }
    }

    private static Class<?> orderedMetadataValueClass(int blockType) {
        switch (blockType) {
            case FLAC_METADATA_TYPE_STREAMINFO:
                throw new IllegalArgumentException("STREAMINFO metadata blocks cannot be supplied.");
            case FLAC_METADATA_TYPE_PADDING:
                return FlacPaddingBlock.class;
            case FLAC_METADATA_TYPE_APPLICATION:
                return FlacApplicationBlock.class;
            case FLAC_METADATA_TYPE_SEEKTABLE:
                return FlacSeekTable.class;
            case FLAC_METADATA_TYPE_VORBIS_COMMENT:
                return NativeVorbisCommentBlock.class;
            case FLAC_METADATA_TYPE_CUESHEET:
                return FlacCueSheet.class;
            case FLAC_METADATA_TYPE_PICTURE:
                return FlacPicture.class;
            default:
                if (blockType >= FLAC_UNKNOWN_METADATA_MIN_TYPE && blockType <= FLAC_UNKNOWN_METADATA_MAX_TYPE) {
                    return FlacUnknownMetadataBlock.class;
                }
                throw new IllegalArgumentException("Unknown metadata type must be in the FLAC reserved range 7..126.");
        }
    }

    static void requireNoEmbeddedNul(String value, String name) {
        if (value != null && value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " must not contain embedded NUL characters.");
        }
    }
}
