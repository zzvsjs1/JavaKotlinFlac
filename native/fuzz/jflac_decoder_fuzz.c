#include "jflac_fuzz_transport.h"

#include <FLAC/metadata.h>
#include <FLAC/stream_decoder.h>

#include <stddef.h>
#include <stdint.h>

/* Stop recovery paths from manufacturing an unbounded amount of PCM. */
#define JFLAC_FUZZ_MAX_DECODED_FRAMES (1024u * 1024u)

typedef struct DecoderFuzzContext
{
    JflacFuzzTransport transport;
    uint64_t decoded_frames;
    uint64_t checksum;
    uint32_t metadata_blocks;
    uint32_t error_callbacks;
} DecoderFuzzContext;

/* Keeps callback memory reads observable after an iteration has completed. */
static volatile uint64_t g_decoder_fuzz_sink;

static FLAC__StreamDecoderReadStatus decoder_read_callback(
    const FLAC__StreamDecoder *decoder,
    FLAC__byte buffer[],
    size_t *bytes,
    void *client_data)
{
    (void)decoder;
    DecoderFuzzContext *context = (DecoderFuzzContext *)client_data;
    if (context == NULL || buffer == NULL || bytes == NULL || *bytes == 0u)
    {
        if (bytes != NULL)
        {
            *bytes = 0u;
        }

        return FLAC__STREAM_DECODER_READ_STATUS_ABORT;
    }

    size_t read = jflac_fuzz_transport_read(&context->transport, buffer, *bytes);
    *bytes = read;
    return read == 0u ? FLAC__STREAM_DECODER_READ_STATUS_END_OF_STREAM
                      : FLAC__STREAM_DECODER_READ_STATUS_CONTINUE;
}

static FLAC__StreamDecoderSeekStatus decoder_seek_callback(
    const FLAC__StreamDecoder *decoder,
    FLAC__uint64 absolute_byte_offset,
    void *client_data)
{
    (void)decoder;
    DecoderFuzzContext *context = (DecoderFuzzContext *)client_data;
    return context != NULL &&
                   jflac_fuzz_transport_seek_absolute(&context->transport, absolute_byte_offset) == 0
               ? FLAC__STREAM_DECODER_SEEK_STATUS_OK
               : FLAC__STREAM_DECODER_SEEK_STATUS_ERROR;
}

static FLAC__StreamDecoderTellStatus decoder_tell_callback(
    const FLAC__StreamDecoder *decoder,
    FLAC__uint64 *absolute_byte_offset,
    void *client_data)
{
    (void)decoder;
    DecoderFuzzContext *context = (DecoderFuzzContext *)client_data;
    if (context == NULL || absolute_byte_offset == NULL)
    {
        return FLAC__STREAM_DECODER_TELL_STATUS_ERROR;
    }

    *absolute_byte_offset = jflac_fuzz_transport_tell(&context->transport);
    return FLAC__STREAM_DECODER_TELL_STATUS_OK;
}

static FLAC__StreamDecoderLengthStatus decoder_length_callback(
    const FLAC__StreamDecoder *decoder,
    FLAC__uint64 *stream_length,
    void *client_data)
{
    (void)decoder;
    DecoderFuzzContext *context = (DecoderFuzzContext *)client_data;
    if (context == NULL || stream_length == NULL)
    {
        return FLAC__STREAM_DECODER_LENGTH_STATUS_ERROR;
    }

    *stream_length = jflac_fuzz_transport_length(&context->transport);
    return FLAC__STREAM_DECODER_LENGTH_STATUS_OK;
}

static FLAC__bool decoder_eof_callback(const FLAC__StreamDecoder *decoder, void *client_data)
{
    (void)decoder;
    DecoderFuzzContext *context = (DecoderFuzzContext *)client_data;
    return context == NULL || jflac_fuzz_transport_eof(&context->transport) ? true : false;
}

static FLAC__StreamDecoderWriteStatus decoder_write_callback(
    const FLAC__StreamDecoder *decoder,
    const FLAC__Frame *frame,
    const FLAC__int32 *const buffer[],
    void *client_data)
{
    (void)decoder;
    DecoderFuzzContext *context = (DecoderFuzzContext *)client_data;
    if (context == NULL || frame == NULL || buffer == NULL ||
        frame->header.channels == 0u || frame->header.channels > FLAC__MAX_CHANNELS)
    {
        return FLAC__STREAM_DECODER_WRITE_STATUS_ABORT;
    }

    uint64_t blocksize = (uint64_t)frame->header.blocksize;
    if (blocksize > JFLAC_FUZZ_MAX_DECODED_FRAMES - context->decoded_frames)
    {
        return FLAC__STREAM_DECODER_WRITE_STATUS_ABORT;
    }

    for (uint32_t channel = 0u; channel < frame->header.channels; ++channel)
    {
        if (buffer[channel] == NULL)
        {
            return FLAC__STREAM_DECODER_WRITE_STATUS_ABORT;
        }

        if (frame->header.blocksize > 0u)
        {
            context->checksum ^= (uint32_t)buffer[channel][0];
            context->checksum ^= (uint64_t)(uint32_t)buffer[channel][frame->header.blocksize - 1u] << 32u;
        }
    }

    context->decoded_frames += blocksize;
    return FLAC__STREAM_DECODER_WRITE_STATUS_CONTINUE;
}

static void decoder_metadata_callback(
    const FLAC__StreamDecoder *decoder,
    const FLAC__StreamMetadata *metadata,
    void *client_data)
{
    (void)decoder;
    DecoderFuzzContext *context = (DecoderFuzzContext *)client_data;
    if (context == NULL || metadata == NULL)
    {
        return;
    }

    context->metadata_blocks += 1u;
    context->checksum ^= (uint64_t)metadata->type;
    context->checksum ^= (uint64_t)metadata->length << 8u;

    /*
     * These validators traverse the pointer-bearing metadata variants and
     * complement the JVM integration tests that only consume valid objects.
     */
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
}

static void decoder_error_callback(
    const FLAC__StreamDecoder *decoder,
    FLAC__StreamDecoderErrorStatus status,
    void *client_data)
{
    (void)decoder;
    DecoderFuzzContext *context = (DecoderFuzzContext *)client_data;
    if (context == NULL)
    {
        return;
    }

    context->error_callbacks += 1u;
    context->checksum ^= (uint64_t)status << 48u;
}

static uint64_t choose_seek_target(const uint8_t *data, size_t size, uint64_t total_samples)
{
    uint64_t value = 0u;
    size_t bytes_to_use = size < sizeof(value) ? size : sizeof(value);
    for (size_t index = 0u; index < bytes_to_use; ++index)
    {
        value = (value << 8u) | data[index];
    }

    return total_samples == 0u ? 0u : value % total_samples;
}

static void run_decoder_variant(const uint8_t *data, size_t size, int use_ogg, int seekable)
{
    DecoderFuzzContext context;
    context.decoded_frames = 0u;
    context.checksum = 0u;
    context.metadata_blocks = 0u;
    context.error_callbacks = 0u;
    jflac_fuzz_transport_init(&context.transport, data, size);

    FLAC__StreamDecoder *decoder = FLAC__stream_decoder_new();
    if (decoder == NULL)
    {
        return;
    }

    (void)FLAC__stream_decoder_set_md5_checking(decoder, false);
    (void)FLAC__stream_decoder_set_metadata_respond_all(decoder);
    if (use_ogg)
    {
        (void)FLAC__stream_decoder_set_decode_chained_stream(decoder, true);
    }

    FLAC__StreamDecoderInitStatus init_status;
    if (use_ogg)
    {
        init_status = FLAC__stream_decoder_init_ogg_stream(
            decoder,
            decoder_read_callback,
            seekable ? decoder_seek_callback : NULL,
            seekable ? decoder_tell_callback : NULL,
            seekable ? decoder_length_callback : NULL,
            seekable ? decoder_eof_callback : NULL,
            decoder_write_callback,
            decoder_metadata_callback,
            decoder_error_callback,
            &context);
    }
    else
    {
        init_status = FLAC__stream_decoder_init_stream(
            decoder,
            decoder_read_callback,
            seekable ? decoder_seek_callback : NULL,
            seekable ? decoder_tell_callback : NULL,
            seekable ? decoder_length_callback : NULL,
            seekable ? decoder_eof_callback : NULL,
            decoder_write_callback,
            decoder_metadata_callback,
            decoder_error_callback,
            &context);
    }

    if (init_status == FLAC__STREAM_DECODER_INIT_STATUS_OK)
    {
        (void)FLAC__stream_decoder_process_until_end_of_metadata(decoder);

        if (seekable)
        {
            uint64_t total_samples = FLAC__stream_decoder_get_total_samples(decoder);
            if (total_samples > 0u)
            {
                (void)FLAC__stream_decoder_seek_absolute(
                    decoder,
                    choose_seek_target(data, size, total_samples));
            }
        }

        (void)FLAC__stream_decoder_process_until_end_of_stream(decoder);
        (void)FLAC__stream_decoder_finish(decoder);
    }

    FLAC__stream_decoder_delete(decoder);
    g_decoder_fuzz_sink ^= context.checksum;
    g_decoder_fuzz_sink ^= context.decoded_frames;
    g_decoder_fuzz_sink ^= (uint64_t)context.metadata_blocks << 32u;
    g_decoder_fuzz_sink ^= context.error_callbacks;
}

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size)
{
    if (size > JFLAC_FUZZ_MAX_INPUT_SIZE || (data == NULL && size != 0u))
    {
        return 0;
    }

    /*
     * Every input exercises all transports and both containers. Parser errors
     * are expected and return normally; sanitizer findings remain hard process
     * failures handled by libFuzzer and the Gradle smoke task.
     */
    run_decoder_variant(data, size, 0, 0);
    run_decoder_variant(data, size, 0, 1);
    run_decoder_variant(data, size, 1, 0);
    run_decoder_variant(data, size, 1, 1);
    return 0;
}
