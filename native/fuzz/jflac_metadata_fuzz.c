#include "jflac_fuzz_transport.h"

#include <FLAC/metadata.h>

#include <errno.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>

/* A valid FLAC input cannot contain this many blocks inside the input limit. */
#define JFLAC_FUZZ_MAX_METADATA_BLOCKS 512u

typedef struct MetadataFuzzContext
{
    JflacFuzzTransport transport;
    uint64_t checksum;
} MetadataFuzzContext;

static volatile uint64_t g_metadata_fuzz_sink;

static size_t metadata_read_callback(
    void *destination,
    size_t record_size,
    size_t record_count,
    FLAC__IOHandle handle)
{
    MetadataFuzzContext *context = (MetadataFuzzContext *)handle;
    if (context == NULL || destination == NULL || record_size == 0u || record_count == 0u)
    {
        return 0u;
    }

    return jflac_fuzz_transport_read_records(
        &context->transport,
        destination,
        record_size,
        record_count);
}

static int metadata_seek_callback(FLAC__IOHandle handle, FLAC__int64 offset, int whence)
{
    MetadataFuzzContext *context = (MetadataFuzzContext *)handle;
    if (context == NULL)
    {
        errno = EINVAL;
        return -1;
    }

    int result = jflac_fuzz_transport_seek(&context->transport, offset, whence);
    if (result != 0)
    {
        errno = EINVAL;
    }

    return result;
}

static FLAC__int64 metadata_tell_callback(FLAC__IOHandle handle)
{
    MetadataFuzzContext *context = (MetadataFuzzContext *)handle;
    if (context == NULL)
    {
        errno = EINVAL;
        return -1;
    }

    uint64_t position = jflac_fuzz_transport_tell(&context->transport);
    if (position > (uint64_t)INT64_MAX)
    {
        errno = EOVERFLOW;
        return -1;
    }

    return (FLAC__int64)position;
}

static int metadata_eof_callback(FLAC__IOHandle handle)
{
    MetadataFuzzContext *context = (MetadataFuzzContext *)handle;
    return context == NULL || jflac_fuzz_transport_eof(&context->transport);
}

static void inspect_metadata_block(MetadataFuzzContext *context, const FLAC__StreamMetadata *metadata)
{
    context->checksum ^= (uint64_t)metadata->type;
    context->checksum ^= (uint64_t)metadata->length << 8u;
    context->checksum ^= metadata->is_last ? UINT64_C(0x8000000000000000) : 0u;

    const char *violation = NULL;
    switch (metadata->type)
    {
    case FLAC__METADATA_TYPE_SEEKTABLE:
        (void)FLAC__metadata_object_seektable_is_legal(metadata);
        break;
    case FLAC__METADATA_TYPE_CUESHEET:
        (void)FLAC__metadata_object_cuesheet_is_legal(metadata, true, &violation);
        break;
    case FLAC__METADATA_TYPE_PICTURE:
        (void)FLAC__metadata_object_picture_is_legal(metadata, &violation);
        break;
    default:
        break;
    }

    /* Deep cloning traverses all owned arrays and strings in the parsed block. */
    FLAC__StreamMetadata *clone = FLAC__metadata_object_clone(metadata);
    if (clone != NULL)
    {
        context->checksum ^= (uint64_t)clone->type << 40u;
        FLAC__metadata_object_delete(clone);
    }
}

static void run_metadata_variant(const uint8_t *data, size_t size, int use_ogg)
{
    MetadataFuzzContext context;
    context.checksum = 0u;
    jflac_fuzz_transport_init(&context.transport, data, size);

    FLAC__Metadata_Chain *chain = FLAC__metadata_chain_new();
    if (chain == NULL)
    {
        return;
    }

    FLAC__IOCallbacks callbacks;
    callbacks.read = metadata_read_callback;
    callbacks.write = NULL;
    callbacks.seek = metadata_seek_callback;
    callbacks.tell = metadata_tell_callback;
    callbacks.eof = metadata_eof_callback;
    callbacks.close = NULL;

    FLAC__bool read_success = use_ogg
                                  ? FLAC__metadata_chain_read_ogg_with_callbacks(chain, &context, callbacks)
                                  : FLAC__metadata_chain_read_with_callbacks(chain, &context, callbacks);
    if (read_success)
    {
        FLAC__Metadata_Iterator *iterator = FLAC__metadata_iterator_new();
        if (iterator != NULL)
        {
            FLAC__metadata_iterator_init(iterator, chain);

            uint32_t block_count = 0u;
            do
            {
                FLAC__StreamMetadata *metadata = FLAC__metadata_iterator_get_block(iterator);
                if (metadata == NULL)
                {
                    break;
                }

                inspect_metadata_block(&context, metadata);
                block_count += 1u;
            } while (block_count < JFLAC_FUZZ_MAX_METADATA_BLOCKS &&
                     FLAC__metadata_iterator_next(iterator));

            context.checksum ^= (uint64_t)block_count << 24u;
            FLAC__metadata_iterator_delete(iterator);
        }

        /* Exercise the in-memory edit operations without ever writing a file. */
        FLAC__metadata_chain_merge_padding(chain);
        FLAC__metadata_chain_sort_padding(chain);
    }
    else
    {
        context.checksum ^= (uint64_t)FLAC__metadata_chain_status(chain) << 56u;
    }

    FLAC__metadata_chain_delete(chain);
    g_metadata_fuzz_sink ^= context.checksum;
}

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size)
{
    if (size > JFLAC_FUZZ_MAX_INPUT_SIZE || (data == NULL && size != 0u))
    {
        return 0;
    }

    /*
     * Rejected and truncated metadata are normal parser outcomes. Only memory
     * safety failures, undefined behaviour, timeouts, or resource-limit exits
     * are hard failures in the sanitizer smoke task.
     */
    run_metadata_variant(data, size, 0);
    run_metadata_variant(data, size, 1);
    return 0;
}
