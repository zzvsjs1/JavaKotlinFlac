/*
 * JNI shim for the Kotlin/Java wrapper.
 *
 * Design outline:
 * - The Kotlin/Java API never exposes a raw libFLAC pointer. Native encoder
 *   handles and reusable decoder session handles are opaque values whose
 *   lifetime is owned by this file.
 * - The library resolves the bundled libFLAC symbols at runtime. This keeps the JVM
 *   library loadable even when the bundled FLAC runtime is missing, and lets
 *   Java receive a controlled NativeLoadException instead of a loader failure.
 * - File paths use libFLAC's file helpers. Java InputStream and
 *   SeekableByteChannel use libFLAC's callback APIs so stream/channel callers
 *   do not need temporary files.
 * - Every callback treats a pending Java exception as authoritative. If Java
 *   already threw, native code aborts libFLAC and avoids replacing the original
 *   exception type or stack trace with a synthetic native exception.
 * - Ownership is intentionally simple: libFLAC-owned data is copied into JVM
 *   objects before returning to Java, and JVM objects kept beyond one JNI call
 *   are promoted to global references and released in the matching destroy
 *   helper.
 *
 * Example flows:
 * - FlacDecoder.decode(path, consumer) allocates one decoder, emits callbacks
 *   synchronously, then destroys the decoder before returning.
 * - FlacDecoder.openPull(path) registers a decoder handle; each Java read
 *   advances libFLAC a little and returns a frame count, 0 for a zero-frame
 *   request, or -1 once end-of-stream has no pending PCM left.
 * - FlacEncoder.open(stream, ...) registers an encoder handle. finishEncoder()
 *   is the successful close path; releaseEncoder() is the abandon path and can
 *   leave incomplete output by design.
 */
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "FLAC/format.h"
#include "FLAC/metadata.h"
#include "FLAC/stream_decoder.h"
#include "FLAC/stream_encoder.h"
#include <jni.h>
#include "jflac_platform.h"
#include "org_zzvsjs_jflac_internal_NativeBindings.h"

/*
 * FLAC's API triplet is a compile-time header contract. The runtime string is
 * checked separately after the bundled runtime is loaded, so neither stale headers nor a
 * mismatched runtime can pass independently.
 */
#if FLAC_API_VERSION_CURRENT != JFLAC_EXPECTED_FLAC_API_VERSION_CURRENT ||                                          \
    FLAC_API_VERSION_REVISION != JFLAC_EXPECTED_FLAC_API_VERSION_REVISION ||                                      \
    FLAC_API_VERSION_AGE != JFLAC_EXPECTED_FLAC_API_VERSION_AGE
#error "The FLAC headers do not match the API version expected by this JNI shim."
#endif

/*
 * Central native constants. Keep the "magic" values named here so callback
 * code and metadata conversion code can describe policy instead of repeating
 * raw bitstream numbers.
 */
#define JFLAC_MESSAGE_BUFFER_SIZE 256u              /* Enough for short native error messages built with snprintf. */
#define JFLAC_STREAMINFO_MD5_LENGTH 16u             /* STREAMINFO stores an MD5 digest as exactly 128 bits. */
#define JFLAC_APPLICATION_ID_LENGTH 4u              /* FLAC APPLICATION blocks begin with a four-byte registered ID. */
#define JFLAC_FIRST_VALID_SESSION_HANDLE 1LL        /* Zero is reserved as the invalid Java-visible handle. */
#define JFLAC_EMIT_METADATA_CALLBACKS 0             /* One-shot decode should forward STREAMINFO to Java. */
#define JFLAC_SUPPRESS_METADATA_CALLBACKS 1         /* Session range decode reuses cached STREAMINFO. */
#define JFLAC_UNLIMITED_RANGE 0                     /* Decode until end-of-stream. */
#define JFLAC_LIMITED_RANGE 1                       /* Stop after the requested maxFrames window. */
#define JFLAC_CONTAINER_NATIVE 0                    /* Raw native FLAC stream, starting with fLaC. */
#define JFLAC_CONTAINER_OGG 1                       /* Ogg container carrying a FLAC stream. */
#define JFLAC_SEEKPOINT_PLACEHOLDER_JAVA_VALUE -1LL /* Signed Java sentinel for libFLAC's UINT64_MAX placeholder. */
/*
 * FLAC stores metadata body lengths in a 24-bit header field and reserves
 * type codes 7..126 for block types this wrapper may not understand yet.
 */
#define JFLAC_METADATA_MAX_BLOCK_LENGTH 0xFFFFFFu /* Maximum value representable by the 24-bit metadata length. */
#define JFLAC_UNKNOWN_METADATA_MIN_TYPE 7         /* Type codes 0..6 are currently defined by the FLAC format. */
#define JFLAC_UNKNOWN_METADATA_MAX_TYPE 126       /* Type code 127 is invalid/reserved by the FLAC format. */
/*
 * The FLAC seekpoint placeholder is all bits set. Keep a local constant here
 * instead of linking against libFLAC's exported variable because this shim
 * resolves libFLAC symbols dynamically at runtime.
 * The Java-facing value is -1 because signed long cannot represent UINT64_MAX.
 */
#define JFLAC_SEEKPOINT_PLACEHOLDER UINT64_MAX

/*
 * Dynamically resolved libFLAC entry points. Function pointers stay grouped in
 * one table so flac_api_ready() can fail before any JNI method dereferences a
 * missing symbol, and so tests can see one consistent error path for missing
 * or incompatible bundled libFLAC versions.
 */
typedef struct FlacApi
{
    /*
     * These exported data symbols identify the exact runtime loaded into the
     * process. Resolving them alongside the functions prevents an older or
     * non-Ogg libFLAC runtime from satisfying only the subset of function names that
     * this wrapper happens to use.
     */
    const char **version_string;
    int *supports_ogg_flac;

    FLAC__Metadata_Chain *(*metadata_chain_new)(void);
    void (*metadata_chain_delete)(FLAC__Metadata_Chain *chain);
    FLAC__bool (*metadata_chain_read)(FLAC__Metadata_Chain *chain, const char *filename);
    FLAC__bool (*metadata_chain_read_ogg)(FLAC__Metadata_Chain *chain, const char *filename);
    FLAC__Metadata_ChainStatus (*metadata_chain_status)(FLAC__Metadata_Chain *chain);
    FLAC__bool (*metadata_chain_write)(FLAC__Metadata_Chain *chain, FLAC__bool use_padding,
                                       FLAC__bool preserve_file_stats);
    FLAC__Metadata_Iterator *(*metadata_iterator_new)(void);
    void (*metadata_iterator_delete)(FLAC__Metadata_Iterator *iterator);
    void (*metadata_iterator_init)(FLAC__Metadata_Iterator *iterator, FLAC__Metadata_Chain *chain);
    FLAC__StreamMetadata *(*metadata_iterator_get_block)(FLAC__Metadata_Iterator *iterator);
    FLAC__bool (*metadata_iterator_next)(FLAC__Metadata_Iterator *iterator);
    FLAC__bool (*metadata_iterator_delete_block)(FLAC__Metadata_Iterator *iterator,
                                                 FLAC__bool replace_with_padding);
    FLAC__bool (*metadata_iterator_insert_block_after)(FLAC__Metadata_Iterator *iterator,
                                                       FLAC__StreamMetadata *block);

    FLAC__StreamDecoder *(*stream_decoder_new)(void);
    void (*stream_decoder_delete)(FLAC__StreamDecoder *decoder);
    FLAC__bool (*stream_decoder_set_decode_chained_stream)(FLAC__StreamDecoder *decoder, FLAC__bool value);
    FLAC__bool (*stream_decoder_set_md5_checking)(FLAC__StreamDecoder *decoder, FLAC__bool value);
    FLAC__StreamDecoderInitStatus (*stream_decoder_init_file)(FLAC__StreamDecoder *decoder, const char *filename,
                                                              FLAC__StreamDecoderWriteCallback write_callback,
                                                              FLAC__StreamDecoderMetadataCallback metadata_callback,
                                                              FLAC__StreamDecoderErrorCallback error_callback,
                                                              void *client_data);
    FLAC__StreamDecoderInitStatus (*stream_decoder_init_ogg_file)(FLAC__StreamDecoder *decoder, const char *filename,
                                                                  FLAC__StreamDecoderWriteCallback write_callback,
                                                                  FLAC__StreamDecoderMetadataCallback metadata_callback,
                                                                  FLAC__StreamDecoderErrorCallback error_callback,
                                                                  void *client_data);
    FLAC__StreamDecoderInitStatus (*stream_decoder_init_stream)(
        FLAC__StreamDecoder *decoder, FLAC__StreamDecoderReadCallback read_callback,
        FLAC__StreamDecoderSeekCallback seek_callback, FLAC__StreamDecoderTellCallback tell_callback,
        FLAC__StreamDecoderLengthCallback length_callback, FLAC__StreamDecoderEofCallback eof_callback,
        FLAC__StreamDecoderWriteCallback write_callback, FLAC__StreamDecoderMetadataCallback metadata_callback,
        FLAC__StreamDecoderErrorCallback error_callback, void *client_data);
    FLAC__StreamDecoderInitStatus (*stream_decoder_init_ogg_stream)(
        FLAC__StreamDecoder *decoder, FLAC__StreamDecoderReadCallback read_callback,
        FLAC__StreamDecoderSeekCallback seek_callback, FLAC__StreamDecoderTellCallback tell_callback,
        FLAC__StreamDecoderLengthCallback length_callback, FLAC__StreamDecoderEofCallback eof_callback,
        FLAC__StreamDecoderWriteCallback write_callback, FLAC__StreamDecoderMetadataCallback metadata_callback,
        FLAC__StreamDecoderErrorCallback error_callback, void *client_data);
    FLAC__bool (*stream_decoder_process_until_end_of_metadata)(FLAC__StreamDecoder *decoder);
    FLAC__bool (*stream_decoder_seek_absolute)(FLAC__StreamDecoder *decoder, FLAC__uint64 sample);
    FLAC__bool (*stream_decoder_process_single)(FLAC__StreamDecoder *decoder);
    FLAC__bool (*stream_decoder_process_until_end_of_link)(FLAC__StreamDecoder *decoder);
    FLAC__bool (*stream_decoder_process_until_end_of_stream)(FLAC__StreamDecoder *decoder);
    FLAC__bool (*stream_decoder_finish_link)(FLAC__StreamDecoder *decoder);
    FLAC__bool (*stream_decoder_finish)(FLAC__StreamDecoder *decoder);
    FLAC__StreamDecoderState (*stream_decoder_get_state)(const FLAC__StreamDecoder *decoder);
    const char *(*stream_decoder_get_resolved_state_string)(const FLAC__StreamDecoder *decoder);

    FLAC__StreamEncoder *(*stream_encoder_new)(void);
    void (*stream_encoder_delete)(FLAC__StreamEncoder *encoder);
    FLAC__bool (*stream_encoder_set_verify)(FLAC__StreamEncoder *encoder, FLAC__bool value);
    FLAC__bool (*stream_encoder_set_streamable_subset)(FLAC__StreamEncoder *encoder, FLAC__bool value);
    FLAC__bool (*stream_encoder_set_channels)(FLAC__StreamEncoder *encoder, uint32_t value);
    FLAC__bool (*stream_encoder_set_bits_per_sample)(FLAC__StreamEncoder *encoder, uint32_t value);
    FLAC__bool (*stream_encoder_set_sample_rate)(FLAC__StreamEncoder *encoder, uint32_t value);
    FLAC__bool (*stream_encoder_set_compression_level)(FLAC__StreamEncoder *encoder, uint32_t value);
    FLAC__bool (*stream_encoder_set_blocksize)(FLAC__StreamEncoder *encoder, uint32_t value);
    uint32_t (*stream_encoder_set_num_threads)(FLAC__StreamEncoder *encoder, uint32_t value);
    FLAC__bool (*stream_encoder_set_total_samples_estimate)(FLAC__StreamEncoder *encoder, FLAC__uint64 value);
    FLAC__bool (*stream_encoder_set_ogg_serial_number)(FLAC__StreamEncoder *encoder, long serial_number);
    FLAC__bool (*stream_encoder_set_metadata)(FLAC__StreamEncoder *encoder, FLAC__StreamMetadata **metadata,
                                              uint32_t num_blocks);
    FLAC__StreamEncoderInitStatus (*stream_encoder_init_file)(FLAC__StreamEncoder *encoder, const char *filename,
                                                              FLAC__StreamEncoderProgressCallback progress_callback,
                                                              void *client_data);
    FLAC__StreamEncoderInitStatus (*stream_encoder_init_ogg_file)(
        FLAC__StreamEncoder *encoder, const char *filename, FLAC__StreamEncoderProgressCallback progress_callback,
        void *client_data);
    FLAC__StreamEncoderInitStatus (*stream_encoder_init_stream)(
        FLAC__StreamEncoder *encoder, FLAC__StreamEncoderWriteCallback write_callback,
        FLAC__StreamEncoderSeekCallback seek_callback, FLAC__StreamEncoderTellCallback tell_callback,
        FLAC__StreamEncoderMetadataCallback metadata_callback, void *client_data);
    FLAC__StreamEncoderInitStatus (*stream_encoder_init_ogg_stream)(
        FLAC__StreamEncoder *encoder, FLAC__StreamEncoderReadCallback read_callback,
        FLAC__StreamEncoderWriteCallback write_callback, FLAC__StreamEncoderSeekCallback seek_callback,
        FLAC__StreamEncoderTellCallback tell_callback, FLAC__StreamEncoderMetadataCallback metadata_callback,
        void *client_data);
    FLAC__bool (*stream_encoder_process_interleaved)(FLAC__StreamEncoder *encoder, const FLAC__int32 buffer[],
                                                     uint32_t samples);
    FLAC__bool (*stream_encoder_finish)(FLAC__StreamEncoder *encoder);
    const char *(*stream_encoder_get_resolved_state_string)(const FLAC__StreamEncoder *encoder);

    FLAC__StreamMetadata *(*metadata_object_new)(FLAC__MetadataType type);
    FLAC__StreamMetadata *(*metadata_object_clone)(const FLAC__StreamMetadata *object);
    void (*metadata_object_delete)(FLAC__StreamMetadata *object);
    FLAC__bool (*metadata_object_vorbiscomment_set_vendor_string)(FLAC__StreamMetadata *object,
                                                                  FLAC__StreamMetadata_VorbisComment_Entry entry,
                                                                  FLAC__bool copy);
    FLAC__bool (*metadata_object_vorbiscomment_append_comment)(FLAC__StreamMetadata *object,
                                                               FLAC__StreamMetadata_VorbisComment_Entry entry,
                                                               FLAC__bool copy);
    FLAC__bool (*metadata_object_picture_set_mime_type)(FLAC__StreamMetadata *object, char *mime_type, FLAC__bool copy);
    FLAC__bool (*metadata_object_picture_set_description)(FLAC__StreamMetadata *object, FLAC__byte *description,
                                                          FLAC__bool copy);
    FLAC__bool (*metadata_object_picture_set_data)(FLAC__StreamMetadata *object, FLAC__byte *data, FLAC__uint32 length,
                                                   FLAC__bool copy);
    FLAC__bool (*metadata_object_picture_is_legal)(const FLAC__StreamMetadata *object, const char **violation);
    FLAC__bool (*metadata_object_application_set_data)(FLAC__StreamMetadata *object, FLAC__byte *data, uint32_t length,
                                                       FLAC__bool copy);
    FLAC__bool (*metadata_object_seektable_resize_points)(FLAC__StreamMetadata *object, uint32_t new_num_points);
    void (*metadata_object_seektable_set_point)(FLAC__StreamMetadata *object, uint32_t point_num,
                                                FLAC__StreamMetadata_SeekPoint point);
    FLAC__bool (*metadata_object_seektable_is_legal)(const FLAC__StreamMetadata *object);
    FLAC__bool (*metadata_object_cuesheet_resize_tracks)(FLAC__StreamMetadata *object, uint32_t new_num_tracks);
    FLAC__bool (*metadata_object_cuesheet_track_resize_indices)(FLAC__StreamMetadata *object, uint32_t track_num,
                                                                uint32_t new_num_indices);
    FLAC__bool (*metadata_object_cuesheet_is_legal)(const FLAC__StreamMetadata *object, FLAC__bool check_cd_da_subset,
                                                    const char **violation);

    FLAC__bool (*format_sample_rate_is_valid)(uint32_t sample_rate);
    FLAC__bool (*format_sample_rate_is_subset)(uint32_t sample_rate);
    FLAC__bool (*format_blocksize_is_subset)(uint32_t blocksize, uint32_t sample_rate);
    FLAC__bool (*format_vorbiscomment_entry_name_is_legal)(const char *name);
    FLAC__bool (*format_vorbiscomment_entry_value_is_legal)(const FLAC__byte *value, uint32_t length);
    FLAC__bool (*format_vorbiscomment_entry_is_legal)(const FLAC__byte *entry, uint32_t length);
} FlacApi;

/*
 * Process-wide libFLAC resolver state. These values are written during
 * platform-neutral one-time initialisation and then read by every JNI entry
 * point before it calls through the function table.
 */
static FlacApi g_flac_api;
/* Keeps any owned loader reference alive for every resolved function call. */
static JflacModule g_flac_module;
static JflacOnce g_flac_init_once = JFLAC_ONCE_INITIALIZER;
static char g_flac_init_error[JFLAC_MESSAGE_BUFFER_SIZE] = "";
static int g_flac_api_initialized = 0;

/* Implemented beside the corresponding registries later in this file. */
static void destroy_all_decode_sessions(JNIEnv *env);
static void destroy_all_encode_contexts(JNIEnv *env);
static void destroy_registry_locks(void);

/* Stores the native initialisation failure for the later Java exception. */
static void set_last_init_error(const char *message)
{
    snprintf(g_flac_init_error, sizeof(g_flac_init_error), "%s", message);
}

/*
 * One-time dynamic resolver for the sibling libFLAC runtime. The JNI library does not link
 * directly to libFLAC, so all required exports are discovered after the JVM
 * has loaded the bundled FLAC runtime.
 */
static void init_flac_api(void)
{
    g_flac_api_initialized = 0;

    JflacModule module = {0};
    if (!jflac_open_sibling_library(JFLAC_FLAC_LIBRARY_FILENAME, &module, g_flac_init_error,
                                    sizeof(g_flac_init_error)))
    {
        return;
    }

#define RESOLVE(field, symbol)                                                                                         \
    if (!jflac_resolve_symbol(&module, #symbol, &g_flac_api.field, sizeof(g_flac_api.field),                           \
                              g_flac_init_error, sizeof(g_flac_init_error)))                                           \
    {                                                                                                                  \
        goto init_failed;                                                                                              \
    }

    RESOLVE(version_string, FLAC__VERSION_STRING);
    RESOLVE(supports_ogg_flac, FLAC_API_SUPPORTS_OGG_FLAC);

    const char *runtime_version = *g_flac_api.version_string;
    if (runtime_version == NULL || strcmp(runtime_version, JFLAC_EXPECTED_FLAC_VERSION) != 0)
    {
        char buffer[JFLAC_MESSAGE_BUFFER_SIZE];
        snprintf(buffer, sizeof(buffer), "Incompatible bundled libFLAC version: expected %s but found %s.",
                 JFLAC_EXPECTED_FLAC_VERSION, runtime_version != NULL ? runtime_version : "<missing>");
        set_last_init_error(buffer);
        goto init_failed;
    }

    if (*g_flac_api.supports_ogg_flac == 0)
    {
        set_last_init_error("The bundled libFLAC runtime was built without required Ogg FLAC support.");
        goto init_failed;
    }

    RESOLVE(metadata_chain_new, FLAC__metadata_chain_new);
    RESOLVE(metadata_chain_delete, FLAC__metadata_chain_delete);
    RESOLVE(metadata_chain_read, FLAC__metadata_chain_read);
    RESOLVE(metadata_chain_read_ogg, FLAC__metadata_chain_read_ogg);
    RESOLVE(metadata_chain_status, FLAC__metadata_chain_status);
    RESOLVE(metadata_chain_write, FLAC__metadata_chain_write);
    RESOLVE(metadata_iterator_new, FLAC__metadata_iterator_new);
    RESOLVE(metadata_iterator_delete, FLAC__metadata_iterator_delete);
    RESOLVE(metadata_iterator_init, FLAC__metadata_iterator_init);
    RESOLVE(metadata_iterator_get_block, FLAC__metadata_iterator_get_block);
    RESOLVE(metadata_iterator_next, FLAC__metadata_iterator_next);
    RESOLVE(metadata_iterator_delete_block, FLAC__metadata_iterator_delete_block);
    RESOLVE(metadata_iterator_insert_block_after, FLAC__metadata_iterator_insert_block_after);
    RESOLVE(stream_decoder_new, FLAC__stream_decoder_new);
    RESOLVE(stream_decoder_delete, FLAC__stream_decoder_delete);
    RESOLVE(stream_decoder_set_decode_chained_stream, FLAC__stream_decoder_set_decode_chained_stream);
    RESOLVE(stream_decoder_set_md5_checking, FLAC__stream_decoder_set_md5_checking);
    RESOLVE(stream_decoder_init_file, FLAC__stream_decoder_init_file);
    RESOLVE(stream_decoder_init_ogg_file, FLAC__stream_decoder_init_ogg_file);
    RESOLVE(stream_decoder_init_stream, FLAC__stream_decoder_init_stream);
    RESOLVE(stream_decoder_init_ogg_stream, FLAC__stream_decoder_init_ogg_stream);
    RESOLVE(stream_decoder_process_until_end_of_metadata, FLAC__stream_decoder_process_until_end_of_metadata);
    RESOLVE(stream_decoder_seek_absolute, FLAC__stream_decoder_seek_absolute);
    RESOLVE(stream_decoder_process_single, FLAC__stream_decoder_process_single);
    RESOLVE(stream_decoder_process_until_end_of_link, FLAC__stream_decoder_process_until_end_of_link);
    RESOLVE(stream_decoder_process_until_end_of_stream, FLAC__stream_decoder_process_until_end_of_stream);
    RESOLVE(stream_decoder_finish_link, FLAC__stream_decoder_finish_link);
    RESOLVE(stream_decoder_finish, FLAC__stream_decoder_finish);
    RESOLVE(stream_decoder_get_state, FLAC__stream_decoder_get_state);
    RESOLVE(stream_decoder_get_resolved_state_string, FLAC__stream_decoder_get_resolved_state_string);
    RESOLVE(stream_encoder_new, FLAC__stream_encoder_new);
    RESOLVE(stream_encoder_delete, FLAC__stream_encoder_delete);
    RESOLVE(stream_encoder_set_verify, FLAC__stream_encoder_set_verify);
    RESOLVE(stream_encoder_set_streamable_subset, FLAC__stream_encoder_set_streamable_subset);
    RESOLVE(stream_encoder_set_channels, FLAC__stream_encoder_set_channels);
    RESOLVE(stream_encoder_set_bits_per_sample, FLAC__stream_encoder_set_bits_per_sample);
    RESOLVE(stream_encoder_set_sample_rate, FLAC__stream_encoder_set_sample_rate);
    RESOLVE(stream_encoder_set_compression_level, FLAC__stream_encoder_set_compression_level);
    RESOLVE(stream_encoder_set_blocksize, FLAC__stream_encoder_set_blocksize);
    RESOLVE(stream_encoder_set_num_threads, FLAC__stream_encoder_set_num_threads);
    RESOLVE(stream_encoder_set_total_samples_estimate, FLAC__stream_encoder_set_total_samples_estimate);
    RESOLVE(stream_encoder_set_ogg_serial_number, FLAC__stream_encoder_set_ogg_serial_number);
    RESOLVE(stream_encoder_set_metadata, FLAC__stream_encoder_set_metadata);
    RESOLVE(stream_encoder_init_file, FLAC__stream_encoder_init_file);
    RESOLVE(stream_encoder_init_ogg_file, FLAC__stream_encoder_init_ogg_file);
    RESOLVE(stream_encoder_init_stream, FLAC__stream_encoder_init_stream);
    RESOLVE(stream_encoder_init_ogg_stream, FLAC__stream_encoder_init_ogg_stream);
    RESOLVE(stream_encoder_process_interleaved, FLAC__stream_encoder_process_interleaved);
    RESOLVE(stream_encoder_finish, FLAC__stream_encoder_finish);
    RESOLVE(stream_encoder_get_resolved_state_string, FLAC__stream_encoder_get_resolved_state_string);
    RESOLVE(metadata_object_new, FLAC__metadata_object_new);
    RESOLVE(metadata_object_clone, FLAC__metadata_object_clone);
    RESOLVE(metadata_object_delete, FLAC__metadata_object_delete);
    RESOLVE(metadata_object_vorbiscomment_set_vendor_string, FLAC__metadata_object_vorbiscomment_set_vendor_string);
    RESOLVE(metadata_object_vorbiscomment_append_comment, FLAC__metadata_object_vorbiscomment_append_comment);
    RESOLVE(metadata_object_picture_set_mime_type, FLAC__metadata_object_picture_set_mime_type);
    RESOLVE(metadata_object_picture_set_description, FLAC__metadata_object_picture_set_description);
    RESOLVE(metadata_object_picture_set_data, FLAC__metadata_object_picture_set_data);
    RESOLVE(metadata_object_picture_is_legal, FLAC__metadata_object_picture_is_legal);
    RESOLVE(metadata_object_application_set_data, FLAC__metadata_object_application_set_data);
    RESOLVE(metadata_object_seektable_resize_points, FLAC__metadata_object_seektable_resize_points);
    RESOLVE(metadata_object_seektable_set_point, FLAC__metadata_object_seektable_set_point);
    RESOLVE(metadata_object_seektable_is_legal, FLAC__metadata_object_seektable_is_legal);
    RESOLVE(metadata_object_cuesheet_resize_tracks, FLAC__metadata_object_cuesheet_resize_tracks);
    RESOLVE(metadata_object_cuesheet_track_resize_indices, FLAC__metadata_object_cuesheet_track_resize_indices);
    RESOLVE(metadata_object_cuesheet_is_legal, FLAC__metadata_object_cuesheet_is_legal);
    RESOLVE(format_sample_rate_is_valid, FLAC__format_sample_rate_is_valid);
    RESOLVE(format_sample_rate_is_subset, FLAC__format_sample_rate_is_subset);
    RESOLVE(format_blocksize_is_subset, FLAC__format_blocksize_is_subset);
    RESOLVE(format_vorbiscomment_entry_name_is_legal, FLAC__format_vorbiscomment_entry_name_is_legal);
    RESOLVE(format_vorbiscomment_entry_value_is_legal, FLAC__format_vorbiscomment_entry_value_is_legal);
    RESOLVE(format_vorbiscomment_entry_is_legal, FLAC__format_vorbiscomment_entry_is_legal);

#undef RESOLVE
    /* Transfer the loader reference only after the complete API is usable. */
    g_flac_module = module;
    module.handle = NULL;
    module.owns_handle = 0;
    g_flac_api_initialized = 1;
    return;

init_failed:
    /* A partially resolved table must not outlive the module it points into. */
    memset(&g_flac_api, 0, sizeof(g_flac_api));
    if (!jflac_close_library(&module))
    {
        /*
         * init_flac_api() runs once under g_flac_init_once, so no other thread
         * can publish or replace g_flac_module here. Preserve the sole loader
         * reference globally so JNI_OnUnload can retry instead of losing it
         * with this stack-local owner.
         */
        g_flac_module = module;
        module.handle = NULL;
        module.owns_handle = 0;
    }
}

/*
 * Ensures libFLAC exports are available before a JNI entry point does native
 * work. Failure is translated into the public NativeLoadException.
 */
static int flac_api_ready(JNIEnv *env)
{
    jflac_call_once(&g_flac_init_once, init_flac_api);
    if (!g_flac_api_initialized)
    {
        jclass exception_class = (*env)->FindClass(env, "org/zzvsjs/jflac/NativeLoadException");
        if (exception_class != NULL)
        {
            (*env)->ThrowNew(env, exception_class,
                             g_flac_init_error[0] ? g_flac_init_error : "Failed to initialize FLAC API.");
        }

        return 0;
    }

    return 1;
}

/*
 * JNI_OnUnload runs only after the defining class loader is unreachable, so
 * no Java thread can still enter this shim. Keeping the module until this
 * point protects every resolved pointer for the full JNI library lifetime.
 * Abandoned sessions are destroyed first because their destructors still
 * call libFLAC and may need to release JNI global references.
 */
JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved)
{
    /*
     * JNI_OnUnload may run on a VM-owned thread which is not attached for JNI
     * calls. Global references still need a valid environment, so attach only
     * when GetEnv reports a detached thread and undo only that attachment.
     */
    JNIEnv *env = NULL;
    int attached_here = 0;
    jint env_status = (*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_8);
    (void)reserved;

    if (env_status == JNI_EDETACHED)
    {
        env = NULL;
        if ((*vm)->AttachCurrentThread(vm, (void **)&env, NULL) == JNI_OK)
        {
            attached_here = 1;
        }
    }
    else if (env_status != JNI_OK)
    {
        env = NULL;
    }

    /*
     * libFLAC's delete paths abandon unfinished work and do not invoke the
     * Java transport callbacks. If the VM refuses attachment during teardown,
     * env remains NULL and the destructors free native state while deliberately
     * skipping DeleteGlobalRef rather than calling JNI through an invalid env.
     */
    destroy_all_decode_sessions(env);
    destroy_all_encode_contexts(env);

    /*
     * POSIX mutex implementations may own resources beyond their static
     * storage. No native entry point can still use these locks once unload has
     * detached every registered owner.
     */
    destroy_registry_locks();

    if (attached_here)
    {
        (void)(*vm)->DetachCurrentThread(vm);
    }

    g_flac_api_initialized = 0;
    memset(&g_flac_api, 0, sizeof(g_flac_api));
    /*
     * This is the final retry for a loader reference retained after an earlier
     * initialisation failure. A rejected unload keeps the module unchanged
     * until process teardown; no caller falsely observes the reference as
     * released.
     */
    (void)jflac_close_library(&g_flac_module);
}

/*
 * Raises the public decode exception from native read/decode failures.
 * Callers should check ExceptionCheck before using this helper so callback
 * failures from Java are not hidden by a later native status message.
 */
static void throw_decode_exception(JNIEnv *env, const char *message)
{
    jclass exception_class = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacDecodeException");
    if (exception_class != NULL)
    {
        (*env)->ThrowNew(env, exception_class, message);
    }
}

/*
 * Raises the public encode exception from native encode failures. This is used
 * for both libFLAC encoder state failures and Java output callback failures
 * that need to be reported after libFLAC unwinds.
 */
static void throw_encode_exception(JNIEnv *env, const char *message)
{
    jclass exception_class = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacEncodeException");
    if (exception_class != NULL)
    {
        (*env)->ThrowNew(env, exception_class, message);
    }
}

/*
 * Raises the public metadata edit exception from native edit failures. Metadata
 * editing is intentionally file-based, so these failures normally come from
 * libFLAC metadata chain read/write status codes.
 */
static void throw_metadata_edit_exception(JNIEnv *env, const char *message)
{
    jclass exception_class = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacMetadataEditException");
    if (exception_class != NULL)
    {
        (*env)->ThrowNew(env, exception_class, message);
    }
}

/*
 * Raises IllegalArgumentException for caller contract violations detected in
 * native code, such as null handles, negative ranges, and invalid metadata
 * payload sizes that should have been rejected by the public API.
 */
static void throw_illegal_argument_exception(JNIEnv *env, const char *message)
{
    jclass exception_class = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
    if (exception_class != NULL)
    {
        (*env)->ThrowNew(env, exception_class, message);
    }
}

static int is_valid_container(jint container)
{
    return container == JFLAC_CONTAINER_NATIVE || container == JFLAC_CONTAINER_OGG;
}

static int validate_container(JNIEnv *env, jint container)
{
    if (!is_valid_container(container))
    {
        throw_illegal_argument_exception(env, "FLAC container code must be native or Ogg.");
        return 0;
    }

    return 1;
}

/* Raises a stable capability error when this libFLAC build lacks an optional feature. */
static void throw_unsupported_feature_exception(JNIEnv *env, const char *message)
{
    jclass exception_class = (*env)->FindClass(env, "org/zzvsjs/jflac/UnsupportedFeatureException");
    if (exception_class != NULL)
    {
        (*env)->ThrowNew(env, exception_class, message);
    }
}

JNIEXPORT void JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_verifyRuntime(JNIEnv *env, jclass clazz)
{
    (void)clazz;
    (void)flac_api_ready(env);
}

/*
 * Enables libFLAC's end-to-end PCM checksum before decoder initialisation.
 * The setting is intentionally applied only to one-shot whole-stream entry
 * points. Seeking disables libFLAC's MD5 accumulator, while an early range or
 * pull-session close cannot produce a checksum for the complete stream.
 */
static int configure_decoder_md5_checking(JNIEnv *env, FLAC__StreamDecoder *decoder, jboolean check_md5)
{
    if (check_md5 == JNI_FALSE)
    {
        return 1;
    }

    if (!g_flac_api.stream_decoder_set_md5_checking(decoder, true))
    {
        throw_decode_exception(env, "Failed to enable FLAC MD5 checking before decoder initialisation.");
        return 0;
    }

    return 1;
}

/* Enables FLAC 1.5 chained-link decoding before the Ogg decoder is initialised. */
static int configure_decoder_chaining(JNIEnv *env, FLAC__StreamDecoder *decoder, jint container,
                                      jboolean decode_chained_ogg)
{
    if (decode_chained_ogg == JNI_FALSE)
    {
        return 1;
    }

    if (container != JFLAC_CONTAINER_OGG)
    {
        throw_illegal_argument_exception(env, "Chained decoding requires an Ogg FLAC source.");
        return 0;
    }

    if (!g_flac_api.stream_decoder_set_decode_chained_stream(decoder, true))
    {
        throw_decode_exception(env, "Failed to enable chained Ogg FLAC decoding before decoder initialisation.");
        return 0;
    }

    return 1;
}

static FLAC__bool read_metadata_chain_for_container(FLAC__Metadata_Chain *chain, const char *filename,
                                                   jint container)
{
    return container == JFLAC_CONTAINER_OGG ? g_flac_api.metadata_chain_read_ogg(chain, filename)
                                           : g_flac_api.metadata_chain_read(chain, filename);
}

static FLAC__StreamDecoderInitStatus init_file_decoder_for_container(
    FLAC__StreamDecoder *decoder, const char *filename, FLAC__StreamDecoderWriteCallback write_callback,
    FLAC__StreamDecoderMetadataCallback metadata_callback, FLAC__StreamDecoderErrorCallback error_callback,
    void *client_data, jint container)
{
    return container == JFLAC_CONTAINER_OGG
               ? g_flac_api.stream_decoder_init_ogg_file(decoder, filename, write_callback, metadata_callback,
                                                         error_callback, client_data)
               : g_flac_api.stream_decoder_init_file(decoder, filename, write_callback, metadata_callback,
                                                     error_callback, client_data);
}

static FLAC__StreamDecoderInitStatus init_stream_decoder_for_container(
    FLAC__StreamDecoder *decoder, FLAC__StreamDecoderReadCallback read_callback,
    FLAC__StreamDecoderSeekCallback seek_callback, FLAC__StreamDecoderTellCallback tell_callback,
    FLAC__StreamDecoderLengthCallback length_callback, FLAC__StreamDecoderEofCallback eof_callback,
    FLAC__StreamDecoderWriteCallback write_callback, FLAC__StreamDecoderMetadataCallback metadata_callback,
    FLAC__StreamDecoderErrorCallback error_callback, void *client_data, jint container)
{
    return container == JFLAC_CONTAINER_OGG
               ? g_flac_api.stream_decoder_init_ogg_stream(decoder, read_callback, seek_callback, tell_callback,
                                                           length_callback, eof_callback, write_callback,
                                                           metadata_callback, error_callback, client_data)
               : g_flac_api.stream_decoder_init_stream(decoder, read_callback, seek_callback, tell_callback,
                                                       length_callback, eof_callback, write_callback,
                                                       metadata_callback, error_callback, client_data);
}

static FLAC__StreamEncoderInitStatus init_file_encoder_for_container(
    FLAC__StreamEncoder *encoder, const char *filename, FLAC__StreamEncoderProgressCallback progress_callback,
    void *client_data, jint container)
{
    return container == JFLAC_CONTAINER_OGG
               ? g_flac_api.stream_encoder_init_ogg_file(encoder, filename, progress_callback, client_data)
               : g_flac_api.stream_encoder_init_file(encoder, filename, progress_callback, client_data);
}

static FLAC__StreamEncoderInitStatus init_stream_encoder_for_container(
    FLAC__StreamEncoder *encoder, FLAC__StreamEncoderReadCallback read_callback,
    FLAC__StreamEncoderWriteCallback write_callback, FLAC__StreamEncoderSeekCallback seek_callback,
    FLAC__StreamEncoderTellCallback tell_callback, FLAC__StreamEncoderMetadataCallback metadata_callback,
    void *client_data, jint container)
{
    return container == JFLAC_CONTAINER_OGG
               ? g_flac_api.stream_encoder_init_ogg_stream(encoder, read_callback, write_callback, seek_callback,
                                                           tell_callback, metadata_callback, client_data)
               : g_flac_api.stream_encoder_init_stream(encoder, write_callback, seek_callback, tell_callback,
                                                       metadata_callback, client_data);
}

/*
 * Raises IllegalStateException for invalid native handle/session state. This
 * keeps stale or forged handles separate from invalid user arguments.
 */
static void throw_illegal_state_exception(JNIEnv *env, const char *message)
{
    jclass exception_class = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (exception_class != NULL)
    {
        (*env)->ThrowNew(env, exception_class, message);
    }
}

/*
 * Reads no-argument Java getters while enforcing JNI's exception discipline.
 * Call*Method may leave an exception pending even when the returned value looks
 * usable; centralising the immediate check keeps larger request snapshots
 * readable and prevents a later JNI call from running in that state.
 */
static int call_int_getter(JNIEnv *env, jobject receiver, jmethodID getter, jint *result)
{
    *result = (*env)->CallIntMethod(env, receiver, getter);
    return !(*env)->ExceptionCheck(env);
}

static int call_long_getter(JNIEnv *env, jobject receiver, jmethodID getter, jlong *result)
{
    *result = (*env)->CallLongMethod(env, receiver, getter);
    return !(*env)->ExceptionCheck(env);
}

static int call_boolean_getter(JNIEnv *env, jobject receiver, jmethodID getter, jboolean *result)
{
    *result = (*env)->CallBooleanMethod(env, receiver, getter);
    return !(*env)->ExceptionCheck(env);
}

static int call_object_getter(JNIEnv *env, jobject receiver, jmethodID getter, jobject *result)
{
    *result = (*env)->CallObjectMethod(env, receiver, getter);
    return !(*env)->ExceptionCheck(env);
}

static int find_instance_method(JNIEnv *env, jclass receiver_class, const char *name, const char *signature,
                                jmethodID *result)
{
    *result = (*env)->GetMethodID(env, receiver_class, name, signature);
    return *result != NULL && !(*env)->ExceptionCheck(env);
}

/*
 * Maps libFLAC metadata-chain statuses without relying on exported strings.
 * libFLAC exposes the enum value through the chain API, but this wrapper builds
 * its own stable diagnostic text so Java exceptions stay useful across builds.
 */
static const char *metadata_chain_status_name(FLAC__Metadata_ChainStatus status)
{
    switch (status)
    {
    case FLAC__METADATA_CHAIN_STATUS_OK:
        return "OK";
    case FLAC__METADATA_CHAIN_STATUS_ILLEGAL_INPUT:
        return "ILLEGAL_INPUT";
    case FLAC__METADATA_CHAIN_STATUS_ERROR_OPENING_FILE:
        return "ERROR_OPENING_FILE";
    case FLAC__METADATA_CHAIN_STATUS_NOT_A_FLAC_FILE:
        return "NOT_A_FLAC_FILE";
    case FLAC__METADATA_CHAIN_STATUS_NOT_WRITABLE:
        return "NOT_WRITABLE";
    case FLAC__METADATA_CHAIN_STATUS_BAD_METADATA:
        return "BAD_METADATA";
    case FLAC__METADATA_CHAIN_STATUS_READ_ERROR:
        return "READ_ERROR";
    case FLAC__METADATA_CHAIN_STATUS_SEEK_ERROR:
        return "SEEK_ERROR";
    case FLAC__METADATA_CHAIN_STATUS_WRITE_ERROR:
        return "WRITE_ERROR";
    case FLAC__METADATA_CHAIN_STATUS_RENAME_ERROR:
        return "RENAME_ERROR";
    case FLAC__METADATA_CHAIN_STATUS_UNLINK_ERROR:
        return "UNLINK_ERROR";
    case FLAC__METADATA_CHAIN_STATUS_MEMORY_ALLOCATION_ERROR:
        return "MEMORY_ALLOCATION_ERROR";
    case FLAC__METADATA_CHAIN_STATUS_INTERNAL_ERROR:
        return "INTERNAL_ERROR";
    case FLAC__METADATA_CHAIN_STATUS_INVALID_CALLBACKS:
        return "INVALID_CALLBACKS";
    case FLAC__METADATA_CHAIN_STATUS_READ_WRITE_MISMATCH:
        return "READ_WRITE_MISMATCH";
    case FLAC__METADATA_CHAIN_STATUS_WRONG_WRITE_CALL:
        return "WRONG_WRITE_CALL";
    default:
        return "UNKNOWN";
    }
}

/*
 * Converts the last metadata-chain status into a Java edit exception. The
 * action prefix names the operation that failed, while the libFLAC status names
 * the lower-level cause such as NOT_WRITABLE or BAD_METADATA.
 */
static void throw_metadata_chain_edit_exception(JNIEnv *env, const char *action, FLAC__Metadata_Chain *chain)
{
    FLAC__Metadata_ChainStatus status = chain != NULL ? g_flac_api.metadata_chain_status(chain)
                                                      : FLAC__METADATA_CHAIN_STATUS_INTERNAL_ERROR;
    char buffer[JFLAC_MESSAGE_BUFFER_SIZE];
    snprintf(buffer, sizeof(buffer), "%s: %s.", action, metadata_chain_status_name(status));
    throw_metadata_edit_exception(env, buffer);
}

/*
 * Converts a Java UTF-16 string into a heap-allocated UTF-8 C string. Callers
 * own the returned buffer and must free it.
 */
static char *jstring_to_utf8(JNIEnv *env, jstring value)
{
    if (value == NULL)
    {
        return NULL;
    }

    const jchar *chars = (*env)->GetStringChars(env, value, NULL);
    if (chars == NULL)
    {
        return NULL;
    }

    jsize length = (*env)->GetStringLength(env, value);
    if (length == 0)
    {
        char *empty = (char *)malloc(1u);
        if (empty != NULL)
        {
            empty[0] = '\0';
        }

        (*env)->ReleaseStringChars(env, value, chars);
        return empty;
    }

    int required = WideCharToMultiByte(CP_UTF8, 0, (LPCWCH)chars, length, NULL, 0, NULL, NULL);
    if (required <= 0)
    {
        (*env)->ReleaseStringChars(env, value, chars);
        return NULL;
    }

    char *buffer = (char *)malloc((size_t)required + 1u);
    if (buffer == NULL)
    {
        (*env)->ReleaseStringChars(env, value, chars);
        return NULL;
    }

    WideCharToMultiByte(CP_UTF8, 0, (LPCWCH)chars, length, buffer, required, NULL, NULL);
    buffer[required] = '\0';
    (*env)->ReleaseStringChars(env, value, chars);
    return buffer;
}

/*
 * Returns the length of a fixed-width FLAC ASCII field up to the first NUL.
 * CUESHEET catalog numbers and ISRC values are stored this way by libFLAC.
 */
static size_t fixed_ascii_length(const char *value, size_t capacity)
{
    size_t length = 0u;
    while (length < capacity && value[length] != '\0')
    {
        length += 1u;
    }

    return length;
}

/*
 * Copies a JVM UTF-8 string into a fixed-width FLAC ASCII field and leaves the
 * unused suffix NUL-padded. JVM validation already checks printable ASCII and
 * length, but native code guards the field capacity again before copying.
 */
static int copy_fixed_ascii_field(char *destination, size_t destination_size, const char *source)
{
    size_t source_length = strlen(source);
    if (source_length >= destination_size)
    {
        return 0;
    }

    memset(destination, 0, destination_size);
    memcpy(destination, source, source_length);
    return 1;
}

/*
 * Builds a Java String from a byte slice that libFLAC declares as UTF-8. This
 * avoids depending on the platform default charset.
 */
static jobject new_utf8_string(JNIEnv *env, const char *bytes, size_t length)
{
    if (length > (size_t)INT_MAX)
    {
        throw_decode_exception(env, "UTF-8 metadata text is too large for one JVM byte array.");
        return NULL;
    }

    /*
     * Keep the charset lookup and temporary byte array inside this helper.
     * PopLocalFrame promotes only the returned String into the caller's frame,
     * so repeated metadata strings cannot exhaust the JNI local-reference table.
     */
    if ((*env)->PushLocalFrame(env, 6) < 0)
    {
        return NULL;
    }

    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    if (string_class == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jmethodID ctor = (*env)->GetMethodID(env, string_class, "<init>", "([BLjava/nio/charset/Charset;)V");
    if (ctor == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jclass charsets_class = (*env)->FindClass(env, "java/nio/charset/StandardCharsets");
    if (charsets_class == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jfieldID utf8_field = (*env)->GetStaticFieldID(env, charsets_class, "UTF_8", "Ljava/nio/charset/Charset;");
    if (utf8_field == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jobject charset = (*env)->GetStaticObjectField(env, charsets_class, utf8_field);
    if (charset == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jbyteArray byte_array = (*env)->NewByteArray(env, (jsize)length);
    if (byte_array == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    if (length > 0u)
    {
        (*env)->SetByteArrayRegion(env, byte_array, 0, (jsize)length, (const jbyte *)bytes);
        if ((*env)->ExceptionCheck(env))
        {
            return (*env)->PopLocalFrame(env, NULL);
        }
    }

    jobject result = (*env)->NewObject(env, string_class, ctor, byte_array, charset);
    if ((*env)->ExceptionCheck(env))
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    return (*env)->PopLocalFrame(env, result);
}

/*
 * Converts libFLAC STREAMINFO into the public FlacStreamInfo model. All fields
 * are copied into JVM-owned values, including the fixed 16-byte MD5 digest.
 */
static jobject new_stream_info(JNIEnv *env, const FLAC__StreamMetadata_StreamInfo *stream_info)
{
    if ((*env)->PushLocalFrame(env, 4) < 0)
    {
        return NULL;
    }

    jclass clazz = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacStreamInfo");
    if (clazz == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jmethodID ctor = (*env)->GetMethodID(env, clazz, "<init>", "(IIIJIIII[B)V");
    if (ctor == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jbyteArray md5_signature = (*env)->NewByteArray(env, (jsize)JFLAC_STREAMINFO_MD5_LENGTH);
    if (md5_signature == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    (*env)->SetByteArrayRegion(env, md5_signature, 0, (jsize)JFLAC_STREAMINFO_MD5_LENGTH,
                               (const jbyte *)stream_info->md5sum);
    if ((*env)->ExceptionCheck(env))
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jobject result = (*env)->NewObject(env, clazz, ctor, (jint)stream_info->sample_rate,
                                       (jint)stream_info->channels, (jint)stream_info->bits_per_sample,
                                       (jlong)stream_info->total_samples, (jint)stream_info->min_blocksize,
                                       (jint)stream_info->max_blocksize, (jint)stream_info->min_framesize,
                                       (jint)stream_info->max_framesize, md5_signature);
    if ((*env)->ExceptionCheck(env))
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    return (*env)->PopLocalFrame(env, result);
}

/*
 * Builds the Java open result for pull sessions. The handle is already a
 * registry-owned opaque value; STREAMINFO is copied into a normal public model
 * so Java does not need a second metadata pass over sequential sources.
 */
static jobject new_pull_decoder_open_result(JNIEnv *env, jlong handle,
                                            const FLAC__StreamMetadata_StreamInfo *stream_info_data)
{
    if ((*env)->PushLocalFrame(env, 4) < 0)
    {
        return NULL;
    }

    jobject stream_info = new_stream_info(env, stream_info_data);
    if (stream_info == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jclass clazz = (*env)->FindClass(env, "org/zzvsjs/jflac/internal/NativePullDecoderOpenResult");
    if (clazz == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jmethodID ctor = (*env)->GetMethodID(env, clazz, "<init>", "(JLorg/zzvsjs/jflac/FlacStreamInfo;)V");
    if (ctor == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jobject result = (*env)->NewObject(env, clazz, ctor, handle, stream_info);
    if ((*env)->ExceptionCheck(env))
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    return (*env)->PopLocalFrame(env, result);
}

/*
 * Converts an APPLICATION metadata block into the public JVM model. The first
 * four bytes are the application ID; the remainder is copied as opaque data.
 */
static jobject new_application_block(JNIEnv *env, const FLAC__StreamMetadata *block)
{
    const FLAC__StreamMetadata_Application *application = &block->data.application;
    uint32_t data_length =
        block->length >= JFLAC_APPLICATION_ID_LENGTH ? block->length - JFLAC_APPLICATION_ID_LENGTH : 0u;
    if ((*env)->PushLocalFrame(env, 5) < 0)
    {
        return NULL;
    }

    jclass clazz = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacApplicationBlock");
    if (clazz == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jmethodID ctor = (*env)->GetMethodID(env, clazz, "<init>", "([B[B)V");
    if (ctor == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    if (data_length > (uint32_t)INT_MAX)
    {
        throw_decode_exception(env, "APPLICATION metadata block is too large for one JVM ByteArray.");
        return (*env)->PopLocalFrame(env, NULL);
    }

    jbyteArray id = (*env)->NewByteArray(env, (jsize)JFLAC_APPLICATION_ID_LENGTH);
    if (id == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jbyteArray data = (*env)->NewByteArray(env, (jsize)data_length);
    if (data == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    (*env)->SetByteArrayRegion(env, id, 0, (jsize)JFLAC_APPLICATION_ID_LENGTH, (const jbyte *)application->id);
    if ((*env)->ExceptionCheck(env))
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    if (data_length > 0u)
    {
        (*env)->SetByteArrayRegion(env, data, 0, (jsize)data_length, (const jbyte *)application->data);
        if ((*env)->ExceptionCheck(env))
        {
            return (*env)->PopLocalFrame(env, NULL);
        }
    }

    jobject result = (*env)->NewObject(env, clazz, ctor, id, data);
    if ((*env)->ExceptionCheck(env))
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    return (*env)->PopLocalFrame(env, result);
}

/*
 * Converts a libFLAC PADDING block into the public JVM model. Padding carries
 * only its byte length, so there is no payload to copy.
 */
static jobject new_padding_block(JNIEnv *env, const FLAC__StreamMetadata *block)
{
    if ((*env)->PushLocalFrame(env, 2) < 0)
    {
        return NULL;
    }

    jclass clazz = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacPaddingBlock");
    if (clazz == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jmethodID ctor = (*env)->GetMethodID(env, clazz, "<init>", "(I)V");
    if (ctor == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jobject result = (*env)->NewObject(env, clazz, ctor, (jint)block->length);
    if ((*env)->ExceptionCheck(env))
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    return (*env)->PopLocalFrame(env, result);
}

/*
 * Converts an opaque libFLAC metadata block into the public JVM model. Unknown
 * block bytes are preserved so callers can round-trip metadata types the
 * wrapper does not understand yet.
 */
static jobject new_unknown_metadata_block(JNIEnv *env, const FLAC__StreamMetadata *block)
{
    if (block->length > (uint32_t)INT_MAX)
    {
        throw_decode_exception(env, "Unknown metadata block is too large for one JVM ByteArray.");
        return NULL;
    }

    if ((*env)->PushLocalFrame(env, 4) < 0)
    {
        return NULL;
    }

    jclass clazz = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacUnknownMetadataBlock");
    if (clazz == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jmethodID ctor = (*env)->GetMethodID(env, clazz, "<init>", "(I[B)V");
    if (ctor == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jbyteArray data = (*env)->NewByteArray(env, (jsize)block->length);
    if (data == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    if (block->length > 0u)
    {
        (*env)->SetByteArrayRegion(env, data, 0, (jsize)block->length, (const jbyte *)block->data.unknown.data);
        if ((*env)->ExceptionCheck(env))
        {
            return (*env)->PopLocalFrame(env, NULL);
        }
    }

    jobject result = (*env)->NewObject(env, clazz, ctor, (jint)block->type, data);
    if ((*env)->ExceptionCheck(env))
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    return (*env)->PopLocalFrame(env, result);
}

/*
 * Converts a libFLAC SEEKTABLE into JVM seek points. The FLAC placeholder
 * sample number is represented as -1 because Java long has no unsigned value.
 */
static jobject new_seek_table(JNIEnv *env, const FLAC__StreamMetadata_SeekTable *seek_table)
{
    if ((*env)->PushLocalFrame(env, 8) < 0)
    {
        return NULL;
    }

    jclass point_class = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacSeekPoint");
    if (point_class == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jmethodID point_ctor = (*env)->GetMethodID(env, point_class, "<init>", "(JJI)V");
    if (point_ctor == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jclass list_class = (*env)->FindClass(env, "java/util/ArrayList");
    if (list_class == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jmethodID list_ctor = (*env)->GetMethodID(env, list_class, "<init>", "(I)V");
    if (list_ctor == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jmethodID list_add = (*env)->GetMethodID(env, list_class, "add", "(Ljava/lang/Object;)Z");
    if (list_add == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jclass table_class = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacSeekTable");
    if (table_class == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jmethodID table_ctor = (*env)->GetMethodID(env, table_class, "<init>", "(Ljava/util/List;)V");
    if (table_ctor == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jobject points = (*env)->NewObject(env, list_class, list_ctor, (jint)seek_table->num_points);
    jboolean points_construction_threw = (*env)->ExceptionCheck(env);
    if (points == NULL || points_construction_threw)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    for (uint32_t i = 0; i < seek_table->num_points; ++i)
    {
        const FLAC__StreamMetadata_SeekPoint *native_point = &seek_table->points[i];
        jobject point = (*env)->NewObject(env, point_class, point_ctor,
                                          (jlong)(native_point->sample_number == JFLAC_SEEKPOINT_PLACEHOLDER
                                                      ? JFLAC_SEEKPOINT_PLACEHOLDER_JAVA_VALUE
                                                      : native_point->sample_number),
                                          (jlong)native_point->stream_offset, (jint)native_point->frame_samples);
        jboolean point_construction_threw = (*env)->ExceptionCheck(env);
        if (point == NULL || point_construction_threw)
        {
            return (*env)->PopLocalFrame(env, NULL);
        }

        jboolean added = (*env)->CallBooleanMethod(env, points, list_add, point);
        jboolean add_threw = (*env)->ExceptionCheck(env);
        (*env)->DeleteLocalRef(env, point);
        if (add_threw)
        {
            return (*env)->PopLocalFrame(env, NULL);
        }

        if (added == JNI_FALSE)
        {
            throw_decode_exception(env, "Failed to append a SEEKTABLE point to the JVM result.");
            return (*env)->PopLocalFrame(env, NULL);
        }
    }

    jobject result = (*env)->NewObject(env, table_class, table_ctor, points);
    if ((*env)->ExceptionCheck(env))
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    return (*env)->PopLocalFrame(env, result);
}

/*
 * Converts one libFLAC CUESHEET index into the public FlacCueSheetIndex model.
 * The offset is a sample offset relative to the owning CUESHEET track.
 */
static jobject new_cue_sheet_index(JNIEnv *env, const FLAC__StreamMetadata_CueSheet_Index *index)
{
    if ((*env)->PushLocalFrame(env, 3) < 0)
    {
        return NULL;
    }

    jclass index_class = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacCueSheetIndex");
    if (index_class == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jmethodID index_ctor = (*env)->GetMethodID(env, index_class, "<init>", "(JI)V");
    if (index_ctor == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jobject result = (*env)->NewObject(env, index_class, index_ctor, (jlong)index->offset, (jint)index->number);
    if ((*env)->ExceptionCheck(env))
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    return (*env)->PopLocalFrame(env, result);
}

/*
 * Converts one libFLAC CUESHEET track and its indices into the public model.
 * Fixed-width ASCII fields are trimmed at the first NUL before Java sees them.
 */
static jobject new_cue_sheet_track(JNIEnv *env, const FLAC__StreamMetadata_CueSheet_Track *track)
{
    if ((*env)->PushLocalFrame(env, 8) < 0)
    {
        return NULL;
    }

    jclass list_class = (*env)->FindClass(env, "java/util/ArrayList");
    if (list_class == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jmethodID list_ctor = (*env)->GetMethodID(env, list_class, "<init>", "(I)V");
    if (list_ctor == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jmethodID list_add = (*env)->GetMethodID(env, list_class, "add", "(Ljava/lang/Object;)Z");
    if (list_add == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jclass track_class = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacCueSheetTrack");
    if (track_class == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jmethodID track_ctor = (*env)->GetMethodID(env, track_class, "<init>", "(JILjava/lang/String;IZLjava/util/List;)V");
    if (track_ctor == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jobject indices = (*env)->NewObject(env, list_class, list_ctor, (jint)track->num_indices);
    jboolean indices_construction_threw = (*env)->ExceptionCheck(env);
    if (indices == NULL || indices_construction_threw)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    for (uint32_t i = 0; i < track->num_indices; ++i)
    {
        jobject index = new_cue_sheet_index(env, &track->indices[i]);
        if (index == NULL)
        {
            return (*env)->PopLocalFrame(env, NULL);
        }

        jboolean added = (*env)->CallBooleanMethod(env, indices, list_add, index);
        jboolean add_threw = (*env)->ExceptionCheck(env);
        (*env)->DeleteLocalRef(env, index);
        if (add_threw)
        {
            return (*env)->PopLocalFrame(env, NULL);
        }

        if (added == JNI_FALSE)
        {
            throw_decode_exception(env, "Failed to append a CUESHEET index to the JVM result.");
            return (*env)->PopLocalFrame(env, NULL);
        }
    }

    jobject isrc = new_utf8_string(env, track->isrc, fixed_ascii_length(track->isrc, sizeof(track->isrc) - 1u));
    if (isrc == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jobject result = (*env)->NewObject(env, track_class, track_ctor, (jlong)track->offset, (jint)track->number, isrc,
                                       (jint)track->type, track->pre_emphasis ? JNI_TRUE : JNI_FALSE, indices);
    if ((*env)->ExceptionCheck(env))
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    return (*env)->PopLocalFrame(env, result);
}

/*
 * Converts one libFLAC CUESHEET block into the public FlacCueSheet model. This
 * keeps the track list order exactly as stored in the FLAC metadata block.
 */
static jobject new_cue_sheet(JNIEnv *env, const FLAC__StreamMetadata_CueSheet *cue_sheet)
{
    if ((*env)->PushLocalFrame(env, 8) < 0)
    {
        return NULL;
    }

    jclass list_class = (*env)->FindClass(env, "java/util/ArrayList");
    if (list_class == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jmethodID list_ctor = (*env)->GetMethodID(env, list_class, "<init>", "(I)V");
    if (list_ctor == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jmethodID list_add = (*env)->GetMethodID(env, list_class, "add", "(Ljava/lang/Object;)Z");
    if (list_add == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jclass cue_sheet_class = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacCueSheet");
    if (cue_sheet_class == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jmethodID cue_sheet_ctor =
        (*env)->GetMethodID(env, cue_sheet_class, "<init>", "(Ljava/lang/String;JZLjava/util/List;)V");
    if (cue_sheet_ctor == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jobject tracks = (*env)->NewObject(env, list_class, list_ctor, (jint)cue_sheet->num_tracks);
    jboolean tracks_construction_threw = (*env)->ExceptionCheck(env);
    if (tracks == NULL || tracks_construction_threw)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    for (uint32_t i = 0; i < cue_sheet->num_tracks; ++i)
    {
        jobject track = new_cue_sheet_track(env, &cue_sheet->tracks[i]);
        if (track == NULL)
        {
            return (*env)->PopLocalFrame(env, NULL);
        }

        jboolean added = (*env)->CallBooleanMethod(env, tracks, list_add, track);
        jboolean add_threw = (*env)->ExceptionCheck(env);
        (*env)->DeleteLocalRef(env, track);
        if (add_threw)
        {
            return (*env)->PopLocalFrame(env, NULL);
        }

        if (added == JNI_FALSE)
        {
            throw_decode_exception(env, "Failed to append a CUESHEET track to the JVM result.");
            return (*env)->PopLocalFrame(env, NULL);
        }
    }

    jobject media_catalog_number = new_utf8_string(
        env, cue_sheet->media_catalog_number,
        fixed_ascii_length(cue_sheet->media_catalog_number, sizeof(cue_sheet->media_catalog_number) - 1u));
    if (media_catalog_number == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jobject result = (*env)->NewObject(env, cue_sheet_class, cue_sheet_ctor, media_catalog_number,
                                       (jlong)cue_sheet->lead_in,
                                       cue_sheet->is_cd ? JNI_TRUE : JNI_FALSE, tracks);
    if ((*env)->ExceptionCheck(env))
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    return (*env)->PopLocalFrame(env, result);
}

/*
 * Converts a libFLAC PICTURE block into the public FlacPicture model. MIME
 * type, description, dimensions, colour depth, and image bytes are all copied
 * out of libFLAC-owned memory.
 */
static jobject new_picture(JNIEnv *env, const FLAC__StreamMetadata_Picture *picture)
{
    if (picture->data_length > (uint32_t)INT_MAX)
    {
        throw_decode_exception(env, "PICTURE metadata data is too large for one JVM ByteArray.");
        return NULL;
    }

    if ((*env)->PushLocalFrame(env, 6) < 0)
    {
        return NULL;
    }

    jclass clazz = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacPicture");
    if (clazz == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jmethodID ctor = (*env)->GetMethodID(env, clazz, "<init>", "(ILjava/lang/String;Ljava/lang/String;IIII[B)V");
    if (ctor == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jobject mime_type = new_utf8_string(env, picture->mime_type, strlen(picture->mime_type));
    if (mime_type == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jobject description =
        new_utf8_string(env, (const char *)picture->description, strlen((const char *)picture->description));
    if (description == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    jbyteArray data = (*env)->NewByteArray(env, (jsize)picture->data_length);
    if (data == NULL)
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    if (picture->data_length > 0u)
    {
        (*env)->SetByteArrayRegion(env, data, 0, (jsize)picture->data_length, (const jbyte *)picture->data);
        if ((*env)->ExceptionCheck(env))
        {
            return (*env)->PopLocalFrame(env, NULL);
        }
    }

    jobject result = (*env)->NewObject(env, clazz, ctor, (jint)picture->type, mime_type, description,
                                       (jint)picture->width, (jint)picture->height, (jint)picture->depth,
                                       (jint)picture->colors, data);
    if ((*env)->ExceptionCheck(env))
    {
        return (*env)->PopLocalFrame(env, NULL);
    }

    return (*env)->PopLocalFrame(env, result);
}

/*
 * Forward declaration for shared picture block construction. The validation
 * entry point appears before encoder construction but both need the same
 * Java-to-libFLAC conversion path.
 */
static FLAC__bool build_picture_block(JNIEnv *env, jobject picture_object, FLAC__StreamMetadata **result);

/*
 * Reads file metadata through libFLAC's metadata chain and materialises all
 * supported read-only block types into a NativeMetadataPayload.
 *
 * The conversion is deliberately two-pass for each repeated block type. The
 * first pass counts blocks so JVM arrays can be allocated with exact sizes; the
 * later passes fill those arrays and build the ordered type/index side table
 * that lets Kotlin reconstruct the original non-STREAMINFO block order.
 */
JNIEXPORT jobject JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_readMetadata(JNIEnv *env, jclass clazz,
                                                                                     jstring path, jint container)
{
    (void)clazz;
    if (!flac_api_ready(env))
    {
        return NULL;
    }

    if (!validate_container(env, container))
    {
        return NULL;
    }

    char *utf8_path = jstring_to_utf8(env, path);
    if (utf8_path == NULL)
    {
        throw_decode_exception(env, "Failed to encode file path to UTF-8.");
        return NULL;
    }

    FLAC__Metadata_Chain *chain = g_flac_api.metadata_chain_new();
    FLAC__Metadata_Iterator *iterator = NULL;
    const FLAC__StreamMetadata *streaminfo_block = NULL;
    const FLAC__StreamMetadata *vorbis_block = NULL;
    jint vorbis_comment_count = 0;
    jint picture_count = 0;
    jint application_count = 0;
    jint seek_table_count = 0;
    jint cue_sheet_count = 0;
    jint padding_count = 0;
    jint unknown_count = 0;
    jint ordered_block_count = 0;

    if (chain == NULL)
    {
        free(utf8_path);
        throw_decode_exception(env, "Failed to allocate FLAC metadata chain.");
        return NULL;
    }

    if (!read_metadata_chain_for_container(chain, utf8_path, container))
    {
        g_flac_api.metadata_chain_delete(chain);
        free(utf8_path);
        throw_decode_exception(env, "Failed to read FLAC metadata.");
        return NULL;
    }

    free(utf8_path);

    iterator = g_flac_api.metadata_iterator_new();
    if (iterator == NULL)
    {
        g_flac_api.metadata_chain_delete(chain);
        throw_decode_exception(env, "Failed to allocate FLAC metadata iterator.");
        return NULL;
    }

    /*
     * First metadata pass: count each block type and remember singleton blocks
     * that have special handling. The counts are needed because JNI arrays have
     * fixed lengths; building growable Java lists here would add more JNI calls
     * and more local references than the native chain iterator needs.
     */
    g_flac_api.metadata_iterator_init(iterator, chain);
    do
    {
        const FLAC__StreamMetadata *block = g_flac_api.metadata_iterator_get_block(iterator);
        if (block == NULL)
        {
            continue;
        }

        switch (block->type)
        {
        case FLAC__METADATA_TYPE_STREAMINFO:
            streaminfo_block = block;
            break;
        case FLAC__METADATA_TYPE_VORBIS_COMMENT:
            ordered_block_count += 1;
            vorbis_comment_count += 1;
            vorbis_block = block;
            break;
        case FLAC__METADATA_TYPE_PICTURE:
            ordered_block_count += 1;
            picture_count += 1;
            break;
        case FLAC__METADATA_TYPE_APPLICATION:
            ordered_block_count += 1;
            application_count += 1;
            break;
        case FLAC__METADATA_TYPE_SEEKTABLE:
            ordered_block_count += 1;
            seek_table_count += 1;
            break;
        case FLAC__METADATA_TYPE_CUESHEET:
            ordered_block_count += 1;
            cue_sheet_count += 1;
            break;
        case FLAC__METADATA_TYPE_PADDING:
            ordered_block_count += 1;
            padding_count += 1;
            break;
        default:
            ordered_block_count += 1;
            unknown_count += 1;
            break;
        }
    } while (g_flac_api.metadata_iterator_next(iterator));

    if (streaminfo_block == NULL)
    {
        g_flac_api.metadata_iterator_delete(iterator);
        g_flac_api.metadata_chain_delete(chain);
        throw_decode_exception(env, "FLAC file does not contain STREAMINFO.");
        return NULL;
    }

    if (vorbis_comment_count > 1)
    {
        g_flac_api.metadata_iterator_delete(iterator);
        g_flac_api.metadata_chain_delete(chain);
        throw_decode_exception(env,
                               "FLAC file contains multiple VORBIS_COMMENT metadata blocks, which this API cannot "
                               "represent exactly.");
        return NULL;
    }

    jobject stream_info = new_stream_info(env, &streaminfo_block->data.stream_info);
    if (stream_info == NULL)
    {
        g_flac_api.metadata_iterator_delete(iterator);
        g_flac_api.metadata_chain_delete(chain);
        return NULL;
    }

    jobject vendor = NULL;
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    if (string_class == NULL)
    {
        g_flac_api.metadata_iterator_delete(iterator);
        g_flac_api.metadata_chain_delete(chain);
        return NULL;
    }

    jobjectArray comment_entries = NULL;
    if (vorbis_block != NULL)
    {
        /*
         * Vorbis comments are a logical singleton block in this API. The first
         * counting pass rejects duplicate VORBIS_COMMENT blocks because the
         * public payload cannot represent multiple distinct vendors/comment
         * lists exactly.
         */
        const FLAC__StreamMetadata_VorbisComment *vorbis_comment = &vorbis_block->data.vorbis_comment;
        vendor = new_utf8_string(env, (const char *)vorbis_comment->vendor_string.entry,
                                 vorbis_comment->vendor_string.length);
        jboolean vendor_conversion_threw = (*env)->ExceptionCheck(env);
        if (vendor == NULL || vendor_conversion_threw)
        {
            (*env)->DeleteLocalRef(env, string_class);
            g_flac_api.metadata_iterator_delete(iterator);
            g_flac_api.metadata_chain_delete(chain);
            return NULL;
        }

        comment_entries = (*env)->NewObjectArray(env, (jsize)vorbis_comment->num_comments, string_class, NULL);
        jboolean comments_allocation_threw = (*env)->ExceptionCheck(env);
        if (comment_entries == NULL || comments_allocation_threw)
        {
            (*env)->DeleteLocalRef(env, string_class);
            g_flac_api.metadata_iterator_delete(iterator);
            g_flac_api.metadata_chain_delete(chain);
            return NULL;
        }

        for (uint32_t i = 0; i < vorbis_comment->num_comments; ++i)
        {
            jobject entry = new_utf8_string(env, (const char *)vorbis_comment->comments[i].entry,
                                            vorbis_comment->comments[i].length);
            jboolean entry_conversion_threw = (*env)->ExceptionCheck(env);
            if (entry == NULL || entry_conversion_threw)
            {
                (*env)->DeleteLocalRef(env, string_class);
                g_flac_api.metadata_iterator_delete(iterator);
                g_flac_api.metadata_chain_delete(chain);
                return NULL;
            }

            (*env)->SetObjectArrayElement(env, comment_entries, (jsize)i, entry);
            jboolean set_threw = (*env)->ExceptionCheck(env);
            (*env)->DeleteLocalRef(env, entry);
            if (set_threw)
            {
                (*env)->DeleteLocalRef(env, string_class);
                g_flac_api.metadata_iterator_delete(iterator);
                g_flac_api.metadata_chain_delete(chain);
                return NULL;
            }
        }
    }
    else
    {
        comment_entries = (*env)->NewObjectArray(env, 0, string_class, NULL);
        jboolean comments_allocation_threw = (*env)->ExceptionCheck(env);
        if (comment_entries == NULL || comments_allocation_threw)
        {
            (*env)->DeleteLocalRef(env, string_class);
            g_flac_api.metadata_iterator_delete(iterator);
            g_flac_api.metadata_chain_delete(chain);
            return NULL;
        }
    }

    (*env)->DeleteLocalRef(env, string_class);

    /*
     * The following repeated passes all use the same pattern:
     * 1. allocate a correctly-sized Java array for one metadata type,
     * 2. rewind the libFLAC iterator to the start of the chain,
     * 3. copy only blocks of that type into the array in file order.
     */
    jclass picture_class = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacPicture");
    if (picture_class == NULL)
    {
        g_flac_api.metadata_iterator_delete(iterator);
        g_flac_api.metadata_chain_delete(chain);
        return NULL;
    }

    jobjectArray pictures = (*env)->NewObjectArray(env, picture_count, picture_class, NULL);
    jboolean pictures_allocation_threw = (*env)->ExceptionCheck(env);
    (*env)->DeleteLocalRef(env, picture_class);
    if (pictures == NULL || pictures_allocation_threw)
    {
        g_flac_api.metadata_iterator_delete(iterator);
        g_flac_api.metadata_chain_delete(chain);
        return NULL;
    }

    jint picture_index = 0;
    /* Fill the PICTURE array; each picture payload is copied into a JVM byte array. */
    g_flac_api.metadata_iterator_init(iterator, chain);
    do
    {
        const FLAC__StreamMetadata *block = g_flac_api.metadata_iterator_get_block(iterator);
        if (block != NULL && block->type == FLAC__METADATA_TYPE_PICTURE)
        {
            jobject picture = new_picture(env, &block->data.picture);
            if (picture == NULL)
            {
                g_flac_api.metadata_iterator_delete(iterator);
                g_flac_api.metadata_chain_delete(chain);
                return NULL;
            }

            (*env)->SetObjectArrayElement(env, pictures, picture_index++, picture);
            jboolean set_threw = (*env)->ExceptionCheck(env);
            (*env)->DeleteLocalRef(env, picture);
            if (set_threw)
            {
                g_flac_api.metadata_iterator_delete(iterator);
                g_flac_api.metadata_chain_delete(chain);
                return NULL;
            }
        }
    } while (g_flac_api.metadata_iterator_next(iterator));

    jclass application_class = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacApplicationBlock");
    if (application_class == NULL)
    {
        g_flac_api.metadata_iterator_delete(iterator);
        g_flac_api.metadata_chain_delete(chain);
        return NULL;
    }

    jobjectArray application_blocks = (*env)->NewObjectArray(env, application_count, application_class, NULL);
    jboolean applications_allocation_threw = (*env)->ExceptionCheck(env);
    (*env)->DeleteLocalRef(env, application_class);
    if (application_blocks == NULL || applications_allocation_threw)
    {
        g_flac_api.metadata_iterator_delete(iterator);
        g_flac_api.metadata_chain_delete(chain);
        return NULL;
    }

    jint application_index = 0;
    /* Fill the APPLICATION array; the four-byte ID and opaque payload are split for Java. */
    g_flac_api.metadata_iterator_init(iterator, chain);
    do
    {
        const FLAC__StreamMetadata *block = g_flac_api.metadata_iterator_get_block(iterator);
        if (block != NULL && block->type == FLAC__METADATA_TYPE_APPLICATION)
        {
            jobject application = new_application_block(env, block);
            if (application == NULL)
            {
                g_flac_api.metadata_iterator_delete(iterator);
                g_flac_api.metadata_chain_delete(chain);
                return NULL;
            }

            (*env)->SetObjectArrayElement(env, application_blocks, application_index++, application);
            jboolean set_threw = (*env)->ExceptionCheck(env);
            (*env)->DeleteLocalRef(env, application);
            if (set_threw)
            {
                g_flac_api.metadata_iterator_delete(iterator);
                g_flac_api.metadata_chain_delete(chain);
                return NULL;
            }
        }
    } while (g_flac_api.metadata_iterator_next(iterator));

    jclass seek_table_class = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacSeekTable");
    if (seek_table_class == NULL)
    {
        g_flac_api.metadata_iterator_delete(iterator);
        g_flac_api.metadata_chain_delete(chain);
        return NULL;
    }

    jobjectArray seek_tables = (*env)->NewObjectArray(env, seek_table_count, seek_table_class, NULL);
    jboolean seek_tables_allocation_threw = (*env)->ExceptionCheck(env);
    (*env)->DeleteLocalRef(env, seek_table_class);
    if (seek_tables == NULL || seek_tables_allocation_threw)
    {
        g_flac_api.metadata_iterator_delete(iterator);
        g_flac_api.metadata_chain_delete(chain);
        return NULL;
    }

    jint seek_table_index = 0;
    /* Fill the SEEKTABLE array; placeholder seek points are converted to Java's -1 sentinel. */
    g_flac_api.metadata_iterator_init(iterator, chain);
    do
    {
        const FLAC__StreamMetadata *block = g_flac_api.metadata_iterator_get_block(iterator);
        if (block != NULL && block->type == FLAC__METADATA_TYPE_SEEKTABLE)
        {
            jobject seek_table = new_seek_table(env, &block->data.seek_table);
            if (seek_table == NULL)
            {
                g_flac_api.metadata_iterator_delete(iterator);
                g_flac_api.metadata_chain_delete(chain);
                return NULL;
            }

            (*env)->SetObjectArrayElement(env, seek_tables, seek_table_index++, seek_table);
            jboolean set_threw = (*env)->ExceptionCheck(env);
            (*env)->DeleteLocalRef(env, seek_table);
            if (set_threw)
            {
                g_flac_api.metadata_iterator_delete(iterator);
                g_flac_api.metadata_chain_delete(chain);
                return NULL;
            }
        }
    } while (g_flac_api.metadata_iterator_next(iterator));

    jclass cue_sheet_class = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacCueSheet");
    if (cue_sheet_class == NULL)
    {
        g_flac_api.metadata_iterator_delete(iterator);
        g_flac_api.metadata_chain_delete(chain);
        return NULL;
    }

    jobjectArray cue_sheets = (*env)->NewObjectArray(env, cue_sheet_count, cue_sheet_class, NULL);
    jboolean cue_sheets_allocation_threw = (*env)->ExceptionCheck(env);
    (*env)->DeleteLocalRef(env, cue_sheet_class);
    if (cue_sheets == NULL || cue_sheets_allocation_threw)
    {
        g_flac_api.metadata_iterator_delete(iterator);
        g_flac_api.metadata_chain_delete(chain);
        return NULL;
    }

    jint cue_sheet_index = 0;
    /* Fill the CUESHEET array; nested track and index lists keep their original order. */
    g_flac_api.metadata_iterator_init(iterator, chain);
    do
    {
        const FLAC__StreamMetadata *block = g_flac_api.metadata_iterator_get_block(iterator);
        if (block != NULL && block->type == FLAC__METADATA_TYPE_CUESHEET)
        {
            jobject cue_sheet = new_cue_sheet(env, &block->data.cue_sheet);
            if (cue_sheet == NULL)
            {
                g_flac_api.metadata_iterator_delete(iterator);
                g_flac_api.metadata_chain_delete(chain);
                return NULL;
            }

            (*env)->SetObjectArrayElement(env, cue_sheets, cue_sheet_index++, cue_sheet);
            jboolean set_threw = (*env)->ExceptionCheck(env);
            (*env)->DeleteLocalRef(env, cue_sheet);
            if (set_threw)
            {
                g_flac_api.metadata_iterator_delete(iterator);
                g_flac_api.metadata_chain_delete(chain);
                return NULL;
            }
        }
    } while (g_flac_api.metadata_iterator_next(iterator));

    jclass padding_class = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacPaddingBlock");
    if (padding_class == NULL)
    {
        g_flac_api.metadata_iterator_delete(iterator);
        g_flac_api.metadata_chain_delete(chain);
        return NULL;
    }

    jobjectArray padding_blocks = (*env)->NewObjectArray(env, padding_count, padding_class, NULL);
    jboolean padding_allocation_threw = (*env)->ExceptionCheck(env);
    (*env)->DeleteLocalRef(env, padding_class);
    if (padding_blocks == NULL || padding_allocation_threw)
    {
        g_flac_api.metadata_iterator_delete(iterator);
        g_flac_api.metadata_chain_delete(chain);
        return NULL;
    }

    jint padding_index = 0;
    /* Fill the PADDING array; only block lengths are exposed because padding has no payload. */
    g_flac_api.metadata_iterator_init(iterator, chain);
    do
    {
        const FLAC__StreamMetadata *block = g_flac_api.metadata_iterator_get_block(iterator);
        if (block != NULL && block->type == FLAC__METADATA_TYPE_PADDING)
        {
            jobject padding = new_padding_block(env, block);
            if (padding == NULL)
            {
                g_flac_api.metadata_iterator_delete(iterator);
                g_flac_api.metadata_chain_delete(chain);
                return NULL;
            }

            (*env)->SetObjectArrayElement(env, padding_blocks, padding_index++, padding);
            jboolean set_threw = (*env)->ExceptionCheck(env);
            (*env)->DeleteLocalRef(env, padding);
            if (set_threw)
            {
                g_flac_api.metadata_iterator_delete(iterator);
                g_flac_api.metadata_chain_delete(chain);
                return NULL;
            }
        }
    } while (g_flac_api.metadata_iterator_next(iterator));

    jclass unknown_class = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacUnknownMetadataBlock");
    if (unknown_class == NULL)
    {
        g_flac_api.metadata_iterator_delete(iterator);
        g_flac_api.metadata_chain_delete(chain);
        return NULL;
    }

    jobjectArray unknown_blocks = (*env)->NewObjectArray(env, unknown_count, unknown_class, NULL);
    jboolean unknown_allocation_threw = (*env)->ExceptionCheck(env);
    (*env)->DeleteLocalRef(env, unknown_class);
    if (unknown_blocks == NULL || unknown_allocation_threw)
    {
        g_flac_api.metadata_iterator_delete(iterator);
        g_flac_api.metadata_chain_delete(chain);
        return NULL;
    }

    jint unknown_index = 0;
    /* Fill the unknown array with all currently undefined FLAC metadata types. */
    g_flac_api.metadata_iterator_init(iterator, chain);
    do
    {
        const FLAC__StreamMetadata *block = g_flac_api.metadata_iterator_get_block(iterator);
        if (block != NULL && block->type >= FLAC__METADATA_TYPE_UNDEFINED)
        {
            jobject unknown = new_unknown_metadata_block(env, block);
            if (unknown == NULL)
            {
                g_flac_api.metadata_iterator_delete(iterator);
                g_flac_api.metadata_chain_delete(chain);
                return NULL;
            }

            (*env)->SetObjectArrayElement(env, unknown_blocks, unknown_index++, unknown);
            jboolean set_threw = (*env)->ExceptionCheck(env);
            (*env)->DeleteLocalRef(env, unknown);
            if (set_threw)
            {
                g_flac_api.metadata_iterator_delete(iterator);
                g_flac_api.metadata_chain_delete(chain);
                return NULL;
            }
        }
    } while (g_flac_api.metadata_iterator_next(iterator));

    jintArray metadata_block_types = (*env)->NewIntArray(env, ordered_block_count);
    if (metadata_block_types == NULL)
    {
        g_flac_api.metadata_iterator_delete(iterator);
        g_flac_api.metadata_chain_delete(chain);
        return NULL;
    }

    jintArray metadata_block_indices = (*env)->NewIntArray(env, ordered_block_count);
    if (metadata_block_indices == NULL)
    {
        g_flac_api.metadata_iterator_delete(iterator);
        g_flac_api.metadata_chain_delete(chain);
        return NULL;
    }

    jint ordered_index = 0;
    picture_index = 0;
    application_index = 0;
    seek_table_index = 0;
    cue_sheet_index = 0;
    padding_index = 0;
    unknown_index = 0;
    /*
     * Final pass: build a compact order table. For each non-STREAMINFO block,
     * metadata_block_types stores its FLAC type code and metadata_block_indices
     * stores the index into the matching typed array above. Kotlin uses the two
     * arrays together to recreate exact non-STREAMINFO order.
     */
    g_flac_api.metadata_iterator_init(iterator, chain);
    do
    {
        const FLAC__StreamMetadata *block = g_flac_api.metadata_iterator_get_block(iterator);
        if (block == NULL || block->type == FLAC__METADATA_TYPE_STREAMINFO)
        {
            continue;
        }

        jint block_type = (jint)block->type;
        jint block_index = 0;
        switch (block->type)
        {
        case FLAC__METADATA_TYPE_VORBIS_COMMENT:
            block_index = 0;
            break;
        case FLAC__METADATA_TYPE_PICTURE:
            block_index = picture_index++;
            break;
        case FLAC__METADATA_TYPE_APPLICATION:
            block_index = application_index++;
            break;
        case FLAC__METADATA_TYPE_SEEKTABLE:
            block_index = seek_table_index++;
            break;
        case FLAC__METADATA_TYPE_CUESHEET:
            block_index = cue_sheet_index++;
            break;
        case FLAC__METADATA_TYPE_PADDING:
            block_index = padding_index++;
            break;
        default:
            block_index = unknown_index++;
            break;
        }

        (*env)->SetIntArrayRegion(env, metadata_block_types, ordered_index, 1, &block_type);
        if ((*env)->ExceptionCheck(env))
        {
            g_flac_api.metadata_iterator_delete(iterator);
            g_flac_api.metadata_chain_delete(chain);
            return NULL;
        }

        (*env)->SetIntArrayRegion(env, metadata_block_indices, ordered_index, 1, &block_index);
        if ((*env)->ExceptionCheck(env))
        {
            g_flac_api.metadata_iterator_delete(iterator);
            g_flac_api.metadata_chain_delete(chain);
            return NULL;
        }

        ordered_index += 1;
    } while (g_flac_api.metadata_iterator_next(iterator));

    jclass payload_class = (*env)->FindClass(env, "org/zzvsjs/jflac/internal/NativeMetadataPayload");
    if (payload_class == NULL)
    {
        g_flac_api.metadata_iterator_delete(iterator);
        g_flac_api.metadata_chain_delete(chain);
        return NULL;
    }

    jmethodID payload_ctor =
        (*env)->GetMethodID(env, payload_class, "<init>",
                            "(Lorg/zzvsjs/jflac/FlacStreamInfo;Ljava/lang/String;[Ljava/lang/String;[Lorg/zzvsjs/jflac/"
                            "FlacPicture;[Lorg/zzvsjs/jflac/FlacApplicationBlock;[Lorg/zzvsjs/jflac/FlacSeekTable;"
                            "[Lorg/zzvsjs/jflac/FlacCueSheet;[Lorg/zzvsjs/jflac/FlacPaddingBlock;"
                            "[Lorg/zzvsjs/jflac/FlacUnknownMetadataBlock;[I[I)V");
    if (payload_ctor == NULL)
    {
        g_flac_api.metadata_iterator_delete(iterator);
        g_flac_api.metadata_chain_delete(chain);
        return NULL;
    }

    jobject payload = (*env)->NewObject(env, payload_class, payload_ctor, stream_info, vendor, comment_entries,
                                        pictures, application_blocks, seek_tables, cue_sheets, padding_blocks,
                                        unknown_blocks, metadata_block_types, metadata_block_indices);
    jboolean payload_construction_threw = (*env)->ExceptionCheck(env);
    (*env)->DeleteLocalRef(env, payload_class);
    g_flac_api.metadata_iterator_delete(iterator);
    g_flac_api.metadata_chain_delete(chain);
    if (payload_construction_threw)
    {
        return NULL;
    }

    return payload;
}

/*
 * Exposes FLAC__format_sample_rate_is_valid to Java/Kotlin callers. Negative
 * Java values are rejected before casting to libFLAC's unsigned type.
 */
JNIEXPORT jboolean JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_isSampleRateValid(JNIEnv *env, jclass clazz,
                                                                                           jint sample_rate)
{
    (void)clazz;
    if (!flac_api_ready(env) || sample_rate < 0)
    {
        return JNI_FALSE;
    }

    return g_flac_api.format_sample_rate_is_valid((uint32_t)sample_rate) ? JNI_TRUE : JNI_FALSE;
}

/*
 * Exposes FLAC__format_sample_rate_is_subset to Java/Kotlin callers. This
 * checks stricter streamable-subset compatibility, not basic FLAC legality.
 */
JNIEXPORT jboolean JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_isSampleRateSubset(JNIEnv *env, jclass clazz,
                                                                                            jint sample_rate)
{
    (void)clazz;
    if (!flac_api_ready(env) || sample_rate < 0)
    {
        return JNI_FALSE;
    }

    return g_flac_api.format_sample_rate_is_subset((uint32_t)sample_rate) ? JNI_TRUE : JNI_FALSE;
}

/*
 * Exposes FLAC__format_blocksize_is_subset with the required sample rate. The
 * sample rate matters because the subset block-size rules depend on it.
 */
JNIEXPORT jboolean JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_isBlockSizeSubset(JNIEnv *env, jclass clazz,
                                                                                           jint block_size,
                                                                                           jint sample_rate)
{
    (void)clazz;
    if (!flac_api_ready(env) || block_size < 0 || sample_rate < 0)
    {
        return JNI_FALSE;
    }

    return g_flac_api.format_blocksize_is_subset((uint32_t)block_size, (uint32_t)sample_rate) ? JNI_TRUE : JNI_FALSE;
}

/*
 * Checks a Vorbis comment field name with libFLAC's legality helper. The name
 * is UTF-8 encoded first because the native helper expects byte strings.
 */
JNIEXPORT jboolean JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_isVorbisCommentNameLegal(JNIEnv *env,
                                                                                                  jclass clazz,
                                                                                                  jstring name)
{
    (void)clazz;
    if (!flac_api_ready(env) || name == NULL)
    {
        return JNI_FALSE;
    }

    char *utf8_name = jstring_to_utf8(env, name);
    if (utf8_name == NULL)
    {
        return JNI_FALSE;
    }

    FLAC__bool legal = g_flac_api.format_vorbiscomment_entry_name_is_legal(utf8_name);
    free(utf8_name);
    return legal ? JNI_TRUE : JNI_FALSE;
}

/*
 * Checks a Vorbis comment field value with libFLAC's legality helper. The
 * length guard prevents a size_t value from narrowing into libFLAC's uint32_t
 * length argument.
 */
JNIEXPORT jboolean JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_isVorbisCommentValueLegal(JNIEnv *env,
                                                                                                   jclass clazz,
                                                                                                   jstring value)
{
    (void)clazz;
    if (!flac_api_ready(env) || value == NULL)
    {
        return JNI_FALSE;
    }

    char *utf8_value = jstring_to_utf8(env, value);
    if (utf8_value == NULL)
    {
        return JNI_FALSE;
    }

    size_t length = strlen(utf8_value);
    FLAC__bool legal =
        length <= UINT_MAX
            ? g_flac_api.format_vorbiscomment_entry_value_is_legal((const FLAC__byte *)utf8_value, (uint32_t)length)
            : false;
    free(utf8_value);
    return legal ? JNI_TRUE : JNI_FALSE;
}

/*
 * Checks a complete KEY=value Vorbis comment entry with libFLAC. This mirrors
 * the encoder path, where Java passes flattened comment entries to native code.
 */
JNIEXPORT jboolean JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_isVorbisCommentEntryLegal(JNIEnv *env,
                                                                                                   jclass clazz,
                                                                                                   jstring entry)
{
    (void)clazz;
    if (!flac_api_ready(env) || entry == NULL)
    {
        return JNI_FALSE;
    }

    char *utf8_entry = jstring_to_utf8(env, entry);
    if (utf8_entry == NULL)
    {
        return JNI_FALSE;
    }

    size_t length = strlen(utf8_entry);
    FLAC__bool legal =
        length <= UINT_MAX
            ? g_flac_api.format_vorbiscomment_entry_is_legal((const FLAC__byte *)utf8_entry, (uint32_t)length)
            : false;
    free(utf8_entry);
    return legal ? JNI_TRUE : JNI_FALSE;
}

/*
 * Validates a public FlacPicture by temporarily building a libFLAC metadata
 * block, then returning libFLAC's violation message when the block is illegal.
 * The temporary block is deleted before returning, so Java only receives text.
 */
JNIEXPORT jstring JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_pictureViolation(JNIEnv *env, jclass clazz,
                                                                                         jobject picture)
{
    (void)clazz;
    if (!flac_api_ready(env))
    {
        return NULL;
    }

    if (picture == NULL)
    {
        return (*env)->NewStringUTF(env, "Picture must not be null.");
    }

    FLAC__StreamMetadata *block = NULL;
    if (!build_picture_block(env, picture, &block) || block == NULL)
    {
        return (*env)->NewStringUTF(env, "Unable to build FLAC picture metadata for validation.");
    }

    const char *violation = NULL;
    FLAC__bool legal = g_flac_api.metadata_object_picture_is_legal(block, &violation);
    g_flac_api.metadata_object_delete(block);
    return legal ? NULL : (*env)->NewStringUTF(env, violation != NULL ? violation : "Illegal FLAC picture metadata.");
}

typedef struct DecodeContext
{
    /*
     * DecodeContext is passed to libFLAC as callback client_data. It is reused
     * for both one-shot decode and reusable session decode, so every entry
     * point must initialise all fields that affect callback behaviour before
     * calling into libFLAC.
     */
    JNIEnv *env;
    jobject consumer;
    jmethodID on_stream_info;
    jmethodID on_pcm_interleaved;
    jmethodID on_complete;

    /*
     * Sequential InputStream decode uses libFLAC's read callback. The stream
     * reference is local to the JNI decode call because libFLAC invokes the
     * callback synchronously before the native method returns.
     */
    jobject input_stream;
    jmethodID input_read;

    /*
     * Pull InputStream sessions outlive the JNI open call, so their stream
     * object must be promoted to a global reference just like seekable-channel
     * sessions. One-shot stream decode deliberately leaves this flag clear and
     * keeps using a local reference.
     */
    int input_stream_is_global;

    /*
     * SeekableByteChannel decode uses the same libFLAC stream API as
     * InputStream, but also supplies seek/tell/length/eof callbacks. The
     * global reference is needed for reusable decoder sessions.
     */
    jobject seekable_channel;
    jmethodID channel_read;
    jmethodID channel_position;
    jmethodID channel_seek;
    jmethodID channel_size;
    jlong channel_base_offset;

    /*
     * libFLAC reports detailed decoder errors through the error callback, but
     * the failing API call often only returns false. These fields remember the
     * last callback status so Java receives an actionable exception message.
     */
    int saw_error;
    FLAC__StreamDecoderErrorStatus last_error_status;

    /*
     * Reusable sessions read metadata when the native decoder is opened. During
     * later seek-decode calls Kotlin already emits the cached STREAMINFO, so the
     * native metadata callback must remember totals without re-calling Java.
     */
    int suppress_metadata;
    int saw_stream_info;
    FLAC__uint64 stream_total_samples;
    FLAC__StreamMetadata_StreamInfo stream_info;

    /*
     * Chained Ogg is limited to whole-stream decoding in this wrapper. The
     * first STREAMINFO supplies the common PCM shape; later links are checked
     * against it and are deliberately not re-emitted through the single-stream
     * PcmConsumer contract.
     */
    int decode_chained_ogg;
    uint32_t chained_link_count;
    int chained_link_md5_failed;
    FLAC__uint64 current_link_total_samples;
    FLAC__uint64 current_link_start_emitted_frames;

    /*
     * Range decode is frame-limited, where one frame means one sample per
     * channel. The write callback uses these fields to crop the final libFLAC
     * block before it is copied into a JVM IntArray.
     */
    int range_limited;
    FLAC__uint64 max_frames;
    FLAC__uint64 emitted_frames;
    int range_complete;

    /*
     * Pull decode state is active only while readPullDecoderInterleaved() is
     * on the native stack. libFLAC writes whole decoded blocks, but Java reads
     * may ask for fewer frames than one block contains. The pending buffer
     * keeps the undrained suffix so the next Java read continues at the exact
     * sample after the last value copied out.
     */
    int pull_mode;
    jintArray pull_output;
    jsize pull_output_capacity_samples;
    uint32_t pull_channels;
    uint32_t pull_requested_frames;
    uint32_t pull_written_frames;
    int pull_end_of_stream;
    jint *pull_pending_samples;
    uint32_t pull_pending_sample_count;
    uint32_t pull_pending_sample_offset;
} DecodeContext;

typedef struct DecodeSession
{
    /*
     * DecodeSession owns exactly one FLAC__StreamDecoder. Java never receives
     * this pointer directly; it receives an opaque handle registered below.
     * That registry prevents stale or forged non-zero handles from being
     * treated as native pointers.
     */
    FLAC__StreamDecoder *decoder;
    DecodeContext context;
    jlong handle;

    /*
     * reference_count protects a session that is being decoded while Java calls
     * releaseDecoder on another thread. released removes the handle from public
     * lookup, then the last native reference performs the actual destroy.
     */
    int reference_count;
    int released;

    /*
     * libFLAC stream decoders are stateful and not re-entrant. in_use rejects a
     * second decode on the same native handle instead of racing two callbacks
     * through the same decoder state.
     */
    int in_use;
} DecodeSession;

typedef struct DecodeSessionRegistryEntry
{
    jlong handle;
    DecodeSession *session;
    struct DecodeSessionRegistryEntry *next;
} DecodeSessionRegistryEntry;

/*
 * Reusable decoder sessions are stored in a small process-local linked list.
 * The list is protected by a statically initialised platform mutex. No registry
 * lock is held while libFLAC or a Java callback runs, so recursive locking is
 * neither required nor desirable.
 */
static JflacMutex g_decode_session_registry_lock = JFLAC_MUTEX_INITIALIZER;
static DecodeSessionRegistryEntry *g_decode_session_registry = NULL;
static jlong g_next_decode_session_handle = JFLAC_FIRST_VALID_SESSION_HANDLE;

/*
 * The session registry is process-local because JNI callers can pass any long
 * value back into native code. Looking up handles in this list gives us a
 * controlled IllegalStateException for 0, stale handles, and arbitrary forged
 * values, rather than dereferencing attacker- or bug-supplied addresses.
 */
/*
 * Enters the process-wide registry lock for reusable decoder sessions.
 */
static void lock_decode_session_registry(void)
{
    jflac_mutex_lock(&g_decode_session_registry_lock);
}

/*
 * Leaves the process-wide registry lock for reusable decoder sessions. All
 * callers must have entered through lock_decode_session_registry().
 */
static void unlock_decode_session_registry(void)
{
    jflac_mutex_unlock(&g_decode_session_registry_lock);
}

/*
 * Deletes a global seekable-channel reference held by a decode context. This
 * helper is safe on partially initialised contexts so error paths can call it
 * without duplicating null checks.
 */
static void clear_decode_seekable_channel(JNIEnv *env, DecodeContext *context)
{
    if (env == NULL || context == NULL || context->seekable_channel == NULL)
    {
        return;
    }

    (*env)->DeleteGlobalRef(env, context->seekable_channel);
    context->seekable_channel = NULL;
}

/*
 * Deletes a global InputStream reference held by a pull decode context. The
 * ordinary one-shot InputStream path stores only a local reference and therefore
 * leaves input_stream_is_global clear so this helper does not touch it.
 */
static void clear_decode_input_stream(JNIEnv *env, DecodeContext *context)
{
    if (env == NULL || context == NULL || !context->input_stream_is_global ||
        context->input_stream == NULL)
    {
        return;
    }

    (*env)->DeleteGlobalRef(env, context->input_stream);
    context->input_stream = NULL;
    context->input_stream_is_global = 0;
}

/*
 * Frees any decoded PCM block that has not yet been fully drained to Java.
 * The buffer is native-owned because it may need to survive across several
 * readPullDecoderInterleaved() calls.
 */
static void clear_decode_pull_pending(DecodeContext *context)
{
    if (context == NULL)
    {
        return;
    }

    free(context->pull_pending_samples);
    context->pull_pending_samples = NULL;
    context->pull_pending_sample_count = 0u;
    context->pull_pending_sample_offset = 0u;
}

/*
 * Finds a live decoder session registry entry by handle. When requested,
 * previous receives the preceding list node so callers can remove the entry.
 */
static DecodeSessionRegistryEntry *find_decode_session_entry_locked(jlong handle, DecodeSessionRegistryEntry **previous)
{
    DecodeSessionRegistryEntry *prev = NULL;
    DecodeSessionRegistryEntry *entry = g_decode_session_registry;
    while (entry != NULL)
    {
        if (entry->handle == handle)
        {
            if (previous != NULL)
            {
                *previous = prev;
            }

            return entry;
        }

        prev = entry;
        entry = entry->next;
    }

    if (previous != NULL)
    {
        *previous = NULL;
    }

    return NULL;
}

/*
 * Reserves the next positive Java-visible session handle. Zero and negative
 * values are never valid handles, so wrap-around restarts at the first value.
 */
static jlong reserve_decode_session_handle_locked(void)
{
    for (;;)
    {
        jlong handle = g_next_decode_session_handle++;
        /*
         * Keep zero invalid forever. If signed overflow or wrap-around makes
         * the next candidate non-positive, restart at one and keep searching
         * until a currently-unused positive handle is found.
         */
        if (g_next_decode_session_handle <= 0)
        {
            g_next_decode_session_handle = JFLAC_FIRST_VALID_SESSION_HANDLE;
        }

        if (handle > 0 && find_decode_session_entry_locked(handle, NULL) == NULL)
        {
            return handle;
        }
    }
}

/*
 * Releases the libFLAC decoder and heap storage owned by one session. It also
 * drops any global channel reference captured for a seekable-channel session.
 */
static void destroy_decode_session(JNIEnv *env, DecodeSession *session)
{
    if (session == NULL)
    {
        return;
    }

    if (session->decoder != NULL)
    {
        g_flac_api.stream_decoder_finish(session->decoder);
        g_flac_api.stream_decoder_delete(session->decoder);
        session->decoder = NULL;
    }

    clear_decode_seekable_channel(env, &session->context);
    clear_decode_input_stream(env, &session->context);
    clear_decode_pull_pending(&session->context);
    free(session);
}

/*
 * Adds a new decoder session to the registry and returns its handle. Ownership
 * transfers to the registry on success; a zero return means the caller still
 * owns the session and must destroy it.
 */
static jlong register_decode_session(DecodeSession *session)
{
    DecodeSessionRegistryEntry *entry = (DecodeSessionRegistryEntry *)calloc(1u, sizeof(DecodeSessionRegistryEntry));
    if (entry == NULL)
    {
        return 0;
    }

    lock_decode_session_registry();
    jlong handle = reserve_decode_session_handle_locked();
    session->handle = handle;
    session->reference_count = 0;
    session->released = 0;
    session->in_use = 0;
    entry->handle = handle;
    entry->session = session;
    entry->next = g_decode_session_registry;
    g_decode_session_registry = entry;
    unlock_decode_session_registry();
    return handle;
}

/*
 * Acquires an active decoder session for one decode operation. The reference
 * count prevents close from freeing native state while callbacks are running.
 */
static DecodeSession *acquire_decode_session(JNIEnv *env, jlong handle)
{
    DecodeSession *session = NULL;

    if (handle > 0)
    {
        lock_decode_session_registry();
        DecodeSessionRegistryEntry *entry = find_decode_session_entry_locked(handle, NULL);
        DecodeSession *candidate = entry != NULL ? entry->session : NULL;
        if (candidate != NULL && !candidate->released && !candidate->in_use &&
            candidate->decoder != NULL)
        {
            /*
             * The registry lock covers the transition from idle to in-use.
             * That makes direct JNI calls safe even if Java bypasses the
             * higher-level Kotlin session object and calls decodeDecoderRange
             * twice from different threads.
             */
            session = candidate;
            session->reference_count += 1;
            session->in_use = 1;
        }

        unlock_decode_session_registry();
    }

    if (session == NULL)
    {
        throw_illegal_state_exception(env, "The native FLAC decoder handle is not active.");
    }

    return session;
}

/*
 * Releases a session operation reference and destroys the session when close
 * already removed it from the active registry. The Java exception state is not
 * touched here because release is cleanup, not a user-visible operation.
 */
static void release_decode_session_reference(JNIEnv *env, DecodeSession *session)
{
    int should_destroy = 0;

    if (session == NULL)
    {
        return;
    }

    lock_decode_session_registry();
    if (session->reference_count > 0)
    {
        session->reference_count -= 1;
    }

    session->in_use = 0;
    /*
     * A release request during decode only marks the session as released. The
     * decoder is destroyed here, after libFLAC has returned and callbacks are no
     * longer using DecodeContext.
     */
    should_destroy = session->released && session->reference_count == 0;
    unlock_decode_session_registry();

    if (should_destroy)
    {
        destroy_decode_session(env, session);
    }
}

/*
 * Removes a decoder session handle from the active registry. The caller either
 * receives the session for immediate destruction or leaves destruction to the
 * final operation reference that is still inside libFLAC callbacks.
 */
static DecodeSession *remove_decode_session(jlong handle)
{
    DecodeSession *session_to_destroy = NULL;

    if (handle <= 0)
    {
        return NULL;
    }

    lock_decode_session_registry();
    DecodeSessionRegistryEntry *previous = NULL;
    DecodeSessionRegistryEntry *entry = find_decode_session_entry_locked(handle, &previous);
    if (entry != NULL)
    {
        DecodeSession *session = entry->session;
        if (previous == NULL)
        {
            g_decode_session_registry = entry->next;
        }
        else
        {
            previous->next = entry->next;
        }

        free(entry);

        if (session != NULL)
        {
            /*
             * Removing the registry entry makes the handle immediately invalid
             * for new calls. Destruction can still be deferred if an existing
             * decode has already acquired the session.
             */
            session->released = 1;
            if (session->reference_count == 0)
            {
                session_to_destroy = session;
            }
        }
    }

    unlock_decode_session_registry();

    return session_to_destroy;
}

/*
 * Releases every session that remains registered when this JNI library is
 * unloaded. Public close paths normally remove entries one at a time; this is
 * the class-loader teardown fallback for sessions abandoned by Java code.
 * JNI_OnUnload cannot overlap a native entry point, so no operation reference
 * can still be using a detached session.
 */
static void destroy_all_decode_sessions(JNIEnv *env)
{
    lock_decode_session_registry();
    DecodeSessionRegistryEntry *entry = g_decode_session_registry;
    g_decode_session_registry = NULL;
    unlock_decode_session_registry();
    while (entry != NULL)
    {
        DecodeSessionRegistryEntry *next = entry->next;
        destroy_decode_session(env, entry->session);
        free(entry);
        entry = next;
    }
}

/*
 * Returns the number of Java-visible decoder handles currently owned by the
 * registry. This package-private diagnostic is deliberately read-only and is
 * used to verify lifecycle ownership without exposing native pointers.
 */
static jlong active_decode_session_count(void)
{
    jlong count = 0;

    lock_decode_session_registry();
    for (DecodeSessionRegistryEntry *entry = g_decode_session_registry;
         entry != NULL;
         entry = entry->next)
    {
        count += 1;
    }

    unlock_decode_session_registry();
    return count;
}

/*
 * Resolves Java callback methods and resets per-operation decode state before
 * a libFLAC decode call starts.
 */
static int prepare_decode_context(JNIEnv *env, DecodeContext *context, jobject consumer)
{
    jclass consumer_class = (*env)->GetObjectClass(env, consumer);
    if (consumer_class == NULL)
    {
        return 0;
    }

    /*
     * Cache method IDs once per decode call. The consumer object itself is not
     * promoted to a global reference because libFLAC invokes callbacks only
     * while the originating JNI call is still on the stack.
     *
     * This function is also the reset point for fields that describe the
     * current decode operation. It intentionally does not clear saw_stream_info
     * or stream_total_samples: reusable sessions populate those during
     * openDecoderFile(), then later range decodes use the cached totals while
     * metadata callbacks are suppressed.
     *
     * GetMethodID leaves a pending Java exception if the consumer does not
     * implement the expected method. Returning 0 lets the caller unwind without
     * replacing that exception with a less precise native error.
     */
    context->env = env;
    context->consumer = consumer;
    context->on_stream_info =
        (*env)->GetMethodID(env, consumer_class, "onStreamInfo", "(Lorg/zzvsjs/jflac/FlacStreamInfo;)V");
    context->on_pcm_interleaved = (*env)->GetMethodID(env, consumer_class, "onPcmInterleaved", "([II)V");
    context->on_complete = (*env)->GetMethodID(env, consumer_class, "onComplete", "()V");
    context->saw_error = 0;
    context->suppress_metadata = 0;
    context->range_limited = 0;
    context->max_frames = 0;
    context->emitted_frames = 0;
    context->range_complete = 0;
    if (context->on_stream_info == NULL || context->on_pcm_interleaved == NULL ||
        context->on_complete == NULL)
    {
        return 0;
    }

    return 1;
}

/*
 * Configures optional range limiting for the decode write callback. The caller
 * passes policy flags instead of booleans so call sites read as
 * LIMITED_RANGE/UNLIMITED_RANGE.
 */
static void configure_decode_range(DecodeContext *context, int range_limited, jlong max_frames)
{
    /*
     * max_frames is validated before this helper is called. A zero-length range
     * is a successful decode with no PCM callback, so mark it complete up front
     * and let the entry point call onComplete normally.
     */
    context->range_limited = range_limited;
    context->max_frames = range_limited ? (FLAC__uint64)max_frames : 0u;
    context->emitted_frames = 0u;
    context->range_complete = range_limited && max_frames == 0;
}

/*
 * Validates common seek/range arguments before libFLAC is called. It rejects
 * negative positions and Java Long overflow in firstSample + maxFrames.
 */
static int validate_decode_request(JNIEnv *env, jlong first_sample, int range_limited, jlong max_frames)
{
    if (first_sample < 0)
    {
        throw_illegal_argument_exception(env, "First sample must be non-negative.");
        return 0;
    }

    if (range_limited && max_frames < 0)
    {
        throw_illegal_argument_exception(env, "Max frames must be non-negative.");
        return 0;
    }

    if (range_limited && max_frames > LLONG_MAX - first_sample)
    {
        throw_illegal_argument_exception(env, "Decode range end must not overflow Long.");
        return 0;
    }

    return 1;
}

/*
 * Checks whether a range starts beyond the STREAMINFO total sample count. A
 * positive answer is a public error because callers asked for samples that do
 * not exist.
 */
static int decode_range_starts_after_stream(DecodeContext *context, jlong first_sample)
{
    /*
     * STREAMINFO total_samples can be zero when the total is unknown. In that
     * case the wrapper cannot pre-validate the range and must let libFLAC seek
     * decide whether the request is valid.
     */
    return context->saw_stream_info && context->stream_total_samples > 0u &&
           (FLAC__uint64)first_sample > context->stream_total_samples;
}

/*
 * Checks whether a range starts exactly at end-of-stream. That is a successful
 * empty decode, unlike starting after end-of-stream.
 */
static int decode_range_starts_at_stream_end(DecodeContext *context, jlong first_sample)
{
    return context->saw_stream_info && context->stream_total_samples > 0u &&
           (FLAC__uint64)first_sample == context->stream_total_samples;
}

/*
 * Processes the stream one frame at a time so a range-limited decode can stop
 * immediately after the callback emits the requested final frame.
 */
static int validate_chained_link_sample_count(DecodeContext *context)
{
    if (context->current_link_total_samples == 0u)
    {
        return 1;
    }

    FLAC__uint64 emitted_for_link = context->emitted_frames - context->current_link_start_emitted_frames;
    if (emitted_for_link == context->current_link_total_samples)
    {
        return 1;
    }

    char buffer[JFLAC_MESSAGE_BUFFER_SIZE];
    snprintf(buffer, sizeof(buffer),
             "Chained Ogg FLAC link ended after %llu frames, but its STREAMINFO total declares exactly %llu frames.",
             (unsigned long long)emitted_for_link, (unsigned long long)context->current_link_total_samples);
    throw_decode_exception(context->env, buffer);
    return 0;
}

static FLAC__bool process_decode_stream(FLAC__StreamDecoder *decoder, DecodeContext *context)
{
    if (!context->range_limited)
    {
        if (context->decode_chained_ogg)
        {
            /*
             * Do not use process_until_end_of_stream for checked chains.
             * libFLAC 1.5 calls finish_link internally but discards its MD5
             * result, which could hide corruption in any non-final link.
             */
            for (;;)
            {
                FLAC__bool link_success = g_flac_api.stream_decoder_process_until_end_of_link(decoder);
                if (!link_success || (*context->env)->ExceptionCheck(context->env))
                {
                    return false;
                }

                FLAC__StreamDecoderState state = g_flac_api.stream_decoder_get_state(decoder);
                if (state == FLAC__STREAM_DECODER_END_OF_STREAM)
                {
                    return validate_chained_link_sample_count(context) ? true : false;
                }

                if (state != FLAC__STREAM_DECODER_END_OF_LINK)
                {
                    return false;
                }

                if (!validate_chained_link_sample_count(context))
                {
                    return false;
                }

                if (!g_flac_api.stream_decoder_finish_link(decoder))
                {
                    context->chained_link_md5_failed = 1;
                    return false;
                }
            }
        }

        return g_flac_api.stream_decoder_process_until_end_of_stream(decoder);
    }

    /*
     * For bounded ranges, do not use a write-callback ABORT as a normal stop
     * signal. ABORT puts the decoder into an error-like path and can leave a
     * reusable decoder awkward to seek again. process_single lets us consume
     * one FLAC frame at a time and stop once the write callback reports that
     * enough PCM frames were emitted.
     */
    while (!context->range_complete)
    {
        if ((*context->env)->ExceptionCheck(context->env))
        {
            return false;
        }

        FLAC__bool success = g_flac_api.stream_decoder_process_single(decoder);
        if (!success)
        {
            return false;
        }

        if ((*context->env)->ExceptionCheck(context->env))
        {
            return false;
        }

        FLAC__StreamDecoderState state = g_flac_api.stream_decoder_get_state(decoder);
        if (state == FLAC__STREAM_DECODER_END_OF_STREAM)
        {
            return true;
        }

        if (state == FLAC__STREAM_DECODER_ABORTED)
        {
            return false;
        }
    }

    return true;
}

/*
 * Throws the public exception for a decode range that starts after EOF. Keeping
 * the text in one helper makes file, channel, and session paths consistent.
 */
static void throw_range_after_stream_exception(JNIEnv *env)
{
    throw_decode_exception(env, "Requested decode range starts after the end of the FLAC stream.");
}

/*
 * Maps libFLAC decoder error callback statuses to stable diagnostic text. The
 * state string explains where libFLAC stopped; this callback status explains
 * the last stream-level problem libFLAC reported.
 */
static const char *decoder_error_status_name(FLAC__StreamDecoderErrorStatus status)
{
    switch (status)
    {
    case FLAC__STREAM_DECODER_ERROR_STATUS_LOST_SYNC:
        return "LOST_SYNC";
    case FLAC__STREAM_DECODER_ERROR_STATUS_BAD_HEADER:
        return "BAD_HEADER";
    case FLAC__STREAM_DECODER_ERROR_STATUS_FRAME_CRC_MISMATCH:
        return "FRAME_CRC_MISMATCH";
    case FLAC__STREAM_DECODER_ERROR_STATUS_UNPARSEABLE_STREAM:
        return "UNPARSEABLE_STREAM";
    case FLAC__STREAM_DECODER_ERROR_STATUS_BAD_METADATA:
        return "BAD_METADATA";
    case FLAC__STREAM_DECODER_ERROR_STATUS_OUT_OF_BOUNDS:
        return "OUT_OF_BOUNDS";
    case FLAC__STREAM_DECODER_ERROR_STATUS_MISSING_FRAME:
        return "MISSING_FRAME";
    default:
        return "UNKNOWN";
    }
}

/*
 * Resolves InputStream.read(byte[], int, int) for sequential stream decode.
 * The stream is not promoted to a global reference because decodeStream()
 * completes synchronously before the JNI call returns.
 */
static int prepare_decode_input_stream(JNIEnv *env, DecodeContext *context, jobject input_stream)
{
    if (input_stream == NULL)
    {
        throw_illegal_argument_exception(env, "Decoder input stream must not be null.");
        return 0;
    }

    jclass input_class = (*env)->GetObjectClass(env, input_stream);
    if (input_class == NULL)
    {
        return 0;
    }

    context->input_stream = input_stream;
    context->input_read = (*env)->GetMethodID(env, input_class, "read", "([BII)I");
    if (context->input_read == NULL)
    {
        return 0;
    }

    return 1;
}

/*
 * Prepares an InputStream for a pull session. The method lookup is identical
 * to one-shot stream decode, but the stream object itself must become a global
 * reference because libFLAC will call back from later readPullDecoderInterleaved
 * JNI calls after the open method's local references have expired.
 */
static int prepare_decode_input_stream_global(JNIEnv *env, DecodeContext *context, jobject input_stream)
{
    if (!prepare_decode_input_stream(env, context, input_stream))
    {
        return 0;
    }

    jobject global_stream = (*env)->NewGlobalRef(env, input_stream);
    if (global_stream == NULL)
    {
        return 0;
    }

    context->input_stream = global_stream;
    context->input_stream_is_global = 1;
    return 1;
}

/*
 * Resolves SeekableByteChannel callbacks and promotes the channel reference.
 * Reusable channel sessions keep this reference across JNI calls, so the
 * corresponding destroy path must always call clear_decode_seekable_channel().
 */
static int prepare_decode_seekable_channel(JNIEnv *env, DecodeContext *context, jobject channel)
{
    if (channel == NULL)
    {
        throw_illegal_argument_exception(env, "Decoder seekable channel must not be null.");
        return 0;
    }

    jclass readable_class = (*env)->FindClass(env, "java/nio/channels/ReadableByteChannel");
    jclass seekable_class = (*env)->FindClass(env, "java/nio/channels/SeekableByteChannel");
    if (readable_class == NULL || seekable_class == NULL)
    {
        return 0;
    }

    context->channel_read = (*env)->GetMethodID(env, readable_class, "read", "(Ljava/nio/ByteBuffer;)I");
    context->channel_position = (*env)->GetMethodID(env, seekable_class, "position", "()J");
    context->channel_seek =
        (*env)->GetMethodID(env, seekable_class, "position", "(J)Ljava/nio/channels/SeekableByteChannel;");
    context->channel_size = (*env)->GetMethodID(env, seekable_class, "size", "()J");
    if (context->channel_read == NULL || context->channel_position == NULL ||
        context->channel_seek == NULL || context->channel_size == NULL)
    {
        return 0;
    }

    context->seekable_channel = (*env)->NewGlobalRef(env, channel);
    if (context->seekable_channel == NULL)
    {
        return 0;
    }

    context->channel_base_offset = (*env)->CallLongMethod(env, channel, context->channel_position);
    if ((*env)->ExceptionCheck(env))
    {
        clear_decode_seekable_channel(env, context);
        return 0;
    }

    if (context->channel_base_offset < 0)
    {
        clear_decode_seekable_channel(env, context);
        throw_decode_exception(env, "SeekableByteChannel returned a negative position.");
        return 0;
    }

    return 1;
}

/*
 * libFLAC read callback backed by java.nio.channels.SeekableByteChannel. The
 * direct ByteBuffer wraps libFLAC's destination buffer, so the channel can fill
 * native memory without an intermediate copy.
 */
static FLAC__StreamDecoderReadStatus decode_channel_read_callback(const FLAC__StreamDecoder *decoder,
                                                                  FLAC__byte buffer[], size_t *bytes,
                                                                  void *client_data)
{
    (void)decoder;
    DecodeContext *context = (DecodeContext *)client_data;
    JNIEnv *env = context->env;

    if ((*env)->ExceptionCheck(env))
    {
        if (bytes != NULL)
        {
            *bytes = 0u;
        }

        return FLAC__STREAM_DECODER_READ_STATUS_ABORT;
    }

    if (bytes == NULL)
    {
        return FLAC__STREAM_DECODER_READ_STATUS_ABORT;
    }

    if (context->seekable_channel == NULL || context->channel_read == NULL || *bytes == 0u)
    {
        *bytes = 0u;
        return FLAC__STREAM_DECODER_READ_STATUS_ABORT;
    }

    if (*bytes > (size_t)INT_MAX)
    {
        *bytes = 0u;
        throw_decode_exception(env, "SeekableByteChannel read request is too large for one ByteBuffer.");
        return FLAC__STREAM_DECODER_READ_STATUS_ABORT;
    }

    jobject byte_buffer = (*env)->NewDirectByteBuffer(env, buffer, (jlong)*bytes);
    if (byte_buffer == NULL)
    {
        *bytes = 0u;
        return FLAC__STREAM_DECODER_READ_STATUS_ABORT;
    }

    jint read = (*env)->CallIntMethod(env, context->seekable_channel, context->channel_read, byte_buffer);
    jboolean read_failed = (*env)->ExceptionCheck(env);
    (*env)->DeleteLocalRef(env, byte_buffer);
    if (read_failed)
    {
        *bytes = 0u;
        return FLAC__STREAM_DECODER_READ_STATUS_ABORT;
    }

    if (read < 0)
    {
        /* Java channel EOF is -1; libFLAC expects bytes=0 plus END_OF_STREAM. */
        *bytes = 0u;
        return FLAC__STREAM_DECODER_READ_STATUS_END_OF_STREAM;
    }

    if (read == 0)
    {
        /*
         * Zero progress on a non-empty blocking read would make libFLAC spin,
         * so V1 treats it as a hard callback failure.
         */
        *bytes = 0u;
        throw_decode_exception(env, "SeekableByteChannel returned zero bytes for a non-empty read request.");
        return FLAC__STREAM_DECODER_READ_STATUS_ABORT;
    }

    *bytes = (size_t)read;
    return FLAC__STREAM_DECODER_READ_STATUS_CONTINUE;
}

/*
 * libFLAC seek callback backed by SeekableByteChannel.position(long). Offsets
 * are relative to the channel position captured at decoder open, allowing
 * callers to decode FLAC data embedded after a prefix in a larger channel.
 */
static FLAC__StreamDecoderSeekStatus decode_channel_seek_callback(const FLAC__StreamDecoder *decoder,
                                                                  FLAC__uint64 absolute_byte_offset,
                                                                  void *client_data)
{
    (void)decoder;
    DecodeContext *context = (DecodeContext *)client_data;
    JNIEnv *env = context->env;

    if (context->seekable_channel == NULL || context->channel_seek == NULL ||
        absolute_byte_offset > (FLAC__uint64)(LLONG_MAX - context->channel_base_offset))
    {
        return FLAC__STREAM_DECODER_SEEK_STATUS_ERROR;
    }

    jobject ignored = (*env)->CallObjectMethod(env, context->seekable_channel, context->channel_seek,
                                               context->channel_base_offset + (jlong)absolute_byte_offset);
    jboolean seek_failed = (*env)->ExceptionCheck(env);
    if (ignored != NULL)
    {
        (*env)->DeleteLocalRef(env, ignored);
    }

    return seek_failed ? FLAC__STREAM_DECODER_SEEK_STATUS_ERROR : FLAC__STREAM_DECODER_SEEK_STATUS_OK;
}

/*
 * libFLAC tell callback backed by SeekableByteChannel.position(). libFLAC
 * expects positions relative to the stream start, so the captured base offset
 * is subtracted from the channel's absolute position.
 */
static FLAC__StreamDecoderTellStatus decode_channel_tell_callback(const FLAC__StreamDecoder *decoder,
                                                                  FLAC__uint64 *absolute_byte_offset,
                                                                  void *client_data)
{
    (void)decoder;
    DecodeContext *context = (DecodeContext *)client_data;
    JNIEnv *env = context->env;

    if (context->seekable_channel == NULL || context->channel_position == NULL ||
        absolute_byte_offset == NULL)
    {
        return FLAC__STREAM_DECODER_TELL_STATUS_ERROR;
    }

    jlong position = (*env)->CallLongMethod(env, context->seekable_channel, context->channel_position);
    if ((*env)->ExceptionCheck(env) || position < context->channel_base_offset)
    {
        return FLAC__STREAM_DECODER_TELL_STATUS_ERROR;
    }

    *absolute_byte_offset = (FLAC__uint64)(position - context->channel_base_offset);
    return FLAC__STREAM_DECODER_TELL_STATUS_OK;
}

/*
 * libFLAC length callback backed by SeekableByteChannel.size(). The reported
 * length is also relative to the captured base offset rather than the whole
 * backing file/channel.
 */
static FLAC__StreamDecoderLengthStatus decode_channel_length_callback(const FLAC__StreamDecoder *decoder,
                                                                      FLAC__uint64 *stream_length, void *client_data)
{
    (void)decoder;
    DecodeContext *context = (DecodeContext *)client_data;
    JNIEnv *env = context->env;

    if (context->seekable_channel == NULL || context->channel_size == NULL || stream_length == NULL)
    {
        return FLAC__STREAM_DECODER_LENGTH_STATUS_ERROR;
    }

    jlong size = (*env)->CallLongMethod(env, context->seekable_channel, context->channel_size);
    if ((*env)->ExceptionCheck(env) || size < context->channel_base_offset)
    {
        return FLAC__STREAM_DECODER_LENGTH_STATUS_ERROR;
    }

    *stream_length = (FLAC__uint64)(size - context->channel_base_offset);
    return FLAC__STREAM_DECODER_LENGTH_STATUS_OK;
}

/*
 * libFLAC EOF callback backed by current channel position and size. On Java
 * exception this reports EOF so libFLAC unwinds quickly and the pending Java
 * exception can surface unchanged.
 */
static FLAC__bool decode_channel_eof_callback(const FLAC__StreamDecoder *decoder, void *client_data)
{
    (void)decoder;
    DecodeContext *context = (DecodeContext *)client_data;
    JNIEnv *env = context->env;

    if (context->seekable_channel == NULL || context->channel_position == NULL ||
        context->channel_size == NULL)
    {
        return true;
    }

    jlong position = (*env)->CallLongMethod(env, context->seekable_channel, context->channel_position);
    if ((*env)->ExceptionCheck(env))
    {
        return true;
    }

    jlong size = (*env)->CallLongMethod(env, context->seekable_channel, context->channel_size);
    if ((*env)->ExceptionCheck(env))
    {
        return true;
    }

    return position >= size ? true : false;
}

/*
 * libFLAC read callback backed by java.io.InputStream. It copies through a
 * temporary JVM byte array because InputStream cannot fill a native pointer.
 */
static FLAC__StreamDecoderReadStatus decode_read_callback(const FLAC__StreamDecoder *decoder, FLAC__byte buffer[],
                                                          size_t *bytes, void *client_data)
{
    DecodeContext *context = (DecodeContext *)client_data;
    if (context == NULL || context->env == NULL)
    {
        if (bytes != NULL)
        {
            *bytes = 0u;
        }

        return FLAC__STREAM_DECODER_READ_STATUS_ABORT;
    }

    if (context->seekable_channel != NULL)
    {
        return decode_channel_read_callback(decoder, buffer, bytes, client_data);
    }

    (void)decoder;
    JNIEnv *env = context->env;

    if ((*env)->ExceptionCheck(env))
    {
        if (bytes != NULL)
        {
            *bytes = 0u;
        }

        return FLAC__STREAM_DECODER_READ_STATUS_ABORT;
    }

    if (bytes == NULL)
    {
        return FLAC__STREAM_DECODER_READ_STATUS_ABORT;
    }

    if (context->input_stream == NULL || context->input_read == NULL || *bytes == 0u)
    {
        *bytes = 0u;
        return FLAC__STREAM_DECODER_READ_STATUS_ABORT;
    }

    if (*bytes > (size_t)INT_MAX)
    {
        *bytes = 0u;
        throw_decode_exception(env, "InputStream read request is too large for one JVM ByteArray.");
        return FLAC__STREAM_DECODER_READ_STATUS_ABORT;
    }

    jsize requested = (jsize)*bytes;
    jbyteArray chunk = (*env)->NewByteArray(env, requested);
    if (chunk == NULL)
    {
        *bytes = 0u;
        return FLAC__STREAM_DECODER_READ_STATUS_ABORT;
    }

    jint read = (*env)->CallIntMethod(env, context->input_stream, context->input_read, chunk, (jint)0, (jint)requested);
    if ((*env)->ExceptionCheck(env))
    {
        (*env)->DeleteLocalRef(env, chunk);
        *bytes = 0u;
        return FLAC__STREAM_DECODER_READ_STATUS_ABORT;
    }

    if (read < 0)
    {
        (*env)->DeleteLocalRef(env, chunk);
        /* Java InputStream EOF is -1; libFLAC expects bytes=0 plus END_OF_STREAM. */
        *bytes = 0u;
        return FLAC__STREAM_DECODER_READ_STATUS_END_OF_STREAM;
    }

    if (read == 0)
    {
        (*env)->DeleteLocalRef(env, chunk);
        /*
         * InputStream.read(byte[], off, len) should not return zero for a
         * positive len in this blocking path. Treat zero as no progress rather
         * than looping indefinitely inside libFLAC.
         */
        *bytes = 0u;
        throw_decode_exception(env, "InputStream returned zero bytes for a non-empty read request.");
        return FLAC__STREAM_DECODER_READ_STATUS_ABORT;
    }

    (*env)->GetByteArrayRegion(env, chunk, 0, read, (jbyte *)buffer);
    (*env)->DeleteLocalRef(env, chunk);
    if ((*env)->ExceptionCheck(env))
    {
        *bytes = 0u;
        return FLAC__STREAM_DECODER_READ_STATUS_ABORT;
    }

    *bytes = (size_t)read;
    return FLAC__STREAM_DECODER_READ_STATUS_CONTINUE;
}

/*
 * Copies as many pending pull samples as the current Java read can accept.
 * Counts are tracked in frames for the public API but copied in samples because
 * the IntArray is interleaved. The offset is always aligned to the channel
 * count because pending buffers are created from complete libFLAC frames.
 */
static int drain_decode_pull_pending(JNIEnv *env, DecodeContext *context)
{
    if (context->pull_pending_samples == NULL ||
        context->pull_pending_sample_offset >= context->pull_pending_sample_count)
    {
        clear_decode_pull_pending(context);
        return 1;
    }

    if (context->pull_channels == 0u)
    {
        throw_decode_exception(env, "Pull decoder has no channel count.");
        return 0;
    }

    uint32_t remaining_request = context->pull_requested_frames - context->pull_written_frames;
    if (remaining_request == 0u)
    {
        return 1;
    }

    uint32_t available_samples = context->pull_pending_sample_count - context->pull_pending_sample_offset;
    uint32_t available_frames = available_samples / context->pull_channels;
    uint32_t copy_frames = available_frames < remaining_request ? available_frames : remaining_request;
    uint32_t copy_samples = copy_frames * context->pull_channels;
    uint32_t output_offset_samples = context->pull_written_frames * context->pull_channels;

    /*
     * Java already validated the IntArray length, and readPullDecoderInterleaved
     * repeats that validation before setting these fields. Keep the native
     * bounds check here as a last defence against direct JNI misuse.
     */
    uint64_t output_sample_end = (uint64_t)output_offset_samples + (uint64_t)copy_samples;
    if (output_sample_end > (uint64_t)context->pull_output_capacity_samples)
    {
        throw_illegal_argument_exception(env, "Interleaved sample buffer must fit maxFrames * channels.");
        return 0;
    }

    (*env)->SetIntArrayRegion(env, context->pull_output, (jsize)output_offset_samples, (jsize)copy_samples,
                              context->pull_pending_samples + context->pull_pending_sample_offset);
    if ((*env)->ExceptionCheck(env))
    {
        return 0;
    }

    context->pull_pending_sample_offset += copy_samples;
    context->pull_written_frames += copy_frames;
    if (context->pull_pending_sample_offset >= context->pull_pending_sample_count)
    {
        clear_decode_pull_pending(context);
    }

    return 1;
}

/*
 * Pull-mode write callback path. libFLAC provides one complete decoded block at
 * a time. Even if Java requested a single frame, the full block is first copied
 * into native-owned pending storage, then the requested prefix is drained into
 * Java. Any suffix remains pending for the next read call, so no decoded PCM is
 * discarded just because Java reads in tiny chunks.
 */
static FLAC__StreamDecoderWriteStatus decode_pull_write_callback(JNIEnv *env, DecodeContext *context,
                                                                 const FLAC__Frame *frame,
                                                                 const FLAC__int32 *const buffer[])
{
    uint32_t channels = frame->header.channels;
    uint32_t frames = frame->header.blocksize;
    uint64_t sample_count = (uint64_t)channels * (uint64_t)frames;

    if (channels == 0u || frames == 0u || sample_count > (uint64_t)UINT32_MAX ||
        sample_count > (uint64_t)INT_MAX)
    {
        throw_decode_exception(env, "Decoded PCM frame is too large for one native pull buffer.");
        return FLAC__STREAM_DECODER_WRITE_STATUS_ABORT;
    }

    if (context->pull_pending_samples != NULL)
    {
        /*
         * readPullDecoderInterleaved drains pending data before asking libFLAC
         * for another block. Reaching this branch means a future code change
         * violated that invariant and would otherwise overwrite undelivered PCM.
         */
        throw_decode_exception(env, "Pull decoder still has pending PCM before receiving a new block.");
        return FLAC__STREAM_DECODER_WRITE_STATUS_ABORT;
    }

    jint *interleaved = (jint *)malloc(sizeof(jint) * (size_t)sample_count);
    if (interleaved == NULL)
    {
        throw_decode_exception(env, "Failed to allocate pull PCM buffer.");
        return FLAC__STREAM_DECODER_WRITE_STATUS_ABORT;
    }

    for (uint32_t frame_index = 0; frame_index < frames; ++frame_index)
    {
        for (uint32_t channel_index = 0; channel_index < channels; ++channel_index)
        {
            interleaved[(frame_index * channels) + channel_index] = (jint)buffer[channel_index][frame_index];
        }
    }

    context->pull_channels = channels;
    context->pull_pending_samples = interleaved;
    context->pull_pending_sample_count = (uint32_t)sample_count;
    context->pull_pending_sample_offset = 0u;

    return drain_decode_pull_pending(env, context) ? FLAC__STREAM_DECODER_WRITE_STATUS_CONTINUE
                                                   : FLAC__STREAM_DECODER_WRITE_STATUS_ABORT;
}

/*
 * libFLAC write callback. It interleaves channel buffers into one JVM IntArray,
 * crops the final frame for range decodes, and forwards the chunk to Java.
 */
static FLAC__StreamDecoderWriteStatus decode_write_callback(const FLAC__StreamDecoder *decoder,
                                                            const FLAC__Frame *frame, const FLAC__int32 *const buffer[],
                                                            void *client_data)
{
    (void)decoder;
    DecodeContext *context = (DecodeContext *)client_data;
    JNIEnv *env = context->env;

    if ((*env)->ExceptionCheck(env))
    {
        return FLAC__STREAM_DECODER_WRITE_STATUS_ABORT;
    }

    if (context->pull_mode)
    {
        return decode_pull_write_callback(env, context, frame, buffer);
    }

    uint32_t channels = frame->header.channels;
    uint32_t frames = frame->header.blocksize;
    uint32_t output_frames = frames;

    if (context->range_limited)
    {
        /*
         * libFLAC delivers complete decoded blocks. A caller's requested range
         * can end inside this block, so only copy the prefix that still belongs
         * to the public maxFrames window. The decoder itself keeps moving only
         * until process_decode_stream sees range_complete.
         */
        FLAC__uint64 remaining = context->max_frames - context->emitted_frames;
        if (remaining == 0u)
        {
            context->range_complete = 1;
            return FLAC__STREAM_DECODER_WRITE_STATUS_CONTINUE;
        }

        if (remaining < (FLAC__uint64)output_frames)
        {
            output_frames = (uint32_t)remaining;
        }
    }

    uint64_t sample_count = (uint64_t)channels * (uint64_t)output_frames;
    /*
     * JNI array lengths are signed int-sized. Guard before multiplication is
     * cast down to jsize so malformed files cannot wrap the allocation length.
     */
    if (channels == 0u || output_frames == 0u || sample_count > (uint64_t)INT_MAX)
    {
        throw_decode_exception(env, "Decoded PCM frame is too large for one JVM IntArray.");
        return FLAC__STREAM_DECODER_WRITE_STATUS_ABORT;
    }

    jintArray samples = (*env)->NewIntArray(env, (jsize)sample_count);
    if (samples == NULL)
    {
        return FLAC__STREAM_DECODER_WRITE_STATUS_ABORT;
    }

    jint *interleaved = (jint *)malloc(sizeof(jint) * (size_t)sample_count);
    if (interleaved == NULL)
    {
        (*env)->DeleteLocalRef(env, samples);
        throw_decode_exception(env, "Failed to allocate PCM buffer.");
        return FLAC__STREAM_DECODER_WRITE_STATUS_ABORT;
    }

    for (uint32_t frame_index = 0; frame_index < output_frames; ++frame_index)
    {
        for (uint32_t channel_index = 0; channel_index < channels; ++channel_index)
        {
            interleaved[(frame_index * channels) + channel_index] = (jint)buffer[channel_index][frame_index];
        }
    }

    /*
     * Local references created inside native callbacks still count against the
     * current JNI frame. Delete the IntArray after the Java callback returns so
     * long decodes do not exhaust the local reference table.
     */
    (*env)->SetIntArrayRegion(env, samples, 0, (jsize)sample_count, interleaved);
    free(interleaved);
    if ((*env)->ExceptionCheck(env))
    {
        (*env)->DeleteLocalRef(env, samples);
        return FLAC__STREAM_DECODER_WRITE_STATUS_ABORT;
    }

    (*env)->CallVoidMethod(env, context->consumer, context->on_pcm_interleaved, samples, (jint)output_frames);
    jboolean callback_failed = (*env)->ExceptionCheck(env);
    (*env)->DeleteLocalRef(env, samples);
    if (callback_failed)
    {
        return FLAC__STREAM_DECODER_WRITE_STATUS_ABORT;
    }

    context->emitted_frames += (FLAC__uint64)output_frames;
    if (context->range_limited && context->emitted_frames >= context->max_frames)
    {
        /*
         * This marks logical completion of the caller's requested range, not
         * necessarily physical end-of-stream. process_decode_stream will stop
         * requesting more FLAC frames and the entry point will call onComplete.
         */
        context->range_complete = 1;
    }

    return FLAC__STREAM_DECODER_WRITE_STATUS_CONTINUE;
}

/*
 * libFLAC metadata callback. It forwards STREAMINFO to Java unless a reusable
 * decoder session is suppressing duplicate metadata events.
 */
static void decode_metadata_callback(const FLAC__StreamDecoder *decoder, const FLAC__StreamMetadata *metadata,
                                     void *client_data)
{
    (void)decoder;
    DecodeContext *context = (DecodeContext *)client_data;
    if (metadata->type != FLAC__METADATA_TYPE_STREAMINFO)
    {
        return;
    }

    FLAC__StreamMetadata_StreamInfo public_stream_info = metadata->data.stream_info;
    if (context->decode_chained_ogg)
    {
        context->current_link_total_samples = metadata->data.stream_info.total_samples;
        context->current_link_start_emitted_frames = context->emitted_frames;
        if (context->chained_link_count > 0u)
        {
            const FLAC__StreamMetadata_StreamInfo *first = &context->stream_info;
            if (metadata->data.stream_info.sample_rate != first->sample_rate ||
                metadata->data.stream_info.channels != first->channels ||
                metadata->data.stream_info.bits_per_sample != first->bits_per_sample)
            {
                throw_decode_exception(
                    context->env,
                    "Chained Ogg FLAC links must use the same sample rate, channel count, and bits per sample.");
                return;
            }

            context->chained_link_count++;
            return;
        }

        context->chained_link_count = 1u;
        /*
         * A chain has one STREAMINFO per link, so block/frame ranges, total
         * samples, and MD5 cannot truthfully be represented by the existing
         * single-stream model. Keep only the common PCM shape and report the
         * chain-wide fields as unknown.
         */
        public_stream_info.total_samples = 0u;
        public_stream_info.min_blocksize = 0u;
        public_stream_info.max_blocksize = 0u;
        public_stream_info.min_framesize = 0u;
        public_stream_info.max_framesize = 0u;
        memset(public_stream_info.md5sum, 0, JFLAC_STREAMINFO_MD5_LENGTH);
    }

    /*
     * Always capture the first STREAMINFO, even when Java metadata delivery is
     * suppressed for reusable sessions. Range validation needs total_samples
     * to distinguish an empty range at EOF from a range that starts past EOF.
     */
    context->saw_stream_info = 1;
    context->stream_total_samples = metadata->data.stream_info.total_samples;
    context->stream_info = metadata->data.stream_info;
    if (context->consumer == NULL || context->suppress_metadata)
    {
        return;
    }

    jobject stream_info = new_stream_info(context->env, &public_stream_info);
    if (stream_info == NULL)
    {
        return;
    }

    (*context->env)->CallVoidMethod(context->env, context->consumer, context->on_stream_info, stream_info);
    (void)(*context->env)->ExceptionCheck(context->env);
    (*context->env)->DeleteLocalRef(context->env, stream_info);
}

/*
 * libFLAC error callback. The callback cannot throw directly as the final
 * error because libFLAC returns through a later API call, so it records status
 * for the caller to include in the eventual Java exception.
 */
static void decode_error_callback(const FLAC__StreamDecoder *decoder, FLAC__StreamDecoderErrorStatus status,
                                  void *client_data)
{
    (void)decoder;
    DecodeContext *context = (DecodeContext *)client_data;
    /*
     * Do not throw directly from libFLAC's error callback. The surrounding
     * process_* call will return false, and the entry point can then build one
     * Java exception that includes both the decoder state and callback status.
     */
    context->saw_error = 1;
    context->last_error_status = status;
}

/*
 * finish() resets a decoder to UNINITIALIZED. Copy the process state first so
 * a later Java exception reports the state that actually caused the failure.
 */
static void copy_resolved_decoder_state(const FLAC__StreamDecoder *decoder, char *buffer, size_t buffer_size)
{
    const char *state = g_flac_api.stream_decoder_get_resolved_state_string(decoder);
    if (state == NULL)
    {
        buffer[0] = '\0';
        return;
    }

    snprintf(buffer, buffer_size, "%s", state);
}

/*
 * Shared one-shot file decode implementation for full, seeked, and ranged
 * public JNI entry points.
 */
static void decode_file_internal(JNIEnv *env, jstring path, jint container, jboolean check_md5,
                                 jboolean decode_chained_ogg, jlong first_sample, jlong max_frames, jobject consumer,
                                 int seek_before_decode, int range_limited)
{
    /*
     * Shared one-shot decoder implementation:
     * - decodeFile: no seek, decode until EOF.
     * - decodeFileFrom: seek first, decode until EOF.
     * - decodeFileRange: seek first, decode at most maxFrames.
     *
     * Keeping these paths together keeps callback setup, error translation and
     * native resource cleanup identical for all public entry points.
     */
    if (!flac_api_ready(env))
    {
        return;
    }

    if (!validate_container(env, container))
    {
        return;
    }

    if (path == NULL || consumer == NULL)
    {
        throw_illegal_argument_exception(env, "Decoder path and consumer must not be null.");
        return;
    }

    if ((seek_before_decode || range_limited) &&
        !validate_decode_request(env, first_sample, range_limited, max_frames))
    {
        return;
    }

    if (decode_chained_ogg != JNI_FALSE && (seek_before_decode || range_limited))
    {
        throw_illegal_argument_exception(env, "Chained Ogg decoding is only available for whole-stream decode.");
        return;
    }

    char *utf8_path = jstring_to_utf8(env, path);
    if (utf8_path == NULL)
    {
        throw_decode_exception(env, "Failed to encode file path to UTF-8.");
        return;
    }

    DecodeContext context;
    memset(&context, 0, sizeof(context));
    context.decode_chained_ogg = decode_chained_ogg != JNI_FALSE;
    /*
     * DecodeContext is stack-owned for one-shot file decode. libFLAC callbacks
     * are synchronous, so this client_data pointer is not used after the native
     * method returns.
     */
    if (!prepare_decode_context(env, &context, consumer))
    {
        free(utf8_path);
        return;
    }

    configure_decode_range(&context, range_limited, max_frames);

    FLAC__StreamDecoder *decoder = g_flac_api.stream_decoder_new();
    if (decoder == NULL)
    {
        free(utf8_path);
        throw_decode_exception(env, "Failed to allocate FLAC decoder.");
        return;
    }

    if (!configure_decoder_chaining(env, decoder, container, decode_chained_ogg) ||
        !configure_decoder_md5_checking(env, decoder, check_md5))
    {
        free(utf8_path);
        g_flac_api.stream_decoder_delete(decoder);
        return;
    }

    FLAC__StreamDecoderInitStatus init_status = init_file_decoder_for_container(
        decoder, utf8_path, decode_write_callback, decode_metadata_callback, decode_error_callback, &context, container);
    /*
     * libFLAC has consumed the path during initialisation. Keep ownership
     * simple by freeing the temporary UTF-8 buffer immediately after init.
     */
    free(utf8_path);

    if (init_status != FLAC__STREAM_DECODER_INIT_STATUS_OK)
    {
        g_flac_api.stream_decoder_delete(decoder);
        throw_decode_exception(env, "Failed to initialize FLAC decoder.");
        return;
    }

    if (seek_before_decode || range_limited)
    {
        /*
         * Seeking and range validation need STREAMINFO first. Processing only
         * metadata also gives the callback a chance to cache total_samples
         * before any seek is attempted.
         */
        FLAC__bool metadata_success = g_flac_api.stream_decoder_process_until_end_of_metadata(decoder);
        if (!(*env)->ExceptionCheck(env) && !metadata_success)
        {
            const char *state = g_flac_api.stream_decoder_get_resolved_state_string(decoder);
            char buffer[JFLAC_MESSAGE_BUFFER_SIZE];
            snprintf(buffer, sizeof(buffer), "FLAC decoding failed while reading metadata%s%s%s%s%s.",
                     state != NULL ? ": " : "", state != NULL ? state : "",
                     context.saw_error ? " (decoder error callback: " : "",
                     context.saw_error ? decoder_error_status_name(context.last_error_status) : "",
                     context.saw_error ? ")" : "");
            throw_decode_exception(env, buffer);
        }

        if ((*env)->ExceptionCheck(env) || !metadata_success)
        {
            g_flac_api.stream_decoder_finish(decoder);
            g_flac_api.stream_decoder_delete(decoder);
            return;
        }

        if (decode_range_starts_after_stream(&context, first_sample))
        {
            g_flac_api.stream_decoder_finish(decoder);
            g_flac_api.stream_decoder_delete(decoder);
            throw_range_after_stream_exception(env);
            return;
        }

        if (context.range_complete || decode_range_starts_at_stream_end(&context, first_sample))
        {
            /*
             * A zero-frame range and a range starting exactly at EOF are both
             * successful empty decodes. Java still receives onComplete so its
             * summary state is consistent with non-empty decodes.
             */
            FLAC__bool finish_success = g_flac_api.stream_decoder_finish(decoder);
            if (!(*env)->ExceptionCheck(env) && finish_success)
            {
                (*env)->CallVoidMethod(env, consumer, context.on_complete);
                (void)(*env)->ExceptionCheck(env);
            }

            g_flac_api.stream_decoder_delete(decoder);
            return;
        }

        FLAC__bool seek_success =
            first_sample == 0 ? true : g_flac_api.stream_decoder_seek_absolute(decoder, (FLAC__uint64)first_sample);
        if (!(*env)->ExceptionCheck(env) && !seek_success)
        {
            const char *state = g_flac_api.stream_decoder_get_resolved_state_string(decoder);
            char buffer[JFLAC_MESSAGE_BUFFER_SIZE];
            snprintf(buffer, sizeof(buffer), "FLAC decoding failed while seeking%s%s%s%s%s.", state != NULL ? ": " : "",
                     state != NULL ? state : "", context.saw_error ? " (decoder error callback: " : "",
                     context.saw_error ? decoder_error_status_name(context.last_error_status) : "",
                     context.saw_error ? ")" : "");
            throw_decode_exception(env, buffer);
        }

        if ((*env)->ExceptionCheck(env) || !seek_success)
        {
            g_flac_api.stream_decoder_finish(decoder);
            g_flac_api.stream_decoder_delete(decoder);
            return;
        }
    }

    FLAC__bool success = process_decode_stream(decoder, &context);
    char process_state[JFLAC_MESSAGE_BUFFER_SIZE];
    copy_resolved_decoder_state(decoder, process_state, sizeof(process_state));
    FLAC__bool finish_success = g_flac_api.stream_decoder_finish(decoder);
    const char *state = process_state[0] != '\0' ? process_state : NULL;

    /*
     * Java exceptions raised by callbacks must win over synthetic native errors.
     * ExceptionCheck gates every later throw so user callback failures preserve
     * their original Java exception type and stack.
     */
    if (!(*env)->ExceptionCheck(env) && context.chained_link_md5_failed && check_md5 != JNI_FALSE)
    {
        throw_decode_exception(
            env,
            "FLAC MD5 verification failed in a non-final chained Ogg link: decoded PCM does not match the STREAMINFO signature.");
    }

    if (!(*env)->ExceptionCheck(env) && !success)
    {
        char buffer[JFLAC_MESSAGE_BUFFER_SIZE];
        snprintf(buffer, sizeof(buffer), "FLAC decoding failed%s%s%s%s%s.", state != NULL ? ": " : "",
                 state != NULL ? state : "", context.saw_error ? " (decoder error callback: " : "",
                 context.saw_error ? decoder_error_status_name(context.last_error_status) : "",
                 context.saw_error ? ")" : "");
        throw_decode_exception(env, buffer);
    }

    if (!(*env)->ExceptionCheck(env) && success && !finish_success && check_md5 != JNI_FALSE)
    {
        throw_decode_exception(env,
                               "FLAC MD5 verification failed: decoded PCM does not match the STREAMINFO signature.");
    }

    if (!(*env)->ExceptionCheck(env) && success && !finish_success)
    {
        char buffer[JFLAC_MESSAGE_BUFFER_SIZE];
        snprintf(buffer, sizeof(buffer), "FLAC decoding failed while finishing%s%s.", state != NULL ? ": " : "",
                 state != NULL ? state : "");
        throw_decode_exception(env, buffer);
    }

    if (!(*env)->ExceptionCheck(env) && success && finish_success)
    {
        (*env)->CallVoidMethod(env, consumer, context.on_complete);
        (void)(*env)->ExceptionCheck(env);
    }

    g_flac_api.stream_decoder_delete(decoder);
}

/*
 * Decodes a sequential InputStream through libFLAC's stream callback API.
 * Range and reusable-session decode stay file-only because plain InputStream
 * does not provide a clean seek/tell/length contract.
 */
static void decode_stream_internal(JNIEnv *env, jobject input_stream, jint container, jboolean check_md5,
                                   jboolean decode_chained_ogg, jobject consumer)
{
    if (!flac_api_ready(env))
    {
        return;
    }

    if (!validate_container(env, container))
    {
        return;
    }

    if (input_stream == NULL || consumer == NULL)
    {
        throw_illegal_argument_exception(env, "Decoder input stream and consumer must not be null.");
        return;
    }

    DecodeContext context;
    memset(&context, 0, sizeof(context));
    context.decode_chained_ogg = decode_chained_ogg != JNI_FALSE;
    /*
     * Plain InputStream decode is one-shot and sequential. All Java references
     * remain local because libFLAC finishes before this JNI method returns.
     */
    if (!prepare_decode_context(env, &context, consumer) ||
        !prepare_decode_input_stream(env, &context, input_stream))
    {
        return;
    }

    FLAC__StreamDecoder *decoder = g_flac_api.stream_decoder_new();
    if (decoder == NULL)
    {
        throw_decode_exception(env, "Failed to allocate FLAC stream decoder.");
        return;
    }

    if (!configure_decoder_chaining(env, decoder, container, decode_chained_ogg) ||
        !configure_decoder_md5_checking(env, decoder, check_md5))
    {
        g_flac_api.stream_decoder_delete(decoder);
        return;
    }

    FLAC__StreamDecoderInitStatus init_status = init_stream_decoder_for_container(
        decoder, decode_read_callback, NULL, NULL, NULL, NULL, decode_write_callback, decode_metadata_callback,
        decode_error_callback, &context, container);
    /*
     * NULL seek/tell/length/eof callbacks tell libFLAC this source is
     * non-seekable. That is why InputStream has no range or reusable session
     * API in V1.
     */
    if (init_status != FLAC__STREAM_DECODER_INIT_STATUS_OK)
    {
        g_flac_api.stream_decoder_delete(decoder);
        throw_decode_exception(env, "Failed to initialize FLAC stream decoder.");
        return;
    }

    FLAC__bool success = process_decode_stream(decoder, &context);
    char process_state[JFLAC_MESSAGE_BUFFER_SIZE];
    copy_resolved_decoder_state(decoder, process_state, sizeof(process_state));
    FLAC__bool finish_success = g_flac_api.stream_decoder_finish(decoder);
    const char *state = process_state[0] != '\0' ? process_state : NULL;

    if (!(*env)->ExceptionCheck(env) && context.chained_link_md5_failed && check_md5 != JNI_FALSE)
    {
        throw_decode_exception(
            env,
            "FLAC MD5 verification failed in a non-final chained Ogg link: decoded PCM does not match the STREAMINFO signature.");
    }

    if (!(*env)->ExceptionCheck(env) && !success)
    {
        char buffer[JFLAC_MESSAGE_BUFFER_SIZE];
        snprintf(buffer, sizeof(buffer), "FLAC stream decoding failed%s%s%s%s%s.", state != NULL ? ": " : "",
                 state != NULL ? state : "", context.saw_error ? " (decoder error callback: " : "",
                 context.saw_error ? decoder_error_status_name(context.last_error_status) : "",
                 context.saw_error ? ")" : "");
        throw_decode_exception(env, buffer);
    }

    if (!(*env)->ExceptionCheck(env) && success && !finish_success && check_md5 != JNI_FALSE)
    {
        throw_decode_exception(env,
                               "FLAC MD5 verification failed: decoded PCM does not match the STREAMINFO signature.");
    }

    if (!(*env)->ExceptionCheck(env) && success && !finish_success)
    {
        char buffer[JFLAC_MESSAGE_BUFFER_SIZE];
        snprintf(buffer, sizeof(buffer), "FLAC stream decoding failed while finishing%s%s.",
                 state != NULL ? ": " : "", state != NULL ? state : "");
        throw_decode_exception(env, buffer);
    }

    if (!(*env)->ExceptionCheck(env) && success && finish_success)
    {
        /*
         * Stream decode has no separate empty-range path; success always means
         * libFLAC reached EOF and the consumer can be completed.
         */
        (*env)->CallVoidMethod(env, consumer, context.on_complete);
        (void)(*env)->ExceptionCheck(env);
    }

    g_flac_api.stream_decoder_delete(decoder);
}

/*
 * Decodes a SeekableByteChannel through libFLAC's direct callback API. The
 * channel position at call entry is treated as byte offset zero for libFLAC.
 */
static void decode_channel_internal(JNIEnv *env, jobject channel, jint container, jboolean check_md5,
                                    jboolean decode_chained_ogg, jlong first_sample, jlong max_frames, jobject consumer,
                                    int seek_before_decode, int range_limited)
{
    if (!flac_api_ready(env))
    {
        return;
    }

    if (!validate_container(env, container))
    {
        return;
    }

    if (channel == NULL || consumer == NULL)
    {
        throw_illegal_argument_exception(env, "Decoder seekable channel and consumer must not be null.");
        return;
    }

    if ((seek_before_decode || range_limited) &&
        !validate_decode_request(env, first_sample, range_limited, max_frames))
    {
        return;
    }

    if (decode_chained_ogg != JNI_FALSE && (seek_before_decode || range_limited))
    {
        throw_illegal_argument_exception(env, "Chained Ogg decoding is only available for whole-stream decode.");
        return;
    }

    DecodeContext context;
    memset(&context, 0, sizeof(context));
    context.decode_chained_ogg = decode_chained_ogg != JNI_FALSE;
    /*
     * Channel decode is one-shot here, but prepare_decode_seekable_channel()
     * uses a global reference because the same helper is shared with reusable
     * sessions. The cleanup path always clears it.
     */
    if (!prepare_decode_context(env, &context, consumer) ||
        !prepare_decode_seekable_channel(env, &context, channel))
    {
        clear_decode_seekable_channel(env, &context);
        return;
    }

    configure_decode_range(&context, range_limited, max_frames);

    FLAC__StreamDecoder *decoder = g_flac_api.stream_decoder_new();
    if (decoder == NULL)
    {
        clear_decode_seekable_channel(env, &context);
        throw_decode_exception(env, "Failed to allocate FLAC channel decoder.");
        return;
    }

    if (!configure_decoder_chaining(env, decoder, container, decode_chained_ogg) ||
        !configure_decoder_md5_checking(env, decoder, check_md5))
    {
        g_flac_api.stream_decoder_delete(decoder);
        clear_decode_seekable_channel(env, &context);
        return;
    }

    FLAC__StreamDecoderInitStatus init_status = init_stream_decoder_for_container(
        decoder, decode_read_callback, decode_channel_seek_callback, decode_channel_tell_callback,
        decode_channel_length_callback, decode_channel_eof_callback, decode_write_callback, decode_metadata_callback,
        decode_error_callback, &context, container);
    if (init_status != FLAC__STREAM_DECODER_INIT_STATUS_OK)
    {
        g_flac_api.stream_decoder_delete(decoder);
        clear_decode_seekable_channel(env, &context);
        throw_decode_exception(env, "Failed to initialize FLAC channel decoder.");
        return;
    }

    if (seek_before_decode || range_limited)
    {
        /*
         * Metadata processing can invoke read/tell/length callbacks. If one of
         * those Java calls throws, ExceptionCheck below preserves that exact
         * Java exception and skips the synthetic native error.
         */
        FLAC__bool metadata_success = g_flac_api.stream_decoder_process_until_end_of_metadata(decoder);
        if (!(*env)->ExceptionCheck(env) && !metadata_success)
        {
            const char *state = g_flac_api.stream_decoder_get_resolved_state_string(decoder);
            char buffer[JFLAC_MESSAGE_BUFFER_SIZE];
            snprintf(buffer, sizeof(buffer), "FLAC channel decoding failed while reading metadata%s%s%s%s%s.",
                     state != NULL ? ": " : "", state != NULL ? state : "",
                     context.saw_error ? " (decoder error callback: " : "",
                     context.saw_error ? decoder_error_status_name(context.last_error_status) : "",
                     context.saw_error ? ")" : "");
            throw_decode_exception(env, buffer);
        }

        if ((*env)->ExceptionCheck(env) || !metadata_success)
        {
            g_flac_api.stream_decoder_finish(decoder);
            g_flac_api.stream_decoder_delete(decoder);
            clear_decode_seekable_channel(env, &context);
            return;
        }

        if (decode_range_starts_after_stream(&context, first_sample))
        {
            g_flac_api.stream_decoder_finish(decoder);
            g_flac_api.stream_decoder_delete(decoder);
            clear_decode_seekable_channel(env, &context);
            throw_range_after_stream_exception(env);
            return;
        }

        if (context.range_complete || decode_range_starts_at_stream_end(&context, first_sample))
        {
            /*
             * A valid empty range still finishes the temporary decoder and
             * releases the global channel reference before returning.
             */
            FLAC__bool finish_success = g_flac_api.stream_decoder_finish(decoder);
            if (!(*env)->ExceptionCheck(env) && finish_success)
            {
                (*env)->CallVoidMethod(env, consumer, context.on_complete);
                (void)(*env)->ExceptionCheck(env);
            }

            g_flac_api.stream_decoder_delete(decoder);
            clear_decode_seekable_channel(env, &context);
            return;
        }

        /*
         * Seeking to zero is already the decoder's current position after
         * metadata processing, so skip the libFLAC seek call for that common
         * case.
         */
        FLAC__bool seek_success =
            first_sample == 0 ? true : g_flac_api.stream_decoder_seek_absolute(decoder, (FLAC__uint64)first_sample);
        if (!(*env)->ExceptionCheck(env) && !seek_success)
        {
            const char *state = g_flac_api.stream_decoder_get_resolved_state_string(decoder);
            char buffer[JFLAC_MESSAGE_BUFFER_SIZE];
            snprintf(buffer, sizeof(buffer), "FLAC channel decoding failed while seeking%s%s%s%s%s.",
                     state != NULL ? ": " : "", state != NULL ? state : "",
                     context.saw_error ? " (decoder error callback: " : "",
                     context.saw_error ? decoder_error_status_name(context.last_error_status) : "",
                     context.saw_error ? ")" : "");
            throw_decode_exception(env, buffer);
        }

        if ((*env)->ExceptionCheck(env) || !seek_success)
        {
            g_flac_api.stream_decoder_finish(decoder);
            g_flac_api.stream_decoder_delete(decoder);
            clear_decode_seekable_channel(env, &context);
            return;
        }
    }

    FLAC__bool success = process_decode_stream(decoder, &context);
    char process_state[JFLAC_MESSAGE_BUFFER_SIZE];
    copy_resolved_decoder_state(decoder, process_state, sizeof(process_state));
    FLAC__bool finish_success = g_flac_api.stream_decoder_finish(decoder);
    const char *state = process_state[0] != '\0' ? process_state : NULL;

    if (!(*env)->ExceptionCheck(env) && context.chained_link_md5_failed && check_md5 != JNI_FALSE)
    {
        throw_decode_exception(
            env,
            "FLAC MD5 verification failed in a non-final chained Ogg link: decoded PCM does not match the STREAMINFO signature.");
    }

    if (!(*env)->ExceptionCheck(env) && !success)
    {
        char buffer[JFLAC_MESSAGE_BUFFER_SIZE];
        snprintf(buffer, sizeof(buffer), "FLAC channel decoding failed%s%s%s%s%s.", state != NULL ? ": " : "",
                 state != NULL ? state : "", context.saw_error ? " (decoder error callback: " : "",
                 context.saw_error ? decoder_error_status_name(context.last_error_status) : "",
                 context.saw_error ? ")" : "");
        throw_decode_exception(env, buffer);
    }

    if (!(*env)->ExceptionCheck(env) && success && !finish_success && check_md5 != JNI_FALSE)
    {
        throw_decode_exception(env,
                               "FLAC MD5 verification failed: decoded PCM does not match the STREAMINFO signature.");
    }

    if (!(*env)->ExceptionCheck(env) && success && !finish_success)
    {
        char buffer[JFLAC_MESSAGE_BUFFER_SIZE];
        snprintf(buffer, sizeof(buffer), "FLAC channel decoding failed while finishing%s%s.",
                 state != NULL ? ": " : "", state != NULL ? state : "");
        throw_decode_exception(env, buffer);
    }

    if (!(*env)->ExceptionCheck(env) && success && finish_success)
    {
        /*
         * onComplete is emitted only after libFLAC reports a clean finish. If
         * Java throws from onComplete, the pending exception is preserved while
         * native cleanup below still runs.
         */
        (*env)->CallVoidMethod(env, consumer, context.on_complete);
        (void)(*env)->ExceptionCheck(env);
    }

    /*
     * The channel global reference is temporary for one-shot channel decode.
     * Reusable channel sessions keep their reference in DecodeSession instead.
     */
    g_flac_api.stream_decoder_delete(decoder);
    clear_decode_seekable_channel(env, &context);
}

/*
 * Decodes a whole FLAC file and emits STREAMINFO plus PCM callbacks. This is
 * the simplest one-shot path and uses libFLAC's file initialiser.
 */
JNIEXPORT void JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_decodeFile(JNIEnv *env, jclass clazz, jstring path,
                                                                                jint container, jboolean check_md5,
                                                                                jboolean decode_chained_ogg,
                                                                                jobject consumer)
{
    (void)clazz;
    decode_file_internal(env, path, container, check_md5, decode_chained_ogg, 0, 0, consumer,
                         JFLAC_EMIT_METADATA_CALLBACKS,
                         JFLAC_UNLIMITED_RANGE);
}

/*
 * Decodes a whole FLAC stream and emits STREAMINFO plus PCM callbacks.
 * Sequential InputStream has no seek/tell callbacks, so range and reusable
 * session operations are intentionally not routed here.
 */
JNIEXPORT void JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_decodeStream(JNIEnv *env, jclass clazz,
                                                                                  jobject input_stream, jint container,
                                                                                  jboolean check_md5,
                                                                                  jboolean decode_chained_ogg,
                                                                                  jobject consumer)
{
    (void)clazz;
    decode_stream_internal(env, input_stream, container, check_md5, decode_chained_ogg, consumer);
}

/*
 * Decodes a whole seekable channel and emits STREAMINFO plus PCM callbacks.
 * The channel callback set includes read, seek, tell, length, and EOF.
 */
JNIEXPORT void JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_decodeChannel(JNIEnv *env, jclass clazz,
                                                                                   jobject channel, jint container,
                                                                                   jboolean check_md5,
                                                                                   jboolean decode_chained_ogg,
                                                                                   jobject consumer)
{
    (void)clazz;
    decode_channel_internal(env, channel, container, check_md5, decode_chained_ogg, 0, 0, consumer,
                            JFLAC_EMIT_METADATA_CALLBACKS, JFLAC_UNLIMITED_RANGE);
}

/*
 * Decodes from an absolute sample frame to end-of-stream. STREAMINFO is read
 * before seeking so out-of-range requests can be reported consistently.
 */
JNIEXPORT void JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_decodeFileFrom(JNIEnv *env, jclass clazz,
                                                                                    jstring path, jint container,
                                                                                    jlong first_sample, jobject consumer)
{
    (void)clazz;
    decode_file_internal(env, path, container, JNI_FALSE, JNI_FALSE, first_sample, 0, consumer,
                         JFLAC_SUPPRESS_METADATA_CALLBACKS, JFLAC_UNLIMITED_RANGE);
}

/*
 * Decodes from an absolute sample frame to end-of-stream from a seekable
 * channel. This mirrors file seek decode but translates libFLAC byte offsets
 * through SeekableByteChannel callbacks.
 */
JNIEXPORT void JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_decodeChannelFrom(JNIEnv *env, jclass clazz,
                                                                                       jobject channel,
                                                                                       jint container, jlong first_sample,
                                                                                       jobject consumer)
{
    (void)clazz;
    decode_channel_internal(env, channel, container, JNI_FALSE, JNI_FALSE, first_sample, 0, consumer,
                            JFLAC_SUPPRESS_METADATA_CALLBACKS, JFLAC_UNLIMITED_RANGE);
}

/*
 * Decodes a bounded frame range from a file. maxFrames counts sample frames,
 * meaning one value per channel, not interleaved integer sample count.
 */
JNIEXPORT void JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_decodeFileRange(JNIEnv *env, jclass clazz,
                                                                                     jstring path, jint container,
                                                                                     jlong first_sample,
                                                                                     jlong max_frames, jobject consumer)
{
    (void)clazz;
    decode_file_internal(env, path, container, JNI_FALSE, JNI_FALSE, first_sample, max_frames, consumer,
                         JFLAC_SUPPRESS_METADATA_CALLBACKS, JFLAC_LIMITED_RANGE);
}

/*
 * Decodes a bounded frame range from a seekable channel. The write callback
 * crops the final decoded libFLAC block so Java receives exactly the requested
 * number of frames unless EOF is reached first.
 */
JNIEXPORT void JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_decodeChannelRange(
    JNIEnv *env, jclass clazz, jobject channel, jint container, jlong first_sample, jlong max_frames, jobject consumer)
{
    (void)clazz;
    decode_channel_internal(env, channel, container, JNI_FALSE, JNI_FALSE, first_sample, max_frames, consumer,
                            JFLAC_SUPPRESS_METADATA_CALLBACKS, JFLAC_LIMITED_RANGE);
}

/*
 * Opens a reusable file decoder session and reads STREAMINFO once so later
 * range decodes can avoid duplicate metadata callbacks. Unlike encoder handles,
 * decoder sessions are registered in a handle table so stale Java handles can
 * be rejected without treating arbitrary long values as pointers.
 */
JNIEXPORT jlong JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_openDecoderFile(JNIEnv *env, jclass clazz,
                                                                                      jstring path, jint container)
{
    (void)clazz;
    if (!flac_api_ready(env))
    {
        return 0;
    }

    if (!validate_container(env, container))
    {
        return 0;
    }

    if (path == NULL)
    {
        throw_illegal_argument_exception(env, "Decoder path must not be null.");
        return 0;
    }

    char *utf8_path = jstring_to_utf8(env, path);
    if (utf8_path == NULL)
    {
        throw_decode_exception(env, "Failed to encode file path to UTF-8.");
        return 0;
    }

    DecodeSession *session = (DecodeSession *)calloc(1u, sizeof(DecodeSession));
    if (session == NULL)
    {
        free(utf8_path);
        throw_decode_exception(env, "Failed to allocate FLAC decoder session.");
        return 0;
    }

    session->decoder = g_flac_api.stream_decoder_new();
    if (session->decoder == NULL)
    {
        destroy_decode_session(env, session);
        free(utf8_path);
        throw_decode_exception(env, "Failed to allocate FLAC decoder.");
        return 0;
    }

    session->context.env = env;
    session->context.consumer = NULL;
    session->context.suppress_metadata = JFLAC_SUPPRESS_METADATA_CALLBACKS;
    /*
     * The reusable decoder is initialised with the same callbacks as one-shot
     * decode, but there is no Java consumer during open. Metadata processing
     * below primes libFLAC for seeking and records STREAMINFO totals in the
     * context; Kotlin separately exposes cached STREAMINFO to users on each
     * session decode.
     */
    FLAC__StreamDecoderInitStatus init_status = init_file_decoder_for_container(
        session->decoder, utf8_path, decode_write_callback, decode_metadata_callback, decode_error_callback,
        &session->context, container);
    free(utf8_path);

    if (init_status != FLAC__STREAM_DECODER_INIT_STATUS_OK)
    {
        destroy_decode_session(env, session);
        throw_decode_exception(env, "Failed to initialize FLAC decoder.");
        return 0;
    }

    /*
     * Stop after metadata during open. Calling finish() here would close the
     * decoder we want to reuse, so successful sessions remain alive in a
     * metadata-ready state and later decode calls start by seeking.
     */
    FLAC__bool metadata_success = g_flac_api.stream_decoder_process_until_end_of_metadata(session->decoder);
    if (!metadata_success)
    {
        const char *state = g_flac_api.stream_decoder_get_resolved_state_string(session->decoder);
        char buffer[JFLAC_MESSAGE_BUFFER_SIZE];
        snprintf(buffer, sizeof(buffer), "FLAC decoder session failed while reading metadata%s%s%s%s%s.",
                 state != NULL ? ": " : "", state != NULL ? state : "",
                 session->context.saw_error ? " (decoder error callback: " : "",
                 session->context.saw_error ? decoder_error_status_name(session->context.last_error_status) : "",
                 session->context.saw_error ? ")" : "");
        destroy_decode_session(env, session);
        throw_decode_exception(env, buffer);
        return 0;
    }

    /*
     * Register only after metadata has been read successfully. Before this
     * point no Java-visible handle exists, so failure can free the session
     * directly without touching the registry.
     */
    jlong handle = register_decode_session(session);
    if (handle == 0)
    {
        destroy_decode_session(env, session);
        throw_decode_exception(env, "Failed to register FLAC decoder session.");
        return 0;
    }

    return handle;
}

/*
 * Opens a reusable seekable-channel decoder session and reads STREAMINFO once
 * so later range decodes can use the same native handle. The channel is held
 * as a global reference because the session can outlive this JNI call.
 */
JNIEXPORT jlong JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_openDecoderChannel(JNIEnv *env, jclass clazz,
                                                                                         jobject channel,
                                                                                         jint container)
{
    (void)clazz;
    if (!flac_api_ready(env))
    {
        return 0;
    }

    if (!validate_container(env, container))
    {
        return 0;
    }

    if (channel == NULL)
    {
        throw_illegal_argument_exception(env, "Decoder seekable channel must not be null.");
        return 0;
    }

    DecodeSession *session = (DecodeSession *)calloc(1u, sizeof(DecodeSession));
    if (session == NULL)
    {
        throw_decode_exception(env, "Failed to allocate FLAC decoder session.");
        return 0;
    }

    session->decoder = g_flac_api.stream_decoder_new();
    if (session->decoder == NULL)
    {
        destroy_decode_session(env, session);
        throw_decode_exception(env, "Failed to allocate FLAC decoder.");
        return 0;
    }

    session->context.env = env;
    session->context.consumer = NULL;
    session->context.suppress_metadata = JFLAC_SUPPRESS_METADATA_CALLBACKS;
    /*
     * prepare_decode_seekable_channel captures channel_base_offset. All later
     * libFLAC byte offsets are relative to the channel position at open time.
     */
    if (!prepare_decode_seekable_channel(env, &session->context, channel))
    {
        destroy_decode_session(env, session);
        return 0;
    }

    FLAC__StreamDecoderInitStatus init_status = init_stream_decoder_for_container(
        session->decoder, decode_read_callback, decode_channel_seek_callback, decode_channel_tell_callback,
        decode_channel_length_callback, decode_channel_eof_callback, decode_write_callback, decode_metadata_callback,
        decode_error_callback, &session->context, container);

    if (init_status != FLAC__STREAM_DECODER_INIT_STATUS_OK)
    {
        destroy_decode_session(env, session);
        throw_decode_exception(env, "Failed to initialize FLAC channel decoder.");
        return 0;
    }

    FLAC__bool metadata_success = g_flac_api.stream_decoder_process_until_end_of_metadata(session->decoder);
    if (!(*env)->ExceptionCheck(env) && !metadata_success)
    {
        const char *state = g_flac_api.stream_decoder_get_resolved_state_string(session->decoder);
        char buffer[JFLAC_MESSAGE_BUFFER_SIZE];
        snprintf(buffer, sizeof(buffer), "FLAC channel decoder session failed while reading metadata%s%s%s%s%s.",
                 state != NULL ? ": " : "", state != NULL ? state : "",
                 session->context.saw_error ? " (decoder error callback: " : "",
                 session->context.saw_error ? decoder_error_status_name(session->context.last_error_status) : "",
                 session->context.saw_error ? ")" : "");
        destroy_decode_session(env, session);
        throw_decode_exception(env, buffer);
        return 0;
    }

    if ((*env)->ExceptionCheck(env) || !metadata_success)
    {
        destroy_decode_session(env, session);
        return 0;
    }

    /*
     * Register only after metadata has been read successfully. Before this
     * point no Java-visible handle exists, so failure can free the session
     * directly without touching the registry.
     */
    jlong handle = register_decode_session(session);
    if (handle == 0)
    {
        destroy_decode_session(env, session);
        throw_decode_exception(env, "Failed to register FLAC channel decoder session.");
        return 0;
    }

    return handle;
}

/*
 * Shared opener for pull decoders. It initialises the chosen libFLAC input
 * target, processes metadata exactly once, then registers the session only
 * after STREAMINFO has been captured. Sequential streams are supported because
 * the same decoder that read metadata is kept alive for later PCM reads.
 */
static jobject open_pull_decoder_internal(JNIEnv *env, char *utf8_path, jobject input_stream, jobject channel,
                                          jint container)
{
    DecodeSession *session = (DecodeSession *)calloc(1u, sizeof(DecodeSession));
    if (session == NULL)
    {
        throw_decode_exception(env, "Failed to allocate FLAC pull decoder session.");
        return NULL;
    }

    session->decoder = g_flac_api.stream_decoder_new();
    if (session->decoder == NULL)
    {
        destroy_decode_session(env, session);
        throw_decode_exception(env, "Failed to allocate FLAC pull decoder.");
        return NULL;
    }

    session->context.env = env;
    session->context.consumer = NULL;
    session->context.suppress_metadata = JFLAC_SUPPRESS_METADATA_CALLBACKS;
    session->context.pull_mode = 1;

    FLAC__StreamDecoderInitStatus init_status = FLAC__STREAM_DECODER_INIT_STATUS_ERROR_OPENING_FILE;
    if (utf8_path != NULL)
    {
        init_status = init_file_decoder_for_container(session->decoder, utf8_path, decode_write_callback,
                                                      decode_metadata_callback, decode_error_callback,
                                                      &session->context, container);
    }
    else if (input_stream != NULL)
    {
        if (!prepare_decode_input_stream_global(env, &session->context, input_stream))
        {
            destroy_decode_session(env, session);
            return NULL;
        }

        init_status = init_stream_decoder_for_container(session->decoder, decode_read_callback, NULL, NULL, NULL, NULL,
                                                        decode_write_callback, decode_metadata_callback,
                                                        decode_error_callback, &session->context, container);
    }
    else if (channel != NULL)
    {
        if (!prepare_decode_seekable_channel(env, &session->context, channel))
        {
            destroy_decode_session(env, session);
            return NULL;
        }

        init_status = init_stream_decoder_for_container(
            session->decoder, decode_read_callback, decode_channel_seek_callback, decode_channel_tell_callback,
            decode_channel_length_callback, decode_channel_eof_callback, decode_write_callback,
            decode_metadata_callback, decode_error_callback, &session->context, container);
    }

    if (init_status != FLAC__STREAM_DECODER_INIT_STATUS_OK)
    {
        destroy_decode_session(env, session);
        throw_decode_exception(env, "Failed to initialize FLAC pull decoder.");
        return NULL;
    }

    /*
     * Only metadata is processed during open. Pull PCM reads later call
     * process_single() one libFLAC frame at a time, so Java back-pressure maps
     * directly to decoder progress without a background thread.
     */
    FLAC__bool metadata_success = g_flac_api.stream_decoder_process_until_end_of_metadata(session->decoder);
    if (!(*env)->ExceptionCheck(env) && !metadata_success)
    {
        const char *state = g_flac_api.stream_decoder_get_resolved_state_string(session->decoder);
        char buffer[JFLAC_MESSAGE_BUFFER_SIZE];
        snprintf(buffer, sizeof(buffer), "FLAC pull decoder failed while reading metadata%s%s%s%s%s.",
                 state != NULL ? ": " : "", state != NULL ? state : "",
                 session->context.saw_error ? " (decoder error callback: " : "",
                 session->context.saw_error ? decoder_error_status_name(session->context.last_error_status) : "",
                 session->context.saw_error ? ")" : "");
        destroy_decode_session(env, session);
        throw_decode_exception(env, buffer);
        return NULL;
    }

    if ((*env)->ExceptionCheck(env) || !metadata_success)
    {
        destroy_decode_session(env, session);
        return NULL;
    }

    if (!session->context.saw_stream_info)
    {
        destroy_decode_session(env, session);
        throw_decode_exception(env, "FLAC pull decoder did not receive STREAMINFO.");
        return NULL;
    }

    jlong handle = register_decode_session(session);
    if (handle == 0)
    {
        destroy_decode_session(env, session);
        throw_decode_exception(env, "Failed to register FLAC pull decoder session.");
        return NULL;
    }

    jobject result = new_pull_decoder_open_result(env, handle, &session->context.stream_info);
    if (result == NULL)
    {
        DecodeSession *registered_session = remove_decode_session(handle);
        destroy_decode_session(env, registered_session);
        return NULL;
    }

    return result;
}

JNIEXPORT jobject JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_openPullDecoderFile(JNIEnv *env, jclass clazz,
                                                                                            jstring path,
                                                                                            jint container)
{
    (void)clazz;
    if (!flac_api_ready(env))
    {
        return NULL;
    }

    if (!validate_container(env, container))
    {
        return NULL;
    }

    if (path == NULL)
    {
        throw_illegal_argument_exception(env, "Decoder path must not be null.");
        return NULL;
    }

    char *utf8_path = jstring_to_utf8(env, path);
    if (utf8_path == NULL)
    {
        throw_decode_exception(env, "Failed to encode file path to UTF-8.");
        return NULL;
    }

    jobject result = open_pull_decoder_internal(env, utf8_path, NULL, NULL, container);
    free(utf8_path);
    return result;
}

JNIEXPORT jobject JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_openPullDecoderStream(JNIEnv *env,
                                                                                              jclass clazz,
                                                                                              jobject input_stream,
                                                                                              jint container)
{
    (void)clazz;
    if (!flac_api_ready(env))
    {
        return NULL;
    }

    if (!validate_container(env, container))
    {
        return NULL;
    }

    if (input_stream == NULL)
    {
        throw_illegal_argument_exception(env, "Decoder input stream must not be null.");
        return NULL;
    }

    return open_pull_decoder_internal(env, NULL, input_stream, NULL, container);
}

JNIEXPORT jobject JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_openPullDecoderChannel(JNIEnv *env,
                                                                                               jclass clazz,
                                                                                               jobject channel,
                                                                                               jint container)
{
    (void)clazz;
    if (!flac_api_ready(env))
    {
        return NULL;
    }

    if (!validate_container(env, container))
    {
        return NULL;
    }

    if (channel == NULL)
    {
        throw_illegal_argument_exception(env, "Decoder seekable channel must not be null.");
        return NULL;
    }

    return open_pull_decoder_internal(env, NULL, NULL, channel, container);
}

/*
 * Decodes from a reusable decoder session to end-of-stream. The session is
 * acquired from the registry, marked in-use, sought to firstSample, then
 * released back to the registry after callbacks finish.
 */
JNIEXPORT void JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_decodeDecoderFrom(JNIEnv *env, jclass clazz,
                                                                                       jlong handle, jlong first_sample,
                                                                                       jobject consumer)
{
    (void)clazz;
    if (!flac_api_ready(env))
    {
        return;
    }

    DecodeSession *session = acquire_decode_session(env, handle);
    if (session == NULL)
    {
        return;
    }

    /*
     * Pull and callback-driven decoders share the opaque handle registry, but
     * they have different callback state machines. In particular, a pull
     * InputStream session owns a global reference stored in DecodeContext.
     * Rejecting the wrong operation before prepare_decode_context() prevents
     * per-call callback setup from disturbing that long-lived transport state.
     */
    if (session->context.pull_mode)
    {
        release_decode_session_reference(env, session);
        throw_illegal_state_exception(env, "A pull decoder handle cannot be used for callback-driven decoding.");
        return;
    }

    /*
     * From here until release_decode_session_reference(), the registry keeps
     * the session in-use. Every early return must release the reference.
     */
    if (first_sample < 0)
    {
        release_decode_session_reference(env, session);
        throw_illegal_argument_exception(env, "First sample must be non-negative.");
        return;
    }

    if (consumer == NULL)
    {
        release_decode_session_reference(env, session);
        throw_illegal_argument_exception(env, "Decoder consumer must not be null.");
        return;
    }

    if (!prepare_decode_context(env, &session->context, consumer))
    {
        release_decode_session_reference(env, session);
        return;
    }

    /*
     * Kotlin already has session STREAMINFO from open. Suppressing metadata
     * here avoids duplicate onStreamInfo calls during repeated session decodes.
     */
    session->context.suppress_metadata = JFLAC_SUPPRESS_METADATA_CALLBACKS;

    /*
     * Reusable session decode deliberately does not call finish() after a
     * successful decode. The session owns one live FLAC__StreamDecoder, and the
     * next decode repositions that same decoder with seek_absolute().
     */
    FLAC__bool seek_success = g_flac_api.stream_decoder_seek_absolute(session->decoder, (FLAC__uint64)first_sample);
    if (!(*env)->ExceptionCheck(env) && !seek_success)
    {
        const char *state = g_flac_api.stream_decoder_get_resolved_state_string(session->decoder);
        char buffer[JFLAC_MESSAGE_BUFFER_SIZE];
        snprintf(buffer, sizeof(buffer), "FLAC decoder session failed while seeking%s%s%s%s%s.",
                 state != NULL ? ": " : "", state != NULL ? state : "",
                 session->context.saw_error ? " (decoder error callback: " : "",
                 session->context.saw_error ? decoder_error_status_name(session->context.last_error_status) : "",
                 session->context.saw_error ? ")" : "");
        /*
         * consumer is a local reference owned by this JNI call. Clear it before
         * returning so a later session operation cannot accidentally observe a
         * reference whose lifetime has ended.
         */
        session->context.consumer = NULL;
        throw_decode_exception(env, buffer);
        release_decode_session_reference(env, session);
        return;
    }

    FLAC__bool success = g_flac_api.stream_decoder_process_until_end_of_stream(session->decoder);
    if (!(*env)->ExceptionCheck(env) && !success)
    {
        const char *state = g_flac_api.stream_decoder_get_resolved_state_string(session->decoder);
        char buffer[JFLAC_MESSAGE_BUFFER_SIZE];
        snprintf(buffer, sizeof(buffer), "FLAC decoder session failed%s%s%s%s%s.", state != NULL ? ": " : "",
                 state != NULL ? state : "", session->context.saw_error ? " (decoder error callback: " : "",
                 session->context.saw_error ? decoder_error_status_name(session->context.last_error_status) : "",
                 session->context.saw_error ? ")" : "");
        /*
         * Clear the local consumer reference on all early exits. The Kotlin
         * session will mark the handle failed after any thrown exception, but
         * direct JNI callers can still trigger this path.
         */
        session->context.consumer = NULL;
        throw_decode_exception(env, buffer);
        release_decode_session_reference(env, session);
        return;
    }

    if (!(*env)->ExceptionCheck(env) && success)
    {
        (*env)->CallVoidMethod(env, consumer, session->context.on_complete);
        (void)(*env)->ExceptionCheck(env);
    }

    /*
     * Do this even when onComplete throws. The pending Java exception is left
     * untouched, while the native context stops retaining a stale local ref.
     */
    session->context.consumer = NULL;
    release_decode_session_reference(env, session);
}

/*
 * Decodes a bounded range through a reusable decoder session. This is the
 * seek-heavy path optimised for repeated ranges over the same seekable source.
 */
JNIEXPORT void JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_decodeDecoderRange(
    JNIEnv *env, jclass clazz, jlong handle, jlong first_sample, jlong max_frames, jobject consumer)
{
    (void)clazz;
    if (!flac_api_ready(env))
    {
        return;
    }

    DecodeSession *session = acquire_decode_session(env, handle);
    if (session == NULL)
    {
        return;
    }

    if (session->context.pull_mode)
    {
        release_decode_session_reference(env, session);
        throw_illegal_state_exception(env, "A pull decoder handle cannot be used for callback-driven decoding.");
        return;
    }

    /*
     * Bounded session decode has more early exits than unbounded decode. Keep
     * all validation after acquisition paired with release_decode_session_reference().
     */
    if (!validate_decode_request(env, first_sample, JFLAC_LIMITED_RANGE, max_frames))
    {
        release_decode_session_reference(env, session);
        return;
    }

    if (consumer == NULL)
    {
        release_decode_session_reference(env, session);
        throw_illegal_argument_exception(env, "Decoder consumer must not be null.");
        return;
    }

    if (!prepare_decode_context(env, &session->context, consumer))
    {
        release_decode_session_reference(env, session);
        return;
    }

    session->context.suppress_metadata = JFLAC_SUPPRESS_METADATA_CALLBACKS;
    /*
     * configure_decode_range() also handles the zero-length range case by
     * marking range_complete before any seek is attempted.
     */
    configure_decode_range(&session->context, JFLAC_LIMITED_RANGE, max_frames);

    /*
     * The session already processed STREAMINFO at open time, so bounded decode
     * can decide empty/past-EOF cases before seeking. This gives Java stable
     * empty summaries for EOF ranges and a controlled exception for past-EOF
     * ranges without disturbing the reusable decoder state.
     */
    if (decode_range_starts_after_stream(&session->context, first_sample))
    {
        session->context.consumer = NULL;
        throw_range_after_stream_exception(env);
        release_decode_session_reference(env, session);
        return;
    }

    if (session->context.range_complete ||
        decode_range_starts_at_stream_end(&session->context, first_sample))
    {
        if (!(*env)->ExceptionCheck(env))
        {
            (*env)->CallVoidMethod(env, consumer, session->context.on_complete);
            (void)(*env)->ExceptionCheck(env);
        }

        /*
         * No seek or finish is needed for a successful empty range. Keeping the
         * decoder open preserves the session for the caller's next range.
         */
        session->context.consumer = NULL;
        release_decode_session_reference(env, session);
        return;
    }

    /*
     * For a non-empty bounded range, seek to the first requested frame and then
     * process one FLAC frame at a time. decode_write_callback crops the final
     * callback to maxFrames and process_decode_stream stops immediately after
     * that cropped callback succeeds.
     */
    FLAC__bool seek_success = g_flac_api.stream_decoder_seek_absolute(session->decoder, (FLAC__uint64)first_sample);
    if (!(*env)->ExceptionCheck(env) && !seek_success)
    {
        const char *state = g_flac_api.stream_decoder_get_resolved_state_string(session->decoder);
        char buffer[JFLAC_MESSAGE_BUFFER_SIZE];
        snprintf(buffer, sizeof(buffer), "FLAC decoder session failed while seeking%s%s%s%s%s.",
                 state != NULL ? ": " : "", state != NULL ? state : "",
                 session->context.saw_error ? " (decoder error callback: " : "",
                 session->context.saw_error ? decoder_error_status_name(session->context.last_error_status) : "",
                 session->context.saw_error ? ")" : "");
        session->context.consumer = NULL;
        throw_decode_exception(env, buffer);
        release_decode_session_reference(env, session);
        return;
    }

    FLAC__bool success = process_decode_stream(session->decoder, &session->context);
    if (!(*env)->ExceptionCheck(env) && !success)
    {
        const char *state = g_flac_api.stream_decoder_get_resolved_state_string(session->decoder);
        char buffer[JFLAC_MESSAGE_BUFFER_SIZE];
        snprintf(buffer, sizeof(buffer), "FLAC decoder session failed%s%s%s%s%s.", state != NULL ? ": " : "",
                 state != NULL ? state : "", session->context.saw_error ? " (decoder error callback: " : "",
                 session->context.saw_error ? decoder_error_status_name(session->context.last_error_status) : "",
                 session->context.saw_error ? ")" : "");
        session->context.consumer = NULL;
        throw_decode_exception(env, buffer);
        release_decode_session_reference(env, session);
        return;
    }

    if (!(*env)->ExceptionCheck(env) && success)
    {
        (*env)->CallVoidMethod(env, consumer, session->context.on_complete);
        (void)(*env)->ExceptionCheck(env);
    }

    /*
     * As above, never leave a JNI local reference in the reusable context after
     * the call has returned, regardless of whether onComplete succeeded.
     */
    session->context.consumer = NULL;
    release_decode_session_reference(env, session);
}

/*
 * Pulls up to max_frames PCM frames from a live pull decoder. This call may
 * return fewer frames than requested: libFLAC exposes whole compressed-frame
 * boundaries, Java may ask for a small buffer, and end-of-stream can arrive
 * before the request is full. A positive return always means progress; -1
 * means EOF was already reached with no pending PCM left. Zero is reserved for
 * a zero-frame Java request; if libFLAC or a Java source produces a no-progress
 * state for a positive request, the callback path turns that into an exception
 * instead of spinning.
 */
JNIEXPORT jint JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_readPullDecoderInterleaved(
    JNIEnv *env, jclass clazz, jlong handle, jintArray samples, jint max_frames)
{
    (void)clazz;
    if (!flac_api_ready(env))
    {
        return 0;
    }

    DecodeSession *session = acquire_decode_session(env, handle);
    if (session == NULL)
    {
        return 0;
    }

    if (max_frames < 0)
    {
        release_decode_session_reference(env, session);
        throw_illegal_argument_exception(env, "Max frames must be non-negative.");
        return 0;
    }

    if (samples == NULL)
    {
        release_decode_session_reference(env, session);
        throw_illegal_argument_exception(env, "PCM sample buffer must not be null.");
        return 0;
    }

    if (max_frames == 0)
    {
        release_decode_session_reference(env, session);
        return 0;
    }

    if (!session->context.pull_mode || !session->context.saw_stream_info)
    {
        release_decode_session_reference(env, session);
        throw_illegal_state_exception(env, "The native FLAC decoder handle is not a pull decoder.");
        return 0;
    }

    uint32_t channels = session->context.stream_info.channels;
    uint64_t required_samples = (uint64_t)(uint32_t)max_frames * (uint64_t)channels;
    jsize sample_capacity = (*env)->GetArrayLength(env, samples);
    if (channels == 0u || required_samples > (uint64_t)INT_MAX ||
        (uint64_t)sample_capacity < required_samples)
    {
        release_decode_session_reference(env, session);
        throw_illegal_argument_exception(env, "Interleaved sample buffer must fit maxFrames * channels.");
        return 0;
    }

    DecodeContext *context = &session->context;
    context->env = env;
    context->saw_error = 0;
    context->pull_output = samples;
    context->pull_output_capacity_samples = sample_capacity;
    context->pull_channels = channels;
    context->pull_requested_frames = (uint32_t)max_frames;
    context->pull_written_frames = 0u;

    if (!drain_decode_pull_pending(env, context))
    {
        context->pull_output = NULL;
        release_decode_session_reference(env, session);
        return 0;
    }

    while (context->pull_written_frames < context->pull_requested_frames && !context->pull_end_of_stream)
    {
        FLAC__bool success = g_flac_api.stream_decoder_process_single(session->decoder);
        if ((*env)->ExceptionCheck(env))
        {
            context->pull_output = NULL;
            release_decode_session_reference(env, session);
            return 0;
        }

        if (!success)
        {
            const char *state = g_flac_api.stream_decoder_get_resolved_state_string(session->decoder);
            char buffer[JFLAC_MESSAGE_BUFFER_SIZE];
            snprintf(buffer, sizeof(buffer), "FLAC pull decoder failed%s%s%s%s%s.", state != NULL ? ": " : "",
                     state != NULL ? state : "", context->saw_error ? " (decoder error callback: " : "",
                     context->saw_error ? decoder_error_status_name(context->last_error_status) : "",
                     context->saw_error ? ")" : "");
            context->pull_output = NULL;
            release_decode_session_reference(env, session);
            throw_decode_exception(env, buffer);
            return 0;
        }

        FLAC__StreamDecoderState state = g_flac_api.stream_decoder_get_state(session->decoder);
        if (state == FLAC__STREAM_DECODER_END_OF_STREAM)
        {
            context->pull_end_of_stream = 1;
            break;
        }

        if (state == FLAC__STREAM_DECODER_ABORTED)
        {
            const char *state_text = g_flac_api.stream_decoder_get_resolved_state_string(session->decoder);
            char buffer[JFLAC_MESSAGE_BUFFER_SIZE];
            snprintf(buffer, sizeof(buffer), "FLAC pull decoder aborted%s%s.", state_text != NULL ? ": " : "",
                     state_text != NULL ? state_text : "");
            context->pull_output = NULL;
            release_decode_session_reference(env, session);
            throw_decode_exception(env, buffer);
            return 0;
        }
    }

    jint written_frames = (jint)context->pull_written_frames;
    int end_of_stream = context->pull_end_of_stream;
    context->pull_output = NULL;
    release_decode_session_reference(env, session);

    if (written_frames > 0)
    {
        return written_frames;
    }

    return end_of_stream ? (jint)-1 : (jint)0;
}

/*
 * Releases a reusable decoder session handle. Invalid or stale handles are a
 * no-op here so close paths can be called defensively; active decodes keep the
 * session alive through reference_count until their callback stack unwinds.
 */
JNIEXPORT void JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_releaseDecoder(JNIEnv *env, jclass clazz,
                                                                                    jlong handle)
{
    (void)clazz;
    DecodeSession *session = remove_decode_session(handle);
    destroy_decode_session(env, session);
}

JNIEXPORT void JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_releasePullDecoder(JNIEnv *env, jclass clazz,
                                                                                        jlong handle)
{
    Java_org_zzvsjs_jflac_internal_NativeBindings_releaseDecoder(env, clazz, handle);
}

JNIEXPORT jlong JNICALL
Java_org_zzvsjs_jflac_internal_NativeBindings_activeDecoderSessionCount(JNIEnv *env, jclass clazz)
{
    (void)env;
    (void)clazz;
    return active_decode_session_count();
}

typedef struct EncodeContext
{
    /*
     * EncodeContext is stored behind a process-local registry handle. It owns the
     * FLAC__StreamEncoder, any metadata blocks not yet handed off to libFLAC,
     * callback target references, and the channel count used to validate PCM
     * writes.
     */
    FLAC__StreamEncoder *encoder;
    FLAC__StreamMetadata **metadata_blocks;
    uint32_t metadata_count;
    uint32_t channels;

    /*
     * OutputStream encoding keeps a global reference because libFLAC invokes
     * write callbacks across open, write, and finish JNI calls.
     */
    JavaVM *jvm;
    jobject output_stream;
    jmethodID output_write;
    jmethodID output_flush;

    /*
     * SeekableByteChannel encoding uses write plus seek/tell callbacks so
     * libFLAC can patch final STREAMINFO fields after PCM frames are written.
     */
    jobject output_channel;
    jmethodID channel_read;
    jmethodID channel_write;
    jmethodID channel_position;
    jmethodID channel_seek;
    jlong channel_base_offset;

    /*
     * Registry state mirrors decoder sessions: Java sees only a small positive
     * handle, and native code rejects concurrent write/finish calls before
     * libFLAC's stateful encoder can be re-entered.
     */
    jlong handle;
    int reference_count;
    int released;
    int in_use;
} EncodeContext;

typedef struct EncodeContextRegistryEntry
{
    jlong handle;
    EncodeContext *context;
    struct EncodeContextRegistryEntry *next;
} EncodeContextRegistryEntry;

static JflacMutex g_encode_context_registry_lock = JFLAC_MUTEX_INITIALIZER;
static EncodeContextRegistryEntry *g_encode_context_registry = NULL;
static jlong g_next_encode_context_handle = JFLAC_FIRST_VALID_SESSION_HANDLE;

/*
 * Deletes all libFLAC metadata blocks owned by an encoder context. Blocks set
 * to NULL have already been transferred to another owner, such as a metadata
 * chain insert, and must not be deleted here.
 */
static void destroy_metadata_blocks(EncodeContext *context)
{
    if (context == NULL || context->metadata_blocks == NULL)
    {
        return;
    }

    for (uint32_t i = 0; i < context->metadata_count; ++i)
    {
        if (context->metadata_blocks[i] != NULL)
        {
            g_flac_api.metadata_object_delete(context->metadata_blocks[i]);
        }
    }

    free(context->metadata_blocks);
    context->metadata_blocks = NULL;
    context->metadata_count = 0;
}

/*
 * Destroys the encoder object, metadata blocks, global callback references,
 * and heap context. It is used by both normal finish and failure/abandon paths,
 * so every field must be safe to clean up after partial initialisation.
 */
static void destroy_encode_context(JNIEnv *env, EncodeContext *context)
{
    if (context == NULL)
    {
        return;
    }

    if (context->encoder != NULL)
    {
        g_flac_api.stream_encoder_delete(context->encoder);
        context->encoder = NULL;
    }

    destroy_metadata_blocks(context);

    if (env != NULL && context->output_stream != NULL)
    {
        (*env)->DeleteGlobalRef(env, context->output_stream);
        context->output_stream = NULL;
    }

    if (env != NULL && context->output_channel != NULL)
    {
        (*env)->DeleteGlobalRef(env, context->output_channel);
        context->output_channel = NULL;
    }

    free(context);
}

static void lock_encode_context_registry(void)
{
    jflac_mutex_lock(&g_encode_context_registry_lock);
}

static void unlock_encode_context_registry(void)
{
    jflac_mutex_unlock(&g_encode_context_registry_lock);
}

static EncodeContextRegistryEntry *find_encode_context_entry_locked(jlong handle,
                                                                    EncodeContextRegistryEntry **previous)
{
    EncodeContextRegistryEntry *prev = NULL;
    EncodeContextRegistryEntry *entry = g_encode_context_registry;
    while (entry != NULL)
    {
        if (entry->handle == handle)
        {
            if (previous != NULL)
            {
                *previous = prev;
            }

            return entry;
        }

        prev = entry;
        entry = entry->next;
    }

    if (previous != NULL)
    {
        *previous = NULL;
    }

    return NULL;
}

static jlong reserve_encode_context_handle_locked(void)
{
    for (;;)
    {
        jlong handle = g_next_encode_context_handle++;
        if (g_next_encode_context_handle <= 0)
        {
            g_next_encode_context_handle = JFLAC_FIRST_VALID_SESSION_HANDLE;
        }

        if (handle > 0 && find_encode_context_entry_locked(handle, NULL) == NULL)
        {
            return handle;
        }
    }
}

static jlong register_encode_context(EncodeContext *context)
{
    EncodeContextRegistryEntry *entry = (EncodeContextRegistryEntry *)calloc(1u, sizeof(EncodeContextRegistryEntry));
    if (entry == NULL)
    {
        return 0;
    }

    lock_encode_context_registry();
    jlong handle = reserve_encode_context_handle_locked();
    context->handle = handle;
    context->reference_count = 0;
    context->released = 0;
    context->in_use = 0;
    entry->handle = handle;
    entry->context = context;
    entry->next = g_encode_context_registry;
    g_encode_context_registry = entry;
    unlock_encode_context_registry();
    return handle;
}

static EncodeContext *acquire_encode_context(JNIEnv *env, jlong handle)
{
    EncodeContext *context = NULL;

    if (handle > 0)
    {
        lock_encode_context_registry();
        EncodeContextRegistryEntry *entry = find_encode_context_entry_locked(handle, NULL);
        EncodeContext *candidate = entry != NULL ? entry->context : NULL;
        if (candidate != NULL && !candidate->released && !candidate->in_use &&
            candidate->encoder != NULL)
        {
            context = candidate;
            context->reference_count += 1;
            context->in_use = 1;
        }

        unlock_encode_context_registry();
    }

    if (context == NULL)
    {
        throw_illegal_state_exception(env, "The native FLAC encoder handle is not active.");
    }

    return context;
}

static void release_encode_context_reference(JNIEnv *env, EncodeContext *context)
{
    int should_destroy = 0;

    if (context == NULL)
    {
        return;
    }

    lock_encode_context_registry();
    if (context->reference_count > 0)
    {
        context->reference_count -= 1;
    }

    context->in_use = 0;
    should_destroy = context->released && context->reference_count == 0;
    unlock_encode_context_registry();

    if (should_destroy)
    {
        destroy_encode_context(env, context);
    }
}

static EncodeContext *remove_encode_context(jlong handle)
{
    EncodeContext *context_to_destroy = NULL;

    if (handle <= 0)
    {
        return NULL;
    }

    lock_encode_context_registry();
    EncodeContextRegistryEntry *previous = NULL;
    EncodeContextRegistryEntry *entry = find_encode_context_entry_locked(handle, &previous);
    if (entry != NULL)
    {
        EncodeContext *context = entry->context;
        if (previous == NULL)
        {
            g_encode_context_registry = entry->next;
        }
        else
        {
            previous->next = entry->next;
        }

        free(entry);

        if (context != NULL)
        {
            context->released = 1;
            if (context->reference_count == 0)
            {
                context_to_destroy = context;
            }
        }
    }

    unlock_encode_context_registry();

    return context_to_destroy;
}

/*
 * Detaches and destroys every encoder retained by the opaque-handle registry.
 * JNI_OnUnload calls this only after JNI work has stopped, but destruction is
 * still performed outside the registry lock so callback/global-reference
 * cleanup never runs while shared registry state is locked.
 */
static void destroy_all_encode_contexts(JNIEnv *env)
{
    lock_encode_context_registry();
    EncodeContextRegistryEntry *entry = g_encode_context_registry;
    g_encode_context_registry = NULL;
    unlock_encode_context_registry();
    while (entry != NULL)
    {
        EncodeContextRegistryEntry *next = entry->next;
        EncodeContext *context = entry->context;
        free(entry);
        destroy_encode_context(env, context);
        entry = next;
    }
}

/*
 * Returns the number of Java-visible encoder handles currently owned by the
 * registry. It is a package-private diagnostic used by lifecycle regression
 * tests and does not alter encoder state.
 */
static jlong active_encode_context_count(void)
{
    jlong count = 0;

    lock_encode_context_registry();
    for (EncodeContextRegistryEntry *entry = g_encode_context_registry;
         entry != NULL;
         entry = entry->next)
    {
        count += 1;
    }

    unlock_encode_context_registry();
    return count;
}

static void destroy_registry_locks(void)
{
    jflac_mutex_destroy(&g_decode_session_registry_lock);
    jflac_mutex_destroy(&g_encode_context_registry_lock);
    jflac_mutex_destroy(&g_flac_init_once.mutex);
}

/*
 * Builds one Vorbis comment metadata block from flattened KEY=value strings.
 * Every flattened entry remains owned by this JNI library. libFLAC receives
 * copy=true so its metadata block stores data allocated by its own runtime,
 * which is required when the two Windows DLLs use separate static CRT heaps.
 */
static FLAC__bool build_vorbis_comment_block(JNIEnv *env, jstring vendor_object, jobjectArray comment_entries,
                                             FLAC__bool force_create, FLAC__StreamMetadata **result)
{
    *result = NULL;
    jsize entry_count = comment_entries != NULL ? (*env)->GetArrayLength(env, comment_entries) : 0;
    if (!force_create && vendor_object == NULL && entry_count == 0)
    {
        return true;
    }

    FLAC__StreamMetadata *block = g_flac_api.metadata_object_new(FLAC__METADATA_TYPE_VORBIS_COMMENT);
    if (block == NULL)
    {
        return false;
    }

    if (vendor_object != NULL)
    {
        char *vendor = jstring_to_utf8(env, vendor_object);
        jboolean vendor_conversion_threw = (*env)->ExceptionCheck(env);
        if (vendor == NULL || vendor_conversion_threw)
        {
            free(vendor);
            g_flac_api.metadata_object_delete(block);
            return false;
        }

        FLAC__StreamMetadata_VorbisComment_Entry vendor_entry;
        memset(&vendor_entry, 0, sizeof(vendor_entry));
        vendor_entry.length = (FLAC__uint32)strlen(vendor);
        vendor_entry.entry = (FLAC__byte *)vendor;
        /*
         * copy=true asks libFLAC to duplicate vendor_entry.entry. The temporary
         * UTF-8 buffer can therefore be freed immediately after the setter.
         */
        FLAC__bool vendor_success =
            g_flac_api.metadata_object_vorbiscomment_set_vendor_string(block, vendor_entry, true);
        free(vendor);
        if (!vendor_success)
        {
            g_flac_api.metadata_object_delete(block);
            return false;
        }
    }

    for (jsize i = 0; i < entry_count; ++i)
    {
        if ((*env)->PushLocalFrame(env, 1) < 0)
        {
            g_flac_api.metadata_object_delete(block);
            return false;
        }

        jstring entry_string = (jstring)(*env)->GetObjectArrayElement(env, comment_entries, i);
        if ((*env)->ExceptionCheck(env))
        {
            (*env)->PopLocalFrame(env, NULL);
            g_flac_api.metadata_object_delete(block);
            return false;
        }

        char *utf8_entry = jstring_to_utf8(env, entry_string);
        jboolean conversion_threw = (*env)->ExceptionCheck(env);
        if (utf8_entry == NULL || conversion_threw)
        {
            free(utf8_entry);
            (*env)->PopLocalFrame(env, NULL);
            g_flac_api.metadata_object_delete(block);
            return false;
        }

        size_t entry_length = strlen(utf8_entry);
        char *normalised_entry = NULL;
        const char *entry_to_append = utf8_entry;
        FLAC__StreamMetadata_VorbisComment_Entry native_entry;
        memset(&native_entry, 0, sizeof(native_entry));

        /*
         * The public API historically accepts a missing '=' as an empty value.
         * Preserve that contract by presenting KEY= to libFLAC. The flattened
         * buffer remains JNI-owned regardless of which representation is used.
         */
        if (strchr(utf8_entry, '=') == NULL)
        {
            if (entry_length > (size_t)UINT32_MAX - 1u ||
                entry_length > SIZE_MAX - 2u)
            {
                free(utf8_entry);
                (*env)->PopLocalFrame(env, NULL);
                g_flac_api.metadata_object_delete(block);
                return false;
            }

            normalised_entry = (char *)malloc(entry_length + 2u);
            if (normalised_entry == NULL)
            {
                free(utf8_entry);
                (*env)->PopLocalFrame(env, NULL);
                g_flac_api.metadata_object_delete(block);
                return false;
            }

            memcpy(normalised_entry, utf8_entry, entry_length);
            normalised_entry[entry_length] = '=';
            normalised_entry[entry_length + 1u] = '\0';
            entry_to_append = normalised_entry;
            entry_length += 1u;
        }
        else if (entry_length > (size_t)UINT32_MAX)
        {
            free(utf8_entry);
            (*env)->PopLocalFrame(env, NULL);
            g_flac_api.metadata_object_delete(block);
            return false;
        }

        native_entry.length = (FLAC__uint32)entry_length;
        native_entry.entry = (FLAC__byte *)entry_to_append;

        /*
         * copy=true keeps heap ownership within each DLL: libFLAC stores its
         * own copy, while this shim always frees only its temporary buffers.
         */
        FLAC__bool append_success =
            g_flac_api.metadata_object_vorbiscomment_append_comment(block, native_entry, true);
        free(normalised_entry);
        free(utf8_entry);
        (*env)->PopLocalFrame(env, NULL);

        if (!append_success)
        {
            g_flac_api.metadata_object_delete(block);
            return false;
        }
    }

    *result = block;
    return true;
}

/*
 * Builds one libFLAC picture metadata block from the public FlacPicture model.
 * The metadata_object_picture_set_* calls use copy=true for string and image
 * payloads, which lets this helper free temporary UTF-8/data buffers before
 * returning the block to the caller.
 */
static FLAC__bool build_picture_block(JNIEnv *env, jobject picture_object, FLAC__StreamMetadata **result)
{
    *result = NULL;
    if (picture_object == NULL)
    {
        return true;
    }

    jclass picture_class = (*env)->GetObjectClass(env, picture_object);
    if (picture_class == NULL)
    {
        return false;
    }

    jmethodID get_type = (*env)->GetMethodID(env, picture_class, "getType", "()I");
    if (get_type == NULL)
    {
        return false;
    }

    jmethodID get_mime_type = (*env)->GetMethodID(env, picture_class, "getMimeType", "()Ljava/lang/String;");
    if (get_mime_type == NULL)
    {
        return false;
    }

    jmethodID get_description = (*env)->GetMethodID(env, picture_class, "getDescription", "()Ljava/lang/String;");
    if (get_description == NULL)
    {
        return false;
    }

    jmethodID get_width = (*env)->GetMethodID(env, picture_class, "getWidth", "()I");
    if (get_width == NULL)
    {
        return false;
    }

    jmethodID get_height = (*env)->GetMethodID(env, picture_class, "getHeight", "()I");
    if (get_height == NULL)
    {
        return false;
    }

    jmethodID get_depth = (*env)->GetMethodID(env, picture_class, "getDepth", "()I");
    if (get_depth == NULL)
    {
        return false;
    }

    jmethodID get_colors = (*env)->GetMethodID(env, picture_class, "getColors", "()I");
    if (get_colors == NULL)
    {
        return false;
    }

    jmethodID get_data = (*env)->GetMethodID(env, picture_class, "getData", "()[B");
    if (get_data == NULL)
    {
        return false;
    }

    jint type = (*env)->CallIntMethod(env, picture_object, get_type);
    if ((*env)->ExceptionCheck(env))
    {
        return false;
    }

    jint width = (*env)->CallIntMethod(env, picture_object, get_width);
    if ((*env)->ExceptionCheck(env))
    {
        return false;
    }

    jint height = (*env)->CallIntMethod(env, picture_object, get_height);
    if ((*env)->ExceptionCheck(env))
    {
        return false;
    }

    jint depth = (*env)->CallIntMethod(env, picture_object, get_depth);
    if ((*env)->ExceptionCheck(env))
    {
        return false;
    }

    jint colors = (*env)->CallIntMethod(env, picture_object, get_colors);
    if ((*env)->ExceptionCheck(env))
    {
        return false;
    }

    jstring mime_type_object = (jstring)(*env)->CallObjectMethod(env, picture_object, get_mime_type);
    if ((*env)->ExceptionCheck(env))
    {
        return false;
    }

    jstring description_object = (jstring)(*env)->CallObjectMethod(env, picture_object, get_description);
    if ((*env)->ExceptionCheck(env))
    {
        return false;
    }

    jbyteArray data_object = (jbyteArray)(*env)->CallObjectMethod(env, picture_object, get_data);
    if ((*env)->ExceptionCheck(env))
    {
        return false;
    }

    if (mime_type_object == NULL || description_object == NULL || data_object == NULL)
    {
        throw_illegal_argument_exception(env, "PICTURE MIME type, description, and data must not be null.");
        return false;
    }

    FLAC__StreamMetadata *block = g_flac_api.metadata_object_new(FLAC__METADATA_TYPE_PICTURE);
    if (block == NULL)
    {
        return false;
    }

    char *mime_type = jstring_to_utf8(env, mime_type_object);
    jboolean mime_type_conversion_threw = (*env)->ExceptionCheck(env);
    if (mime_type == NULL || mime_type_conversion_threw)
    {
        free(mime_type);
        g_flac_api.metadata_object_delete(block);
        return false;
    }

    char *description = jstring_to_utf8(env, description_object);
    jboolean description_conversion_threw = (*env)->ExceptionCheck(env);
    if (description == NULL || description_conversion_threw)
    {
        free(mime_type);
        free(description);
        g_flac_api.metadata_object_delete(block);
        return false;
    }

    jsize data_length = data_object != NULL ? (*env)->GetArrayLength(env, data_object) : 0;
    FLAC__byte *data = NULL;

    /*
     * Picture data can be large, but libFLAC's setter takes a native pointer.
     * Copy the Java byte array into a temporary native buffer, then ask libFLAC
     * to make its own copy so the temporary buffer can be released immediately.
     */
    if (data_length > 0)
    {
        data = (FLAC__byte *)malloc((size_t)data_length);
        if (data == NULL)
        {
            free(mime_type);
            free(description);
            g_flac_api.metadata_object_delete(block);
            return false;
        }

        (*env)->GetByteArrayRegion(env, data_object, 0, data_length, (jbyte *)data);
        if ((*env)->ExceptionCheck(env))
        {
            free(mime_type);
            free(description);
            free(data);
            g_flac_api.metadata_object_delete(block);
            return false;
        }
    }

    block->data.picture.type = (FLAC__StreamMetadata_Picture_Type)type;
    block->data.picture.width = (FLAC__uint32)width;
    block->data.picture.height = (FLAC__uint32)height;
    block->data.picture.depth = (FLAC__uint32)depth;
    block->data.picture.colors = (FLAC__uint32)colors;

    /*
     * The final boolean argument is libFLAC's copy flag. true means the
     * returned metadata block is self-contained; false with NULL/zero is used
     * only for the valid "empty picture data" case.
     */
    if (!g_flac_api.metadata_object_picture_set_mime_type(block, mime_type, true) ||
        !g_flac_api.metadata_object_picture_set_description(block, (FLAC__byte *)description, true) ||
        !(data_length > 0 ? g_flac_api.metadata_object_picture_set_data(block, data, (FLAC__uint32)data_length, true)
                          : g_flac_api.metadata_object_picture_set_data(block, NULL, 0u, false)))
    {
        free(mime_type);
        free(description);
        free(data);
        g_flac_api.metadata_object_delete(block);
        return false;
    }

    free(mime_type);
    free(description);
    free(data);
    *result = block;
    return true;
}

/*
 * Builds one libFLAC APPLICATION block from the public FlacApplicationBlock
 * model. The application ID is copied into the fixed four-byte field and the
 * payload is attached with libFLAC copy semantics before the temporary buffer
 * is freed.
 */
static FLAC__bool build_application_block(JNIEnv *env, jobject application_object, FLAC__StreamMetadata **result)
{
    *result = NULL;
    if (application_object == NULL)
    {
        throw_illegal_argument_exception(env, "APPLICATION metadata block must not be null.");
        return false;
    }

    jclass application_class = (*env)->GetObjectClass(env, application_object);
    if (application_class == NULL)
    {
        return false;
    }

    jmethodID get_id = (*env)->GetMethodID(env, application_class, "getId", "()[B");
    if (get_id == NULL)
    {
        return false;
    }

    jmethodID get_data = (*env)->GetMethodID(env, application_class, "getData", "()[B");
    if (get_data == NULL)
    {
        return false;
    }

    jbyteArray id_object = (jbyteArray)(*env)->CallObjectMethod(env, application_object, get_id);
    if ((*env)->ExceptionCheck(env))
    {
        return false;
    }

    jbyteArray data_object = (jbyteArray)(*env)->CallObjectMethod(env, application_object, get_data);
    if ((*env)->ExceptionCheck(env))
    {
        return false;
    }

    if (id_object == NULL || data_object == NULL)
    {
        throw_illegal_argument_exception(env, "APPLICATION metadata ID and data must not be null.");
        return false;
    }

    jsize id_length = (*env)->GetArrayLength(env, id_object);
    if (id_length != (jsize)JFLAC_APPLICATION_ID_LENGTH)
    {
        throw_illegal_argument_exception(env, "APPLICATION metadata ID must be exactly four bytes.");
        return false;
    }

    FLAC__StreamMetadata *block = g_flac_api.metadata_object_new(FLAC__METADATA_TYPE_APPLICATION);
    if (block == NULL)
    {
        return false;
    }

    jbyte id[JFLAC_APPLICATION_ID_LENGTH];
    (*env)->GetByteArrayRegion(env, id_object, 0, id_length, id);
    if ((*env)->ExceptionCheck(env))
    {
        g_flac_api.metadata_object_delete(block);
        return false;
    }

    memcpy(block->data.application.id, id, JFLAC_APPLICATION_ID_LENGTH);

    jsize data_length = (*env)->GetArrayLength(env, data_object);
    FLAC__byte *data = NULL;
    /*
     * APPLICATION payload is optional. When it exists, use a temporary native
     * buffer because metadata_object_application_set_data() consumes native
     * memory, not a Java byte array.
     */
    if (data_length > 0)
    {
        data = (FLAC__byte *)malloc((size_t)data_length);
        if (data == NULL)
        {
            g_flac_api.metadata_object_delete(block);
            return false;
        }

        (*env)->GetByteArrayRegion(env, data_object, 0, data_length, (jbyte *)data);
        if ((*env)->ExceptionCheck(env))
        {
            free(data);
            g_flac_api.metadata_object_delete(block);
            return false;
        }
    }

    FLAC__bool success = data_length > 0
                             ? g_flac_api.metadata_object_application_set_data(block, data, (uint32_t)data_length, true)
                             : g_flac_api.metadata_object_application_set_data(block, NULL, 0u, false);
    /*
     * The copy=true branch makes libFLAC own an internal copy. The empty branch
     * passes NULL with copy=false because there is no payload to copy.
     */
    free(data);
    if (!success)
    {
        g_flac_api.metadata_object_delete(block);
        return false;
    }

    *result = block;
    return true;
}

/*
 * Builds one libFLAC PADDING block from the public FlacPaddingBlock model.
 * Padding is only a length field, but the length must still fit the FLAC
 * 24-bit metadata block length.
 */
static FLAC__bool build_padding_block(JNIEnv *env, jobject padding_object, FLAC__StreamMetadata **result)
{
    *result = NULL;
    if (padding_object == NULL)
    {
        throw_illegal_argument_exception(env, "PADDING metadata block must not be null.");
        return false;
    }

    jclass padding_class = (*env)->GetObjectClass(env, padding_object);
    if (padding_class == NULL)
    {
        return false;
    }

    jmethodID get_length = (*env)->GetMethodID(env, padding_class, "getLength", "()I");
    if (get_length == NULL)
    {
        return false;
    }

    jint length = (*env)->CallIntMethod(env, padding_object, get_length);
    if ((*env)->ExceptionCheck(env))
    {
        return false;
    }

    if (length < 0 || (uint32_t)length > JFLAC_METADATA_MAX_BLOCK_LENGTH)
    {
        throw_illegal_argument_exception(env,
                                         "PADDING metadata length must fit the FLAC 24-bit metadata length field.");
        return false;
    }

    FLAC__StreamMetadata *block = g_flac_api.metadata_object_new(FLAC__METADATA_TYPE_PADDING);
    if (block == NULL)
    {
        return false;
    }

    block->length = (uint32_t)length;

    *result = block;
    return true;
}

/*
 * Builds one opaque libFLAC metadata block from the public raw-block model.
 * The source structure borrows a JVM byte-array view only for the duration of
 * metadata_object_clone(). libFLAC therefore allocates and later frees the
 * stored payload within its own DLL, even when Windows uses static CRT heaps.
 */
static FLAC__bool build_unknown_metadata_block(JNIEnv *env, jobject unknown_object, FLAC__StreamMetadata **result)
{
    *result = NULL;
    if (unknown_object == NULL)
    {
        throw_illegal_argument_exception(env, "Unknown metadata block must not be null.");
        return false;
    }

    jclass unknown_class = (*env)->GetObjectClass(env, unknown_object);
    if (unknown_class == NULL)
    {
        return false;
    }

    jmethodID get_type = (*env)->GetMethodID(env, unknown_class, "getType", "()I");
    if (get_type == NULL)
    {
        return false;
    }

    jmethodID get_data = (*env)->GetMethodID(env, unknown_class, "getData", "()[B");
    if (get_data == NULL)
    {
        return false;
    }

    jint type = (*env)->CallIntMethod(env, unknown_object, get_type);
    if ((*env)->ExceptionCheck(env))
    {
        return false;
    }

    jbyteArray data_object = (jbyteArray)(*env)->CallObjectMethod(env, unknown_object, get_data);
    if ((*env)->ExceptionCheck(env))
    {
        return false;
    }

    if (data_object == NULL)
    {
        throw_illegal_argument_exception(env, "Unknown metadata data must not be null.");
        return false;
    }

    if (type < JFLAC_UNKNOWN_METADATA_MIN_TYPE || type > JFLAC_UNKNOWN_METADATA_MAX_TYPE)
    {
        /*
         * Only currently-undefined FLAC metadata types are accepted as
         * "unknown". Defined types have dedicated models and builders.
         */
        throw_illegal_argument_exception(env, "Unknown metadata type must be in the FLAC reserved range 7..126.");
        return false;
    }

    jsize data_length = (*env)->GetArrayLength(env, data_object);
    if (data_length < 0 || (uint32_t)data_length > JFLAC_METADATA_MAX_BLOCK_LENGTH)
    {
        throw_illegal_argument_exception(
            env, "Unknown metadata payload length must fit the FLAC 24-bit metadata length field.");
        return false;
    }

    jbyte *borrowed_data = NULL;
    if (data_length > 0)
    {
        borrowed_data = (*env)->GetByteArrayElements(env, data_object, NULL);
        if (borrowed_data == NULL)
        {
            return false;
        }
    }

    FLAC__StreamMetadata source;
    memset(&source, 0, sizeof(source));
    source.type = (FLAC__MetadataType)type;
    source.length = (uint32_t)data_length;
    source.data.unknown.data = (FLAC__byte *)borrowed_data;

    FLAC__StreamMetadata *block = g_flac_api.metadata_object_clone(&source);
    if (borrowed_data != NULL)
    {
        (*env)->ReleaseByteArrayElements(env, data_object, borrowed_data, JNI_ABORT);
    }

    if (block == NULL)
    {
        return false;
    }

    *result = block;
    return true;
}

/*
 * Builds one libFLAC SEEKTABLE block from the public FlacSeekTable model.
 * Java uses -1 for placeholder points; native code converts that sentinel back
 * to libFLAC's all-bits-set placeholder before legality validation.
 */
static FLAC__bool build_seek_table_block(JNIEnv *env, jobject seek_table_object, FLAC__StreamMetadata **result)
{
    *result = NULL;
    if (seek_table_object == NULL)
    {
        throw_illegal_argument_exception(env, "SEEKTABLE metadata block must not be null.");
        return false;
    }

    jclass table_class = (*env)->GetObjectClass(env, seek_table_object);
    if (table_class == NULL)
    {
        return false;
    }

    jmethodID get_points = (*env)->GetMethodID(env, table_class, "getPoints", "()Ljava/util/List;");
    if (get_points == NULL)
    {
        return false;
    }

    jclass list_class = (*env)->FindClass(env, "java/util/List");
    if (list_class == NULL)
    {
        return false;
    }

    jmethodID list_size = (*env)->GetMethodID(env, list_class, "size", "()I");
    if (list_size == NULL)
    {
        return false;
    }

    jmethodID list_get = (*env)->GetMethodID(env, list_class, "get", "(I)Ljava/lang/Object;");
    if (list_get == NULL)
    {
        return false;
    }

    jclass point_class = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacSeekPoint");
    if (point_class == NULL)
    {
        return false;
    }

    jmethodID get_sample_number = (*env)->GetMethodID(env, point_class, "getSampleNumber", "()J");
    if (get_sample_number == NULL)
    {
        return false;
    }

    jmethodID get_stream_offset = (*env)->GetMethodID(env, point_class, "getStreamOffset", "()J");
    if (get_stream_offset == NULL)
    {
        return false;
    }

    jmethodID get_frame_samples = (*env)->GetMethodID(env, point_class, "getFrameSamples", "()I");
    if (get_frame_samples == NULL)
    {
        return false;
    }

    jobject points = (*env)->CallObjectMethod(env, seek_table_object, get_points);
    if ((*env)->ExceptionCheck(env))
    {
        return false;
    }

    if (points == NULL)
    {
        throw_illegal_argument_exception(env, "SEEKTABLE point list must not be null.");
        return false;
    }

    jint point_count = (*env)->CallIntMethod(env, points, list_size);
    if ((*env)->ExceptionCheck(env))
    {
        return false;
    }

    FLAC__StreamMetadata *block = g_flac_api.metadata_object_new(FLAC__METADATA_TYPE_SEEKTABLE);
    if (block == NULL)
    {
        return false;
    }

    if (!g_flac_api.metadata_object_seektable_resize_points(block, (uint32_t)point_count))
    {
        g_flac_api.metadata_object_delete(block);
        return false;
    }

    for (jint i = 0; i < point_count; ++i)
    {
        jobject point_object = (*env)->CallObjectMethod(env, points, list_get, i);
        if ((*env)->ExceptionCheck(env))
        {
            g_flac_api.metadata_object_delete(block);
            return false;
        }

        if (point_object == NULL)
        {
            throw_illegal_argument_exception(env, "SEEKTABLE point must not be null.");
            g_flac_api.metadata_object_delete(block);
            return false;
        }

        jlong sample_number = (*env)->CallLongMethod(env, point_object, get_sample_number);
        if ((*env)->ExceptionCheck(env))
        {
            (*env)->DeleteLocalRef(env, point_object);
            g_flac_api.metadata_object_delete(block);
            return false;
        }

        jlong stream_offset = (*env)->CallLongMethod(env, point_object, get_stream_offset);
        if ((*env)->ExceptionCheck(env))
        {
            (*env)->DeleteLocalRef(env, point_object);
            g_flac_api.metadata_object_delete(block);
            return false;
        }

        jint frame_samples = (*env)->CallIntMethod(env, point_object, get_frame_samples);
        if ((*env)->ExceptionCheck(env))
        {
            (*env)->DeleteLocalRef(env, point_object);
            g_flac_api.metadata_object_delete(block);
            return false;
        }

        FLAC__StreamMetadata_SeekPoint point;
        /*
         * Java cannot represent FLAC's unsigned UINT64_MAX placeholder in a
         * signed long, so the public model uses -1 and this builder restores
         * the native sentinel before libFLAC validates the seek table.
         */
        point.sample_number = sample_number == JFLAC_SEEKPOINT_PLACEHOLDER_JAVA_VALUE ? JFLAC_SEEKPOINT_PLACEHOLDER
                                                                                      : (FLAC__uint64)sample_number;
        point.stream_offset = (FLAC__uint64)stream_offset;
        point.frame_samples = (uint32_t)frame_samples;
        g_flac_api.metadata_object_seektable_set_point(block, (uint32_t)i, point);
        (*env)->DeleteLocalRef(env, point_object);
    }

    if (!g_flac_api.metadata_object_seektable_is_legal(block))
    {
        throw_encode_exception(env, "SEEKTABLE metadata block is not legal.");
        g_flac_api.metadata_object_delete(block);
        return false;
    }

    *result = block;
    return true;
}

/*
 * Fills one already-resized libFLAC CUESHEET track from the public
 * FlacCueSheetTrack model. The parent block owns the track array; this helper
 * only writes into the requested slot and validates fixed-width ASCII fields
 * before copying them into libFLAC's NUL-padded storage.
 */
static FLAC__bool fill_cue_sheet_track(JNIEnv *env, FLAC__StreamMetadata *block, uint32_t track_index,
                                       jobject track_object)
{
    if (track_object == NULL)
    {
        throw_illegal_argument_exception(env, "CUESHEET track must not be null.");
        return false;
    }

    jclass track_class = (*env)->GetObjectClass(env, track_object);
    if (track_class == NULL)
    {
        return false;
    }

    jmethodID get_offset = (*env)->GetMethodID(env, track_class, "getOffset", "()J");
    if (get_offset == NULL)
    {
        return false;
    }

    jmethodID get_number = (*env)->GetMethodID(env, track_class, "getNumber", "()I");
    if (get_number == NULL)
    {
        return false;
    }

    jmethodID get_isrc = (*env)->GetMethodID(env, track_class, "getIsrc", "()Ljava/lang/String;");
    if (get_isrc == NULL)
    {
        return false;
    }

    jmethodID get_type = (*env)->GetMethodID(env, track_class, "getType", "()I");
    if (get_type == NULL)
    {
        return false;
    }

    jmethodID get_pre_emphasis = (*env)->GetMethodID(env, track_class, "getPreEmphasis", "()Z");
    if (get_pre_emphasis == NULL)
    {
        return false;
    }

    jmethodID get_indices = (*env)->GetMethodID(env, track_class, "getIndices", "()Ljava/util/List;");
    if (get_indices == NULL)
    {
        return false;
    }

    jlong offset = (*env)->CallLongMethod(env, track_object, get_offset);
    if ((*env)->ExceptionCheck(env))
    {
        return false;
    }

    jint number = (*env)->CallIntMethod(env, track_object, get_number);
    if ((*env)->ExceptionCheck(env))
    {
        return false;
    }

    jstring isrc_object = (jstring)(*env)->CallObjectMethod(env, track_object, get_isrc);
    if ((*env)->ExceptionCheck(env))
    {
        return false;
    }

    jint type = (*env)->CallIntMethod(env, track_object, get_type);
    if ((*env)->ExceptionCheck(env))
    {
        return false;
    }

    jboolean pre_emphasis = (*env)->CallBooleanMethod(env, track_object, get_pre_emphasis);
    if ((*env)->ExceptionCheck(env))
    {
        return false;
    }

    jobject indices = (*env)->CallObjectMethod(env, track_object, get_indices);
    if ((*env)->ExceptionCheck(env))
    {
        return false;
    }

    if (isrc_object == NULL || indices == NULL)
    {
        throw_illegal_argument_exception(env, "CUESHEET track ISRC and indices must not be null.");
        return false;
    }

    char *isrc = jstring_to_utf8(env, isrc_object);
    jboolean isrc_conversion_threw = (*env)->ExceptionCheck(env);
    if (isrc == NULL || isrc_conversion_threw)
    {
        free(isrc);
        return false;
    }

    jclass list_class = (*env)->FindClass(env, "java/util/List");
    if (list_class == NULL)
    {
        free(isrc);
        return false;
    }

    jclass index_class = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacCueSheetIndex");
    if (index_class == NULL)
    {
        free(isrc);
        return false;
    }

    jmethodID list_size = (*env)->GetMethodID(env, list_class, "size", "()I");
    if (list_size == NULL)
    {
        free(isrc);
        return false;
    }

    jmethodID list_get = (*env)->GetMethodID(env, list_class, "get", "(I)Ljava/lang/Object;");
    if (list_get == NULL)
    {
        free(isrc);
        return false;
    }

    jmethodID get_index_offset = (*env)->GetMethodID(env, index_class, "getOffset", "()J");
    if (get_index_offset == NULL)
    {
        free(isrc);
        return false;
    }

    jmethodID get_index_number = (*env)->GetMethodID(env, index_class, "getNumber", "()I");
    if (get_index_number == NULL)
    {
        free(isrc);
        return false;
    }

    jint index_count = (*env)->CallIntMethod(env, indices, list_size);
    if ((*env)->ExceptionCheck(env))
    {
        free(isrc);
        return false;
    }

    if (!g_flac_api.metadata_object_cuesheet_track_resize_indices(
            block, track_index, (uint32_t)index_count))
    {
        free(isrc);
        return false;
    }

    /*
     * The resize call above may reallocate the track's index array, so take the
     * track pointer only after resizing. Fields below map directly to the FLAC
     * CUESHEET track structure.
     */
    FLAC__StreamMetadata_CueSheet_Track *track = &block->data.cue_sheet.tracks[track_index];
    track->offset = (FLAC__uint64)offset;
    track->number = (FLAC__byte)number;
    track->type = (uint32_t)type;
    track->pre_emphasis = pre_emphasis ? 1u : 0u;
    if (!copy_fixed_ascii_field(track->isrc, sizeof(track->isrc), isrc))
    {
        free(isrc);
        throw_illegal_argument_exception(env, "CUESHEET track ISRC is too long.");
        return false;
    }

    free(isrc);

    for (jint i = 0; i < index_count; ++i)
    {
        /*
         * Each index object is a temporary local reference. Delete it as soon
         * as its values are copied so large CUESHEETs do not pressure the JNI
         * local reference table.
         */
        jobject index_object = (*env)->CallObjectMethod(env, indices, list_get, i);
        if ((*env)->ExceptionCheck(env))
        {
            return false;
        }

        if (index_object == NULL)
        {
            throw_illegal_argument_exception(env, "CUESHEET index must not be null.");
            return false;
        }

        jlong index_offset = (*env)->CallLongMethod(env, index_object, get_index_offset);
        if ((*env)->ExceptionCheck(env))
        {
            (*env)->DeleteLocalRef(env, index_object);
            return false;
        }

        jint index_number = (*env)->CallIntMethod(env, index_object, get_index_number);
        if ((*env)->ExceptionCheck(env))
        {
            (*env)->DeleteLocalRef(env, index_object);
            return false;
        }

        (*env)->DeleteLocalRef(env, index_object);

        track->indices[i].offset = (FLAC__uint64)index_offset;
        track->indices[i].number = (FLAC__byte)index_number;
    }

    return true;
}

/*
 * Builds one libFLAC CUESHEET block from the public FlacCueSheet model.
 * Tracks and indices must be resized through libFLAC helpers before their
 * fields are written, then libFLAC's legality check validates CD-DA rules.
 */
static FLAC__bool build_cue_sheet_block(JNIEnv *env, jobject cue_sheet_object, FLAC__StreamMetadata **result)
{
    *result = NULL;
    if (cue_sheet_object == NULL)
    {
        throw_illegal_argument_exception(env, "CUESHEET metadata block must not be null.");
        return false;
    }

    jclass cue_sheet_class = (*env)->GetObjectClass(env, cue_sheet_object);
    if (cue_sheet_class == NULL)
    {
        return false;
    }

    jmethodID get_media_catalog_number =
        (*env)->GetMethodID(env, cue_sheet_class, "getMediaCatalogNumber", "()Ljava/lang/String;");
    if (get_media_catalog_number == NULL)
    {
        return false;
    }

    jmethodID get_lead_in = (*env)->GetMethodID(env, cue_sheet_class, "getLeadIn", "()J");
    if (get_lead_in == NULL)
    {
        return false;
    }

    jmethodID is_cd = (*env)->GetMethodID(env, cue_sheet_class, "isCd", "()Z");
    if (is_cd == NULL)
    {
        return false;
    }

    jmethodID get_tracks = (*env)->GetMethodID(env, cue_sheet_class, "getTracks", "()Ljava/util/List;");
    if (get_tracks == NULL)
    {
        return false;
    }

    jstring media_catalog_number_object =
        (jstring)(*env)->CallObjectMethod(env, cue_sheet_object, get_media_catalog_number);
    if ((*env)->ExceptionCheck(env))
    {
        return false;
    }

    jlong lead_in = (*env)->CallLongMethod(env, cue_sheet_object, get_lead_in);
    if ((*env)->ExceptionCheck(env))
    {
        return false;
    }

    jboolean cd = (*env)->CallBooleanMethod(env, cue_sheet_object, is_cd);
    if ((*env)->ExceptionCheck(env))
    {
        return false;
    }

    jobject tracks = (*env)->CallObjectMethod(env, cue_sheet_object, get_tracks);
    if ((*env)->ExceptionCheck(env))
    {
        return false;
    }

    if (media_catalog_number_object == NULL || tracks == NULL)
    {
        throw_illegal_argument_exception(env, "CUESHEET media catalog number and tracks must not be null.");
        return false;
    }

    char *media_catalog_number = jstring_to_utf8(env, media_catalog_number_object);
    jboolean catalog_conversion_threw = (*env)->ExceptionCheck(env);
    if (media_catalog_number == NULL || catalog_conversion_threw)
    {
        free(media_catalog_number);
        return false;
    }

    jclass list_class = (*env)->FindClass(env, "java/util/List");
    if (list_class == NULL)
    {
        free(media_catalog_number);
        return false;
    }

    jmethodID list_size = (*env)->GetMethodID(env, list_class, "size", "()I");
    if (list_size == NULL)
    {
        free(media_catalog_number);
        return false;
    }

    jmethodID list_get = (*env)->GetMethodID(env, list_class, "get", "(I)Ljava/lang/Object;");
    if (list_get == NULL)
    {
        free(media_catalog_number);
        return false;
    }

    jint track_count = (*env)->CallIntMethod(env, tracks, list_size);
    if ((*env)->ExceptionCheck(env))
    {
        free(media_catalog_number);
        return false;
    }

    FLAC__StreamMetadata *block = g_flac_api.metadata_object_new(FLAC__METADATA_TYPE_CUESHEET);
    if (block == NULL)
    {
        free(media_catalog_number);
        return false;
    }

    block->data.cue_sheet.lead_in = (FLAC__uint64)lead_in;
    block->data.cue_sheet.is_cd = cd ? true : false;
    if (!copy_fixed_ascii_field(block->data.cue_sheet.media_catalog_number,
                                sizeof(block->data.cue_sheet.media_catalog_number), media_catalog_number))
    {
        free(media_catalog_number);
        g_flac_api.metadata_object_delete(block);
        throw_illegal_argument_exception(env, "CUESHEET media catalog number is too long.");
        return false;
    }

    free(media_catalog_number);

    if (!g_flac_api.metadata_object_cuesheet_resize_tracks(block, (uint32_t)track_count))
    {
        g_flac_api.metadata_object_delete(block);
        return false;
    }

    /*
     * Resize the top-level track array once, then fill each track in place.
     * fill_cue_sheet_track() handles the nested index-array resize per track.
     */
    for (jint i = 0; i < track_count; ++i)
    {
        if ((*env)->PushLocalFrame(env, 16) < 0)
        {
            g_flac_api.metadata_object_delete(block);
            return false;
        }

        jobject track_object = (*env)->CallObjectMethod(env, tracks, list_get, i);
        if ((*env)->ExceptionCheck(env))
        {
            (*env)->PopLocalFrame(env, NULL);
            g_flac_api.metadata_object_delete(block);
            return false;
        }

        FLAC__bool track_success = fill_cue_sheet_track(env, block, (uint32_t)i, track_object);
        jboolean track_conversion_threw = (*env)->ExceptionCheck(env);
        (*env)->PopLocalFrame(env, NULL);
        if (!track_success || track_conversion_threw)
        {
            g_flac_api.metadata_object_delete(block);
            return false;
        }
    }

    const char *violation = NULL;
    /*
     * libFLAC performs format-level validation here. When cd=true, it also
     * checks the stricter CD-DA subset constraints requested by the caller.
     */
    if (!g_flac_api.metadata_object_cuesheet_is_legal(block, cd ? true : false, &violation))
    {
        char buffer[JFLAC_MESSAGE_BUFFER_SIZE];
        snprintf(buffer, sizeof(buffer), "CUESHEET metadata block is not legal: %s.",
                 violation != NULL ? violation : "unknown violation");
        throw_encode_exception(env, buffer);
        g_flac_api.metadata_object_delete(block);
        return false;
    }

    *result = block;
    return true;
}

static FLAC__bool ensure_metadata_block_length_fits(JNIEnv *env, FLAC__StreamMetadata *block);

typedef FLAC__bool (*MetadataBlockBuilder)(JNIEnv *env, jobject value, FLAC__StreamMetadata **result);

/*
 * Appends every element of one typed Java metadata array to the native block
 * table. One local frame per element contains all references created by the
 * selected builder, including nested model getters. The native block pointer
 * survives PopLocalFrame because it is owned by libFLAC, not by the JVM.
 */
static FLAC__bool build_metadata_array_blocks(JNIEnv *env, jobjectArray values, jsize value_count,
                                              MetadataBlockBuilder builder, EncodeContext *context,
                                              uint32_t *next_index)
{
    for (jsize i = 0; i < value_count; ++i)
    {
        if ((*env)->PushLocalFrame(env, 16) < 0)
        {
            return false;
        }

        jobject value = (*env)->GetObjectArrayElement(env, values, i);
        if ((*env)->ExceptionCheck(env))
        {
            (*env)->PopLocalFrame(env, NULL);
            return false;
        }

        FLAC__bool success = builder(env, value, &context->metadata_blocks[*next_index]);
        jboolean builder_threw = (*env)->ExceptionCheck(env);
        if (success && !builder_threw)
        {
            success = ensure_metadata_block_length_fits(env, context->metadata_blocks[*next_index]);
        }

        jboolean length_check_threw = (*env)->ExceptionCheck(env);
        (*env)->PopLocalFrame(env, NULL);
        if (!success || builder_threw || length_check_threw)
        {
            return false;
        }

        *next_index += 1u;
    }

    return true;
}

/*
 * Builds the metadata block array attached to a new encoder from grouped Java
 * arrays. This is the compact API path: one Vorbis-comment block is built from
 * all comments, then each typed array is appended in the wrapper's standard
 * order.
 */
static FLAC__bool build_metadata_blocks(JNIEnv *env, jobjectArray comment_entries, jobjectArray pictures,
                                        jobjectArray application_blocks, jobjectArray seek_tables,
                                        jobjectArray cue_sheets, jobjectArray padding_blocks,
                                        jobjectArray unknown_blocks, EncodeContext *context)
{
    jsize comment_count = comment_entries != NULL ? (*env)->GetArrayLength(env, comment_entries) : 0;
    jsize picture_count = pictures != NULL ? (*env)->GetArrayLength(env, pictures) : 0;
    jsize application_count = application_blocks != NULL ? (*env)->GetArrayLength(env, application_blocks) : 0;
    jsize seek_table_count = seek_tables != NULL ? (*env)->GetArrayLength(env, seek_tables) : 0;
    jsize cue_sheet_count = cue_sheets != NULL ? (*env)->GetArrayLength(env, cue_sheets) : 0;
    jsize padding_count = padding_blocks != NULL ? (*env)->GetArrayLength(env, padding_blocks) : 0;
    jsize unknown_count = unknown_blocks != NULL ? (*env)->GetArrayLength(env, unknown_blocks) : 0;
    uint64_t total_blocks_64 = (comment_count > 0 ? 1u : 0u) + (uint64_t)picture_count + (uint64_t)application_count +
                               (uint64_t)seek_table_count + (uint64_t)cue_sheet_count + (uint64_t)padding_count +
                               (uint64_t)unknown_count;
    if (total_blocks_64 > UINT32_MAX)
    {
        throw_encode_exception(env, "Too many FLAC metadata blocks were requested.");
        return false;
    }

    uint32_t total_blocks = (uint32_t)total_blocks_64;

    context->metadata_blocks = NULL;
    context->metadata_count = 0;
    if (total_blocks == 0u)
    {
        return true;
    }

    /*
     * Allocate the block pointer table first so every later failure can use
     * destroy_metadata_blocks() for a single cleanup path. Individual builders
     * only publish a pointer into the table once the block is valid enough for
     * metadata_object_delete().
     */
    context->metadata_blocks = (FLAC__StreamMetadata **)calloc(total_blocks, sizeof(FLAC__StreamMetadata *));
    if (context->metadata_blocks == NULL)
    {
        return false;
    }

    /*
     * metadata_count is also the destructor's cleanup span. Publish the full
     * zero-initialised table immediately so a later partial-build failure
     * deletes every block that an earlier builder successfully installed.
     */
    context->metadata_count = total_blocks;

    uint32_t index = 0;
    if (comment_count > 0)
    {
        if (!build_vorbis_comment_block(env, NULL, comment_entries, false,
                                        &context->metadata_blocks[index]) ||
            !ensure_metadata_block_length_fits(env, context->metadata_blocks[index]))
        {
            destroy_metadata_blocks(context);
            return false;
        }

        index += 1u;
    }

    if (!build_metadata_array_blocks(env, pictures, picture_count, build_picture_block, context, &index) ||
        !build_metadata_array_blocks(env, application_blocks, application_count, build_application_block, context,
                                     &index) ||
        !build_metadata_array_blocks(env, seek_tables, seek_table_count, build_seek_table_block, context, &index) ||
        !build_metadata_array_blocks(env, cue_sheets, cue_sheet_count, build_cue_sheet_block, context, &index) ||
        !build_metadata_array_blocks(env, padding_blocks, padding_count, build_padding_block, context, &index) ||
        !build_metadata_array_blocks(env, unknown_blocks, unknown_count, build_unknown_metadata_block, context,
                                     &index))
    {
        destroy_metadata_blocks(context);
        return false;
    }

    return true;
}

static FLAC__bool ensure_metadata_block_length_fits(JNIEnv *env, FLAC__StreamMetadata *block)
{
    if (block != NULL && block->length > JFLAC_METADATA_MAX_BLOCK_LENGTH)
    {
        throw_illegal_argument_exception(env, "FLAC metadata length must fit the 24-bit metadata length field.");
        return false;
    }

    return true;
}

static FLAC__bool ensure_instance_of(JNIEnv *env, jobject value, const char *class_name, const char *message)
{
    jclass expected_class = (*env)->FindClass(env, class_name);
    if (expected_class == NULL)
    {
        return false;
    }

    jboolean matches = (*env)->IsInstanceOf(env, value, expected_class);
    jboolean check_threw = (*env)->ExceptionCheck(env);
    (*env)->DeleteLocalRef(env, expected_class);
    if (check_threw)
    {
        return false;
    }

    if (matches == JNI_FALSE)
    {
        throw_illegal_argument_exception(env, message);
        return false;
    }

    return true;
}

static FLAC__bool build_vorbis_comment_transport_block(JNIEnv *env, jobject block_value,
                                                       FLAC__StreamMetadata **result)
{
    if (!ensure_instance_of(env, block_value, "org/zzvsjs/jflac/internal/NativeVorbisCommentBlock",
                            "VORBIS_COMMENT ordered metadata value must be NativeVorbisCommentBlock."))
    {
        return false;
    }

    jclass block_class = (*env)->GetObjectClass(env, block_value);
    if (block_class == NULL)
    {
        return false;
    }

    jmethodID get_vendor = (*env)->GetMethodID(env, block_class, "getVendor", "()Ljava/lang/String;");
    if (get_vendor == NULL)
    {
        return false;
    }

    jmethodID get_entries = (*env)->GetMethodID(env, block_class, "getEntries", "()[Ljava/lang/String;");
    if (get_entries == NULL)
    {
        return false;
    }

    jstring vendor = (jstring)(*env)->CallObjectMethod(env, block_value, get_vendor);
    if ((*env)->ExceptionCheck(env))
    {
        return false;
    }

    jobjectArray entries = (jobjectArray)(*env)->CallObjectMethod(env, block_value, get_entries);
    if ((*env)->ExceptionCheck(env))
    {
        return false;
    }

    if (entries == NULL)
    {
        throw_illegal_argument_exception(env, "VORBIS_COMMENT entries must not be null.");
        return false;
    }

    return build_vorbis_comment_block(env, vendor, entries, true, result);
}

/*
 * Builds one ordered metadata block according to the Java-supplied FLAC type
 * code. Ordered mode is used when callers need exact block ordering; the
 * caller supplies a parallel type array and value array from Kotlin.
 */
static FLAC__bool build_ordered_metadata_block(JNIEnv *env, jint block_type, jobject block_value,
                                               FLAC__StreamMetadata **result)
{
    if (block_value == NULL)
    {
        throw_illegal_argument_exception(env, "Ordered metadata block value must not be null.");
        return false;
    }

    FLAC__bool success = false;
    switch ((FLAC__MetadataType)block_type)
    {
    case FLAC__METADATA_TYPE_STREAMINFO:
        throw_illegal_argument_exception(env, "STREAMINFO metadata blocks cannot be supplied to the encoder.");
        return false;
    case FLAC__METADATA_TYPE_PADDING:
        success = ensure_instance_of(env, block_value, "org/zzvsjs/jflac/FlacPaddingBlock",
                                     "PADDING ordered metadata value must be FlacPaddingBlock.") &&
                  build_padding_block(env, block_value, result);
        break;
    case FLAC__METADATA_TYPE_APPLICATION:
        success = ensure_instance_of(env, block_value, "org/zzvsjs/jflac/FlacApplicationBlock",
                                     "APPLICATION ordered metadata value must be FlacApplicationBlock.") &&
                  build_application_block(env, block_value, result);
        break;
    case FLAC__METADATA_TYPE_SEEKTABLE:
        success = ensure_instance_of(env, block_value, "org/zzvsjs/jflac/FlacSeekTable",
                                     "SEEKTABLE ordered metadata value must be FlacSeekTable.") &&
                  build_seek_table_block(env, block_value, result);
        break;
    case FLAC__METADATA_TYPE_VORBIS_COMMENT:
        success = build_vorbis_comment_transport_block(env, block_value, result);
        break;
    case FLAC__METADATA_TYPE_CUESHEET:
        success = ensure_instance_of(env, block_value, "org/zzvsjs/jflac/FlacCueSheet",
                                     "CUESHEET ordered metadata value must be FlacCueSheet.") &&
                  build_cue_sheet_block(env, block_value, result);
        break;
    case FLAC__METADATA_TYPE_PICTURE:
        success = ensure_instance_of(env, block_value, "org/zzvsjs/jflac/FlacPicture",
                                     "PICTURE ordered metadata value must be FlacPicture.") &&
                  build_picture_block(env, block_value, result);
        break;
    default:
        success = ensure_instance_of(env, block_value, "org/zzvsjs/jflac/FlacUnknownMetadataBlock",
                                     "Unknown ordered metadata value must be FlacUnknownMetadataBlock.") &&
                  build_unknown_metadata_block(env, block_value, result);
        break;
    }

    if (!success)
    {
        return false;
    }

    if (!ensure_metadata_block_length_fits(env, *result))
    {
        if (*result != NULL)
        {
            g_flac_api.metadata_object_delete(*result);
            *result = NULL;
        }

        return false;
    }

    return true;
}

/*
 * Builds metadata blocks in exact caller order, excluding encoder-owned
 * STREAMINFO. If any ordered metadata is present, ordered mode wins over the
 * grouped arrays so a round-trip read/edit/write can preserve block order.
 */
static FLAC__bool build_ordered_metadata_blocks(JNIEnv *env, jintArray metadata_block_types,
                                                jobjectArray metadata_block_values, EncodeContext *context)
{
    jsize type_count = metadata_block_types != NULL ? (*env)->GetArrayLength(env, metadata_block_types) : 0;
    jsize value_count = metadata_block_values != NULL ? (*env)->GetArrayLength(env, metadata_block_values) : 0;
    if (type_count != value_count)
    {
        throw_illegal_argument_exception(env, "Ordered metadata type and value counts must match.");
        return false;
    }

    context->metadata_blocks = NULL;
    context->metadata_count = 0;
    if (type_count == 0)
    {
        return true;
    }

    /*
     * Ordered mode is an exact replacement list. It does not merge with the
     * grouped arrays because that would make output order ambiguous.
     */
    context->metadata_blocks = (FLAC__StreamMetadata **)calloc((size_t)type_count, sizeof(FLAC__StreamMetadata *));
    if (context->metadata_blocks == NULL)
    {
        return false;
    }

    /* See build_metadata_blocks(): the destructor needs the full table span during construction too. */
    context->metadata_count = (uint32_t)type_count;
    for (jsize i = 0; i < type_count; ++i)
    {
        if ((*env)->PushLocalFrame(env, 16) < 0)
        {
            destroy_metadata_blocks(context);
            return false;
        }

        /*
         * The two Java arrays are parallel: metadata_block_types[i] says how to
         * interpret metadata_block_values[i]. JNI array access can throw, so
         * each element read is followed by ExceptionCheck before continuing.
         */
        jint block_type = 0;
        (*env)->GetIntArrayRegion(env, metadata_block_types, i, 1, &block_type);
        if ((*env)->ExceptionCheck(env))
        {
            (*env)->PopLocalFrame(env, NULL);
            destroy_metadata_blocks(context);
            return false;
        }

        jobject block_value = (*env)->GetObjectArrayElement(env, metadata_block_values, i);
        if ((*env)->ExceptionCheck(env))
        {
            (*env)->PopLocalFrame(env, NULL);
            destroy_metadata_blocks(context);
            return false;
        }

        FLAC__bool success =
            build_ordered_metadata_block(env, block_type, block_value, &context->metadata_blocks[i]);
        jboolean builder_threw = (*env)->ExceptionCheck(env);
        (*env)->PopLocalFrame(env, NULL);
        if (!success || builder_threw)
        {
            destroy_metadata_blocks(context);
            return false;
        }
    }

    return true;
}

/*
 * Replaces all non-STREAMINFO blocks in an existing FLAC file metadata chain.
 * STREAMINFO is preserved because libFLAC owns stream statistics and the
 * public metadata editor is intentionally file-only. The flow is:
 * read chain, verify leading STREAMINFO, delete every later block, build the
 * requested replacement blocks, insert them after STREAMINFO, then ask libFLAC
 * to rewrite the chain with the caller's padding/file-stat policy.
 *
 * Kotlin rejects Ogg FLAC before this JNI entry point because this writer uses
 * FLAC__metadata_chain_read/write, not the Ogg metadata-chain reader. Keeping
 * that policy in one JVM-side validation path gives callers a stable
 * UnsupportedFeatureException instead of a lower-level chain status.
 */
JNIEXPORT void JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_writeMetadata(JNIEnv *env, jclass clazz,
                                                                                   jstring path, jobject request,
                                                                                   jboolean use_padding,
                                                                                   jboolean preserve_file_stats)
{
    (void)clazz;
    if (!flac_api_ready(env))
    {
        return;
    }

    if (path == NULL || request == NULL)
    {
        throw_illegal_argument_exception(env, "Metadata edit path and request must not be null.");
        return;
    }

    /*
     * libFLAC's metadata chain API is file-name based. There is intentionally
     * no stream/channel metadata editor here because V1 only supports safe
     * whole-file metadata rewrite semantics.
     */
    char *utf8_path = jstring_to_utf8(env, path);
    if (utf8_path == NULL)
    {
        throw_metadata_edit_exception(env, "Failed to encode file path to UTF-8.");
        return;
    }

    FLAC__Metadata_Chain *chain = g_flac_api.metadata_chain_new();
    if (chain == NULL)
    {
        free(utf8_path);
        throw_metadata_edit_exception(env, "Failed to allocate FLAC metadata chain.");
        return;
    }

    if (!g_flac_api.metadata_chain_read(chain, utf8_path))
    {
        free(utf8_path);
        throw_metadata_chain_edit_exception(env, "Failed to read FLAC metadata for editing", chain);
        g_flac_api.metadata_chain_delete(chain);
        return;
    }

    free(utf8_path);

    FLAC__Metadata_Iterator *iterator = g_flac_api.metadata_iterator_new();
    if (iterator == NULL)
    {
        g_flac_api.metadata_chain_delete(chain);
        throw_metadata_edit_exception(env, "Failed to allocate FLAC metadata iterator.");
        return;
    }

    g_flac_api.metadata_iterator_init(iterator, chain);
    FLAC__StreamMetadata *first_block = g_flac_api.metadata_iterator_get_block(iterator);
    if (first_block == NULL || first_block->type != FLAC__METADATA_TYPE_STREAMINFO)
    {
        g_flac_api.metadata_iterator_delete(iterator);
        g_flac_api.metadata_chain_delete(chain);
        throw_metadata_edit_exception(env, "FLAC file does not contain a leading STREAMINFO metadata block.");
        return;
    }

    /*
     * The public edit API replaces all non-STREAMINFO metadata. That is simpler
     * and safer than in-place mutation because callers provide a complete
     * desired metadata set, and libFLAC can decide how to rewrite/pad the file.
     */
    /*
     * After metadata_iterator_next(), delete_block(false) removes the current
     * block and keeps the iterator at a valid insertion point. false means do
     * not replace removed blocks with padding; the caller's use_padding choice
     * is applied once when the final chain is written.
     */
    while (g_flac_api.metadata_iterator_next(iterator))
    {
        if (!g_flac_api.metadata_iterator_delete_block(iterator, false))
        {
            g_flac_api.metadata_iterator_delete(iterator);
            throw_metadata_chain_edit_exception(env, "Failed to delete existing FLAC metadata block", chain);
            g_flac_api.metadata_chain_delete(chain);
            return;
        }
    }

    jclass request_class = (*env)->GetObjectClass(env, request);
    jmethodID get_comment_entries = NULL;
    jmethodID get_pictures = NULL;
    jmethodID get_application_blocks = NULL;
    jmethodID get_seek_tables = NULL;
    jmethodID get_cue_sheets = NULL;
    jmethodID get_padding_blocks = NULL;
    jmethodID get_unknown_blocks = NULL;
    jmethodID get_metadata_block_types = NULL;
    jmethodID get_metadata_block_values = NULL;
    if (request_class == NULL ||
        !find_instance_method(env, request_class, "getCommentEntries", "()[Ljava/lang/String;",
                              &get_comment_entries) ||
        !find_instance_method(env, request_class, "getPictures", "()[Lorg/zzvsjs/jflac/FlacPicture;",
                              &get_pictures) ||
        !find_instance_method(env, request_class, "getApplicationBlocks",
                              "()[Lorg/zzvsjs/jflac/FlacApplicationBlock;", &get_application_blocks) ||
        !find_instance_method(env, request_class, "getSeekTables", "()[Lorg/zzvsjs/jflac/FlacSeekTable;",
                              &get_seek_tables) ||
        !find_instance_method(env, request_class, "getCueSheets", "()[Lorg/zzvsjs/jflac/FlacCueSheet;",
                              &get_cue_sheets) ||
        !find_instance_method(env, request_class, "getPaddingBlocks", "()[Lorg/zzvsjs/jflac/FlacPaddingBlock;",
                              &get_padding_blocks) ||
        !find_instance_method(env, request_class, "getUnknownBlocks",
                              "()[Lorg/zzvsjs/jflac/FlacUnknownMetadataBlock;", &get_unknown_blocks) ||
        !find_instance_method(env, request_class, "getMetadataBlockTypes", "()[I", &get_metadata_block_types) ||
        !find_instance_method(env, request_class, "getMetadataBlockValues", "()[Ljava/lang/Object;",
                              &get_metadata_block_values))
    {
        /*
         * GetMethodID leaves a pending Java exception on failure. Preserve it
         * and only perform native cleanup here.
         */
        g_flac_api.metadata_iterator_delete(iterator);
        g_flac_api.metadata_chain_delete(chain);
        return;
    }

    jobject comment_entries_value = NULL;
    jobject pictures_value = NULL;
    jobject application_blocks_value = NULL;
    jobject seek_tables_value = NULL;
    jobject cue_sheets_value = NULL;
    jobject padding_blocks_value = NULL;
    jobject unknown_blocks_value = NULL;
    jobject metadata_block_types_value = NULL;
    jobject metadata_block_values_value = NULL;
    if (!call_object_getter(env, request, get_comment_entries, &comment_entries_value) ||
        !call_object_getter(env, request, get_pictures, &pictures_value) ||
        !call_object_getter(env, request, get_application_blocks, &application_blocks_value) ||
        !call_object_getter(env, request, get_seek_tables, &seek_tables_value) ||
        !call_object_getter(env, request, get_cue_sheets, &cue_sheets_value) ||
        !call_object_getter(env, request, get_padding_blocks, &padding_blocks_value) ||
        !call_object_getter(env, request, get_unknown_blocks, &unknown_blocks_value) ||
        !call_object_getter(env, request, get_metadata_block_types, &metadata_block_types_value) ||
        !call_object_getter(env, request, get_metadata_block_values, &metadata_block_values_value))
    {
        g_flac_api.metadata_iterator_delete(iterator);
        g_flac_api.metadata_chain_delete(chain);
        return;
    }

    jobjectArray comment_entries = (jobjectArray)comment_entries_value;
    jobjectArray pictures = (jobjectArray)pictures_value;
    jobjectArray application_blocks = (jobjectArray)application_blocks_value;
    jobjectArray seek_tables = (jobjectArray)seek_tables_value;
    jobjectArray cue_sheets = (jobjectArray)cue_sheets_value;
    jobjectArray padding_blocks = (jobjectArray)padding_blocks_value;
    jobjectArray unknown_blocks = (jobjectArray)unknown_blocks_value;
    jintArray metadata_block_types = (jintArray)metadata_block_types_value;
    jobjectArray metadata_block_values = (jobjectArray)metadata_block_values_value;

    EncodeContext metadata_context;
    memset(&metadata_context, 0, sizeof(metadata_context));
    /*
     * Reuse the encoder metadata builders so edit and encode validate blocks
     * identically. metadata_context only owns metadata_blocks here; it has no
     * encoder or Java global references.
     */
    jsize ordered_metadata_count =
        metadata_block_types != NULL ? (*env)->GetArrayLength(env, metadata_block_types) : (jsize)0;
    FLAC__bool metadata_success =
        ordered_metadata_count > 0
            ? build_ordered_metadata_blocks(env, metadata_block_types, metadata_block_values, &metadata_context)
            : build_metadata_blocks(env, comment_entries, pictures, application_blocks, seek_tables, cue_sheets,
                                    padding_blocks, unknown_blocks, &metadata_context);
    if (!metadata_success)
    {
        destroy_metadata_blocks(&metadata_context);
        g_flac_api.metadata_iterator_delete(iterator);
        g_flac_api.metadata_chain_delete(chain);
        if (!(*env)->ExceptionCheck(env))
        {
            throw_metadata_edit_exception(env, "Failed to build FLAC metadata edit blocks.");
        }

        return;
    }

    g_flac_api.metadata_iterator_init(iterator, chain);
    for (uint32_t i = 0; i < metadata_context.metadata_count; ++i)
    {
        FLAC__StreamMetadata *block = metadata_context.metadata_blocks[i];
        if (block == NULL)
        {
            continue;
        }

        if (!g_flac_api.metadata_iterator_insert_block_after(iterator, block))
        {
            destroy_metadata_blocks(&metadata_context);
            g_flac_api.metadata_iterator_delete(iterator);
            throw_metadata_chain_edit_exception(env, "Failed to insert FLAC metadata edit block", chain);
            g_flac_api.metadata_chain_delete(chain);
            return;
        }

        /*
         * insert_block_after transfers ownership to the metadata chain on
         * success. Null the slot so destroy_metadata_blocks() only releases
         * blocks that were not inserted.
         */
        metadata_context.metadata_blocks[i] = NULL;
    }

    destroy_metadata_blocks(&metadata_context);

    /*
     * use_padding lets libFLAC reuse/insert padding during rewrite.
     * preserve_file_stats asks libFLAC to keep file metadata such as mtime when
     * the platform supports it.
     */
    if (!g_flac_api.metadata_chain_write(chain, use_padding ? true : false,
                                         preserve_file_stats ? true : false))
    {
        g_flac_api.metadata_iterator_delete(iterator);
        throw_metadata_chain_edit_exception(env, "Failed to write FLAC metadata edits", chain);
        g_flac_api.metadata_chain_delete(chain);
        return;
    }

    g_flac_api.metadata_iterator_delete(iterator);
    g_flac_api.metadata_chain_delete(chain);
}

/*
 * Returns the current encoder state text, falling back when libFLAC gives none.
 * Error paths call this after libFLAC returns false so Java gets state context
 * instead of only "operation failed".
 */
static const char *encoder_state_or_unknown(const FLAC__StreamEncoder *encoder)
{
    const char *state = g_flac_api.stream_encoder_get_resolved_state_string(encoder);
    return state != NULL ? state : "unknown encoder state";
}

/*
 * Returns the JNIEnv for synchronous libFLAC callbacks on the calling thread.
 * The wrapper only supports callbacks made on the JNI caller thread; if libFLAC
 * ever called back on a different native thread this helper would fail closed.
 */
static JNIEnv *encode_context_env(EncodeContext *context)
{
    if (context == NULL || context->jvm == NULL)
    {
        return NULL;
    }

    JNIEnv *env = NULL;
    if ((*context->jvm)->GetEnv(context->jvm, (void **)&env, JNI_VERSION_1_8) != JNI_OK)
    {
        return NULL;
    }

    return env;
}

/*
 * Resolves OutputStream callbacks and promotes the stream to encoder lifetime.
 * OutputStream encoders can stay open across multiple writeEncoderInterleaved()
 * JNI calls, so the stream must be a global reference until finish/release.
 */
static int prepare_encode_output_stream(JNIEnv *env, EncodeContext *context, jobject output_stream)
{
    if (output_stream == NULL)
    {
        throw_illegal_argument_exception(env, "Encoder output stream must not be null.");
        return 0;
    }

    jclass output_class = (*env)->GetObjectClass(env, output_stream);
    if (output_class == NULL)
    {
        return 0;
    }

    context->output_write = (*env)->GetMethodID(env, output_class, "write", "([BII)V");
    context->output_flush = (*env)->GetMethodID(env, output_class, "flush", "()V");
    if (context->output_write == NULL || context->output_flush == NULL)
    {
        return 0;
    }

    if ((*env)->GetJavaVM(env, &context->jvm) != JNI_OK)
    {
        throw_encode_exception(env, "Failed to access JVM for stream encoder callbacks.");
        return 0;
    }

    context->output_stream = (*env)->NewGlobalRef(env, output_stream);
    if (context->output_stream == NULL)
    {
        return 0;
    }

    return 1;
}

/*
 * Resolves SeekableByteChannel callbacks and promotes the channel reference.
 * The starting channel position becomes the FLAC stream origin; all libFLAC
 * seek/tell offsets are translated relative to that base offset.
 */
static int prepare_encode_output_channel(JNIEnv *env, EncodeContext *context, jobject output_channel)
{
    if (output_channel == NULL)
    {
        throw_illegal_argument_exception(env, "Encoder output channel must not be null.");
        return 0;
    }

    jclass readable_class = (*env)->FindClass(env, "java/nio/channels/ReadableByteChannel");
    jclass writable_class = (*env)->FindClass(env, "java/nio/channels/WritableByteChannel");
    jclass seekable_class = (*env)->FindClass(env, "java/nio/channels/SeekableByteChannel");
    if (readable_class == NULL || writable_class == NULL || seekable_class == NULL)
    {
        return 0;
    }

    context->channel_read = (*env)->GetMethodID(env, readable_class, "read", "(Ljava/nio/ByteBuffer;)I");
    context->channel_write = (*env)->GetMethodID(env, writable_class, "write", "(Ljava/nio/ByteBuffer;)I");
    context->channel_position = (*env)->GetMethodID(env, seekable_class, "position", "()J");
    context->channel_seek =
        (*env)->GetMethodID(env, seekable_class, "position", "(J)Ljava/nio/channels/SeekableByteChannel;");
    if (context->channel_read == NULL || context->channel_write == NULL ||
        context->channel_position == NULL || context->channel_seek == NULL)
    {
        return 0;
    }

    if ((*env)->GetJavaVM(env, &context->jvm) != JNI_OK)
    {
        throw_encode_exception(env, "Failed to access JVM for channel encoder callbacks.");
        return 0;
    }

    context->output_channel = (*env)->NewGlobalRef(env, output_channel);
    if (context->output_channel == NULL)
    {
        return 0;
    }

    context->channel_base_offset = (*env)->CallLongMethod(env, output_channel, context->channel_position);
    if ((*env)->ExceptionCheck(env))
    {
        return 0;
    }

    if (context->channel_base_offset < 0)
    {
        throw_encode_exception(env, "SeekableByteChannel returned a negative position.");
        return 0;
    }

    return 1;
}

/*
 * Ogg FLAC channel encoders need a read-back callback when seek/tell are
 * available so libFLAC can update Ogg metadata after audio frames are written.
 */
static FLAC__StreamEncoderReadStatus encode_channel_read_callback(const FLAC__StreamEncoder *encoder,
                                                                  FLAC__byte buffer[], size_t *bytes,
                                                                  void *client_data)
{
    (void)encoder;
    EncodeContext *context = (EncodeContext *)client_data;
    JNIEnv *env = encode_context_env(context);
    if (env == NULL || context->output_channel == NULL || context->channel_read == NULL ||
        bytes == NULL || buffer == NULL)
    {
        if (bytes != NULL)
        {
            *bytes = 0u;
        }

        return FLAC__STREAM_ENCODER_READ_STATUS_ABORT;
    }

    if ((*env)->ExceptionCheck(env))
    {
        *bytes = 0u;
        return FLAC__STREAM_ENCODER_READ_STATUS_ABORT;
    }

    if (*bytes == 0u)
    {
        return FLAC__STREAM_ENCODER_READ_STATUS_ABORT;
    }

    if (*bytes > (size_t)INT_MAX)
    {
        *bytes = 0u;
        throw_encode_exception(env, "SeekableByteChannel read-back request is too large for one ByteBuffer.");
        return FLAC__STREAM_ENCODER_READ_STATUS_ABORT;
    }

    /*
     * The direct ByteBuffer is only a temporary view over libFLAC's read-back
     * buffer. The Java channel fills it during this call; native code deletes
     * the local reference immediately afterwards and libFLAC owns the memory.
     */
    jobject byte_buffer = (*env)->NewDirectByteBuffer(env, buffer, (jlong)*bytes);
    if (byte_buffer == NULL)
    {
        *bytes = 0u;
        return FLAC__STREAM_ENCODER_READ_STATUS_ABORT;
    }

    jint read = (*env)->CallIntMethod(env, context->output_channel, context->channel_read, byte_buffer);
    jboolean read_failed = (*env)->ExceptionCheck(env);
    (*env)->DeleteLocalRef(env, byte_buffer);
    if (read_failed)
    {
        *bytes = 0u;
        return FLAC__STREAM_ENCODER_READ_STATUS_ABORT;
    }

    if (read <= 0)
    {
        *bytes = 0u;
        return FLAC__STREAM_ENCODER_READ_STATUS_END_OF_STREAM;
    }

    *bytes = (size_t)read;
    return FLAC__STREAM_ENCODER_READ_STATUS_CONTINUE;
}

/*
 * libFLAC write callback backed by java.nio.channels.SeekableByteChannel. A
 * direct ByteBuffer wraps libFLAC's encoded byte slice for this callback only;
 * Java must not store it because the pointer is owned by libFLAC and becomes
 * invalid after the callback returns. A loop handles partial channel writes
 * until the full slice has been consumed.
 */
static FLAC__StreamEncoderWriteStatus encode_channel_write_callback(const FLAC__StreamEncoder *encoder,
                                                                    const FLAC__byte buffer[], size_t bytes,
                                                                    uint32_t samples, uint32_t current_frame,
                                                                    void *client_data)
{
    (void)encoder;
    (void)samples;
    (void)current_frame;

    EncodeContext *context = (EncodeContext *)client_data;
    JNIEnv *env = encode_context_env(context);
    if (env == NULL || context->output_channel == NULL || context->channel_write == NULL)
    {
        return FLAC__STREAM_ENCODER_WRITE_STATUS_FATAL_ERROR;
    }

    if ((*env)->ExceptionCheck(env))
    {
        return FLAC__STREAM_ENCODER_WRITE_STATUS_FATAL_ERROR;
    }

    if (bytes > (size_t)INT_MAX)
    {
        throw_encode_exception(env, "Encoded FLAC chunk is too large for one ByteBuffer.");
        return FLAC__STREAM_ENCODER_WRITE_STATUS_FATAL_ERROR;
    }

    jobject byte_buffer = (*env)->NewDirectByteBuffer(env, (void *)buffer, (jlong)bytes);
    if (byte_buffer == NULL)
    {
        return FLAC__STREAM_ENCODER_WRITE_STATUS_FATAL_ERROR;
    }

    size_t written_total = 0u;
    while (written_total < bytes)
    {
        jint written = (*env)->CallIntMethod(env, context->output_channel, context->channel_write, byte_buffer);
        if ((*env)->ExceptionCheck(env))
        {
            (*env)->DeleteLocalRef(env, byte_buffer);
            return FLAC__STREAM_ENCODER_WRITE_STATUS_FATAL_ERROR;
        }

        if (written <= 0)
        {
            (*env)->DeleteLocalRef(env, byte_buffer);
            /*
             * WritableByteChannel may legally write fewer bytes, but returning
             * zero for this blocking V1 path would spin forever. Treat no
             * progress as fatal and let Java expose the failure.
             */
            throw_encode_exception(env, "SeekableByteChannel returned zero bytes for a non-empty write request.");
            return FLAC__STREAM_ENCODER_WRITE_STATUS_FATAL_ERROR;
        }

        written_total += (size_t)written;
    }

    (*env)->DeleteLocalRef(env, byte_buffer);
    return FLAC__STREAM_ENCODER_WRITE_STATUS_OK;
}

/*
 * libFLAC seek callback backed by SeekableByteChannel.position(long). It is
 * supplied only for seekable channel encoders, letting libFLAC back-patch final
 * STREAMINFO statistics after finish.
 */
static FLAC__StreamEncoderSeekStatus encode_channel_seek_callback(const FLAC__StreamEncoder *encoder,
                                                                  FLAC__uint64 absolute_byte_offset,
                                                                  void *client_data)
{
    (void)encoder;
    EncodeContext *context = (EncodeContext *)client_data;
    JNIEnv *env = encode_context_env(context);
    if (env == NULL || context->output_channel == NULL || context->channel_seek == NULL ||
        absolute_byte_offset > (FLAC__uint64)(LLONG_MAX - context->channel_base_offset))
    {
        return FLAC__STREAM_ENCODER_SEEK_STATUS_ERROR;
    }

    jobject ignored = (*env)->CallObjectMethod(env, context->output_channel, context->channel_seek,
                                               context->channel_base_offset + (jlong)absolute_byte_offset);
    jboolean seek_failed = (*env)->ExceptionCheck(env);
    if (ignored != NULL)
    {
        (*env)->DeleteLocalRef(env, ignored);
    }

    return seek_failed ? FLAC__STREAM_ENCODER_SEEK_STATUS_ERROR : FLAC__STREAM_ENCODER_SEEK_STATUS_OK;
}

/*
 * libFLAC tell callback backed by SeekableByteChannel.position(). As with the
 * decoder, libFLAC offsets are relative to the captured stream origin rather
 * than the absolute channel position.
 */
static FLAC__StreamEncoderTellStatus encode_channel_tell_callback(const FLAC__StreamEncoder *encoder,
                                                                  FLAC__uint64 *absolute_byte_offset,
                                                                  void *client_data)
{
    (void)encoder;
    EncodeContext *context = (EncodeContext *)client_data;
    JNIEnv *env = encode_context_env(context);
    if (env == NULL || context->output_channel == NULL || context->channel_position == NULL ||
        absolute_byte_offset == NULL)
    {
        return FLAC__STREAM_ENCODER_TELL_STATUS_ERROR;
    }

    jlong position = (*env)->CallLongMethod(env, context->output_channel, context->channel_position);
    if ((*env)->ExceptionCheck(env) || position < context->channel_base_offset)
    {
        return FLAC__STREAM_ENCODER_TELL_STATUS_ERROR;
    }

    *absolute_byte_offset = (FLAC__uint64)(position - context->channel_base_offset);
    return FLAC__STREAM_ENCODER_TELL_STATUS_OK;
}

/*
 * libFLAC write callback backed by java.io.OutputStream. OutputStream receives
 * a copied JVM byte array because it cannot write from a native pointer.
 * No seek/tell callbacks are registered for OutputStream, so the FLAC file is
 * valid but final STREAMINFO statistics cannot be back-patched by libFLAC. If
 * OutputStream.write throws, the pending Java exception remains the primary
 * failure and libFLAC only sees a fatal callback status.
 */
static FLAC__StreamEncoderWriteStatus encode_write_callback(const FLAC__StreamEncoder *encoder,
                                                            const FLAC__byte buffer[], size_t bytes,
                                                            uint32_t samples, uint32_t current_frame,
                                                            void *client_data)
{
    EncodeContext *context = (EncodeContext *)client_data;
    if (context == NULL)
    {
        return FLAC__STREAM_ENCODER_WRITE_STATUS_FATAL_ERROR;
    }

    if (context->output_channel != NULL)
    {
        return encode_channel_write_callback(encoder, buffer, bytes, samples, current_frame, client_data);
    }

    (void)encoder;
    (void)samples;
    (void)current_frame;

    JNIEnv *env = encode_context_env(context);
    if (env == NULL || context->output_stream == NULL || context->output_write == NULL)
    {
        return FLAC__STREAM_ENCODER_WRITE_STATUS_FATAL_ERROR;
    }

    if ((*env)->ExceptionCheck(env))
    {
        return FLAC__STREAM_ENCODER_WRITE_STATUS_FATAL_ERROR;
    }

    if (bytes > (size_t)INT_MAX)
    {
        throw_encode_exception(env, "Encoded FLAC chunk is too large for one JVM ByteArray.");
        return FLAC__STREAM_ENCODER_WRITE_STATUS_FATAL_ERROR;
    }

    jbyteArray chunk = (*env)->NewByteArray(env, (jsize)bytes);
    if (chunk == NULL)
    {
        return FLAC__STREAM_ENCODER_WRITE_STATUS_FATAL_ERROR;
    }

    if (bytes > 0u)
    {
        (*env)->SetByteArrayRegion(env, chunk, 0, (jsize)bytes, (const jbyte *)buffer);
    }

    jboolean write_failed = (*env)->ExceptionCheck(env);
    if (!write_failed)
    {
        (*env)->CallVoidMethod(env, context->output_stream, context->output_write, chunk, (jint)0, (jint)bytes);
        write_failed = (*env)->ExceptionCheck(env);
    }

    (*env)->DeleteLocalRef(env, chunk);

    return write_failed ? FLAC__STREAM_ENCODER_WRITE_STATUS_FATAL_ERROR : FLAC__STREAM_ENCODER_WRITE_STATUS_OK;
}

/*
 * Opens and configures a file, OutputStream, or SeekableByteChannel encoder.
 * This allocates the EncodeContext, attaches metadata, initialises the chosen
 * libFLAC output target, and returns a registry handle to Java. finishEncoder()
 * or releaseEncoder() removes that handle; using it after either call is
 * invalid.
 */
static jlong open_encoder_internal(JNIEnv *env, char *utf8_path, jobject output_stream, jobject output_channel,
                                   jobject request)
{
    if ((utf8_path == NULL && output_stream == NULL && output_channel == NULL) || request == NULL)
    {
        free(utf8_path);
        throw_illegal_argument_exception(env, "Encoder output and request must not be null.");
        return 0;
    }

    /*
     * Reflection is kept local to this native boundary instead of caching
     * global class references. Encoder open is not on the per-frame hot path,
     * and keeping the lookup here avoids static JNI initialisation order and
     * class-loader lifetime problems.
     */
    jclass request_class = (*env)->GetObjectClass(env, request);
    jmethodID get_sample_rate = NULL;
    jmethodID get_channels = NULL;
    jmethodID get_bits_per_sample = NULL;
    jmethodID get_total_samples_estimate = NULL;
    jmethodID get_compression_level = NULL;
    jmethodID is_verify = NULL;
    jmethodID is_streamable_subset = NULL;
    jmethodID get_block_size = NULL;
    jmethodID get_num_threads = NULL;
    jmethodID get_container = NULL;
    jmethodID get_ogg_serial_number = NULL;
    jmethodID get_comment_entries = NULL;
    jmethodID get_pictures = NULL;
    jmethodID get_application_blocks = NULL;
    jmethodID get_seek_tables = NULL;
    jmethodID get_cue_sheets = NULL;
    jmethodID get_padding_blocks = NULL;
    jmethodID get_unknown_blocks = NULL;
    jmethodID get_metadata_block_types = NULL;
    jmethodID get_metadata_block_values = NULL;
    if (request_class == NULL ||
        !find_instance_method(env, request_class, "getSampleRate", "()I", &get_sample_rate) ||
        !find_instance_method(env, request_class, "getChannels", "()I", &get_channels) ||
        !find_instance_method(env, request_class, "getBitsPerSample", "()I", &get_bits_per_sample) ||
        !find_instance_method(env, request_class, "getTotalSamplesEstimate", "()Ljava/lang/Long;",
                              &get_total_samples_estimate) ||
        !find_instance_method(env, request_class, "getCompressionLevel", "()I", &get_compression_level) ||
        !find_instance_method(env, request_class, "isVerify", "()Z", &is_verify) ||
        !find_instance_method(env, request_class, "isStreamableSubset", "()Z", &is_streamable_subset) ||
        !find_instance_method(env, request_class, "getBlockSize", "()Ljava/lang/Integer;", &get_block_size) ||
        !find_instance_method(env, request_class, "getNumThreads", "()I", &get_num_threads) ||
        !find_instance_method(env, request_class, "getContainer", "()I", &get_container) ||
        !find_instance_method(env, request_class, "getOggSerialNumber", "()Ljava/lang/Integer;",
                              &get_ogg_serial_number) ||
        !find_instance_method(env, request_class, "getCommentEntries", "()[Ljava/lang/String;",
                              &get_comment_entries) ||
        !find_instance_method(env, request_class, "getPictures", "()[Lorg/zzvsjs/jflac/FlacPicture;",
                              &get_pictures) ||
        !find_instance_method(env, request_class, "getApplicationBlocks",
                              "()[Lorg/zzvsjs/jflac/FlacApplicationBlock;", &get_application_blocks) ||
        !find_instance_method(env, request_class, "getSeekTables", "()[Lorg/zzvsjs/jflac/FlacSeekTable;",
                              &get_seek_tables) ||
        !find_instance_method(env, request_class, "getCueSheets", "()[Lorg/zzvsjs/jflac/FlacCueSheet;",
                              &get_cue_sheets) ||
        !find_instance_method(env, request_class, "getPaddingBlocks", "()[Lorg/zzvsjs/jflac/FlacPaddingBlock;",
                              &get_padding_blocks) ||
        !find_instance_method(env, request_class, "getUnknownBlocks",
                              "()[Lorg/zzvsjs/jflac/FlacUnknownMetadataBlock;", &get_unknown_blocks) ||
        !find_instance_method(env, request_class, "getMetadataBlockTypes", "()[I", &get_metadata_block_types) ||
        !find_instance_method(env, request_class, "getMetadataBlockValues", "()[Ljava/lang/Object;",
                              &get_metadata_block_values))
    {
        /*
         * Missing methods normally indicate Java/native version skew. The JVM
         * has already raised NoSuchMethodError, so do not replace it.
         */
        free(utf8_path);
        return 0;
    }

    /*
     * Snapshot the request into native locals before allocating the encoder.
     * Later encoder callbacks should depend only on EncodeContext, not on
     * calling back into the request object.
     */
    jint sample_rate = 0;
    jint channels = 0;
    jint bits_per_sample = 0;
    jint compression_level = 0;
    jboolean verify = JNI_FALSE;
    jboolean streamable_subset = JNI_FALSE;
    jint num_threads = 0;
    jint container = 0;
    jobject total_samples_object = NULL;
    jobject block_size_object = NULL;
    jobject ogg_serial_number_object = NULL;
    jobject comment_entries_value = NULL;
    jobject pictures_value = NULL;
    jobject application_blocks_value = NULL;
    jobject seek_tables_value = NULL;
    jobject cue_sheets_value = NULL;
    jobject padding_blocks_value = NULL;
    jobject unknown_blocks_value = NULL;
    jobject metadata_block_types_value = NULL;
    jobject metadata_block_values_value = NULL;
    if (!call_int_getter(env, request, get_sample_rate, &sample_rate) ||
        !call_int_getter(env, request, get_channels, &channels) ||
        !call_int_getter(env, request, get_bits_per_sample, &bits_per_sample) ||
        !call_int_getter(env, request, get_compression_level, &compression_level) ||
        !call_boolean_getter(env, request, is_verify, &verify) ||
        !call_boolean_getter(env, request, is_streamable_subset, &streamable_subset) ||
        !call_int_getter(env, request, get_num_threads, &num_threads) ||
        !call_int_getter(env, request, get_container, &container) ||
        !call_object_getter(env, request, get_total_samples_estimate, &total_samples_object) ||
        !call_object_getter(env, request, get_block_size, &block_size_object) ||
        !call_object_getter(env, request, get_ogg_serial_number, &ogg_serial_number_object) ||
        !call_object_getter(env, request, get_comment_entries, &comment_entries_value) ||
        !call_object_getter(env, request, get_pictures, &pictures_value) ||
        !call_object_getter(env, request, get_application_blocks, &application_blocks_value) ||
        !call_object_getter(env, request, get_seek_tables, &seek_tables_value) ||
        !call_object_getter(env, request, get_cue_sheets, &cue_sheets_value) ||
        !call_object_getter(env, request, get_padding_blocks, &padding_blocks_value) ||
        !call_object_getter(env, request, get_unknown_blocks, &unknown_blocks_value) ||
        !call_object_getter(env, request, get_metadata_block_types, &metadata_block_types_value) ||
        !call_object_getter(env, request, get_metadata_block_values, &metadata_block_values_value))
    {
        free(utf8_path);
        return 0;
    }

    jobjectArray comment_entries = (jobjectArray)comment_entries_value;
    jobjectArray pictures = (jobjectArray)pictures_value;
    jobjectArray application_blocks = (jobjectArray)application_blocks_value;
    jobjectArray seek_tables = (jobjectArray)seek_tables_value;
    jobjectArray cue_sheets = (jobjectArray)cue_sheets_value;
    jobjectArray padding_blocks = (jobjectArray)padding_blocks_value;
    jobjectArray unknown_blocks = (jobjectArray)unknown_blocks_value;
    jintArray metadata_block_types = (jintArray)metadata_block_types_value;
    jobjectArray metadata_block_values = (jobjectArray)metadata_block_values_value;
    if (!validate_container(env, container))
    {
        free(utf8_path);
        return 0;
    }

    if (container != JFLAC_CONTAINER_OGG && ogg_serial_number_object != NULL)
    {
        free(utf8_path);
        throw_illegal_argument_exception(env, "Ogg serial number can only be set for Ogg FLAC encoding.");
        return 0;
    }

    jclass long_class = NULL;
    jclass integer_class = NULL;
    jmethodID long_value = NULL;
    jmethodID int_value = NULL;
    if (total_samples_object != NULL)
    {
        long_class = (*env)->FindClass(env, "java/lang/Long");
        if (long_class == NULL || !find_instance_method(env, long_class, "longValue", "()J", &long_value))
        {
            free(utf8_path);
            return 0;
        }
    }

    if (block_size_object != NULL || ogg_serial_number_object != NULL)
    {
        integer_class = (*env)->FindClass(env, "java/lang/Integer");
        if (integer_class == NULL || !find_instance_method(env, integer_class, "intValue", "()I", &int_value))
        {
            free(utf8_path);
            return 0;
        }
    }

    if ((*env)->ExceptionCheck(env))
    {
        /* Preserve NoSuchMethodError/ClassNotFoundException from boxed value lookup. */
        free(utf8_path);
        return 0;
    }

    /*
     * Null boxed values mean "caller did not specify this optional encoder
     * setting". Only call the libFLAC setter when the value exists.
     */
    FLAC__uint64 total_samples_estimate = 0;
    uint32_t block_size = 0;
    long ogg_serial_number = 0;
    FLAC__bool has_total_samples_estimate = total_samples_object != NULL;
    FLAC__bool has_block_size = block_size_object != NULL;
    FLAC__bool has_ogg_serial_number = ogg_serial_number_object != NULL;
    if (has_total_samples_estimate)
    {
        jlong value = 0;
        if (!call_long_getter(env, total_samples_object, long_value, &value))
        {
            free(utf8_path);
            return 0;
        }

        total_samples_estimate = (FLAC__uint64)value;
    }

    if (has_block_size)
    {
        jint value = 0;
        if (!call_int_getter(env, block_size_object, int_value, &value))
        {
            free(utf8_path);
            return 0;
        }

        block_size = (uint32_t)value;
    }

    if (has_ogg_serial_number)
    {
        jint value = 0;
        if (!call_int_getter(env, ogg_serial_number_object, int_value, &value))
        {
            free(utf8_path);
            return 0;
        }

        ogg_serial_number = (long)value;
    }

    EncodeContext *context = (EncodeContext *)calloc(1u, sizeof(EncodeContext));
    if (context == NULL)
    {
        free(utf8_path);
        throw_encode_exception(env, "Failed to allocate encoder context.");
        return 0;
    }

    context->channels = (uint32_t)channels;
    /*
     * Prepare the callback target before creating the FLAC encoder. If this
     * fails, destroy_encode_context() can already clean up any global reference
     * made by the prepare helper.
     */
    if (output_stream != NULL && !prepare_encode_output_stream(env, context, output_stream))
    {
        destroy_encode_context(env, context);
        free(utf8_path);
        return 0;
    }

    if (output_channel != NULL && !prepare_encode_output_channel(env, context, output_channel))
    {
        destroy_encode_context(env, context);
        free(utf8_path);
        return 0;
    }

    context->encoder = g_flac_api.stream_encoder_new();
    if (context->encoder == NULL)
    {
        destroy_encode_context(env, context);
        free(utf8_path);
        throw_encode_exception(env, "Failed to allocate FLAC encoder.");
        return 0;
    }

    /*
     * FLAC 1.5 returns a status code here, with zero meaning success. Keep the
     * call separate from the boolean setter chain, and skip the default value
     * so Windows builds without pthread support retain normal encoding.
     */
    if (num_threads != 1)
    {
        uint32_t thread_status =
            g_flac_api.stream_encoder_set_num_threads(context->encoder, (uint32_t)num_threads);
        if (thread_status != FLAC__STREAM_ENCODER_SET_NUM_THREADS_OK)
        {
            destroy_encode_context(env, context);
            free(utf8_path);
            switch (thread_status)
            {
            case FLAC__STREAM_ENCODER_SET_NUM_THREADS_NOT_COMPILED_WITH_MULTITHREADING_ENABLED:
                throw_unsupported_feature_exception(
                    env, "Parallel FLAC encoding is unavailable because the bundled libFLAC was built without pthread support.");
                break;
            case FLAC__STREAM_ENCODER_SET_NUM_THREADS_ALREADY_INITIALIZED:
                throw_encode_exception(env, "FLAC encoder thread count was set after encoder initialisation.");
                break;
            case FLAC__STREAM_ENCODER_SET_NUM_THREADS_TOO_MANY_THREADS:
                throw_illegal_argument_exception(env, "Encoder thread count must be between 1 and 128.");
                break;
            default:
                throw_encode_exception(env, "libFLAC returned an unknown encoder thread configuration status.");
                break;
            }

            return 0;
        }
    }

    /*
     * libFLAC validates settings when each setter runs. A false return gives
     * only encoder state text, so include it in the Java exception while the
     * still-live context can query the encoder.
     */
    if (!g_flac_api.stream_encoder_set_verify(context->encoder, verify ? true : false) ||
        !g_flac_api.stream_encoder_set_streamable_subset(context->encoder, streamable_subset ? true : false) ||
        !g_flac_api.stream_encoder_set_channels(context->encoder, (uint32_t)channels) ||
        !g_flac_api.stream_encoder_set_bits_per_sample(context->encoder, (uint32_t)bits_per_sample) ||
        !g_flac_api.stream_encoder_set_sample_rate(context->encoder, (uint32_t)sample_rate) ||
        !g_flac_api.stream_encoder_set_compression_level(context->encoder, (uint32_t)compression_level) ||
        (has_block_size && !g_flac_api.stream_encoder_set_blocksize(context->encoder, block_size)) ||
        (has_ogg_serial_number &&
         !g_flac_api.stream_encoder_set_ogg_serial_number(context->encoder, ogg_serial_number)) ||
        (has_total_samples_estimate &&
         !g_flac_api.stream_encoder_set_total_samples_estimate(context->encoder, total_samples_estimate)))
    {
        char buffer[JFLAC_MESSAGE_BUFFER_SIZE];
        snprintf(buffer, sizeof(buffer), "Failed to configure FLAC encoder: %s.",
                 encoder_state_or_unknown(context->encoder));
        destroy_encode_context(env, context);
        free(utf8_path);
        throw_encode_exception(env, buffer);
        return 0;
    }

    jsize ordered_metadata_count =
        metadata_block_types != NULL ? (*env)->GetArrayLength(env, metadata_block_types) : (jsize)0;
    /*
     * Ordered metadata is used by metadata round-trip APIs. If present, it is
     * authoritative; otherwise the grouped request fields are converted in the
     * wrapper's normal order.
     */
    FLAC__bool metadata_success =
        ordered_metadata_count > 0
            ? build_ordered_metadata_blocks(env, metadata_block_types, metadata_block_values, context)
            : build_metadata_blocks(env, comment_entries, pictures, application_blocks, seek_tables, cue_sheets,
                                    padding_blocks, unknown_blocks, context);
    if (!metadata_success)
    {
        destroy_encode_context(env, context);
        free(utf8_path);
        if (!(*env)->ExceptionCheck(env))
        {
            throw_encode_exception(env, "Failed to build FLAC metadata blocks.");
        }

        return 0;
    }

    if (context->metadata_count > 0u &&
        !g_flac_api.stream_encoder_set_metadata(context->encoder, context->metadata_blocks, context->metadata_count))
    {
        char buffer[JFLAC_MESSAGE_BUFFER_SIZE];
        snprintf(buffer, sizeof(buffer), "Failed to attach FLAC metadata blocks: %s.",
                 encoder_state_or_unknown(context->encoder));
        destroy_encode_context(env, context);
        free(utf8_path);
        throw_encode_exception(env, buffer);
        return 0;
    }

    /*
     * After set_metadata succeeds, libFLAC references the metadata blocks until
     * the encoder is deleted. EncodeContext still owns and deletes them; it
     * must therefore outlive the FLAC encoder and be destroyed only after
     * finish/release.
     */
    FLAC__StreamEncoderInitStatus init_status;
    if (output_stream != NULL)
    {
        /*
         * Plain OutputStream is sequential. Passing NULL seek/tell callbacks is
         * deliberate and is the source of the documented STREAMINFO caveat.
         */
        init_status = init_stream_encoder_for_container(context->encoder, NULL, encode_write_callback, NULL, NULL, NULL,
                                                        context, container);
    }
    else if (output_channel != NULL)
    {
        /*
         * SeekableByteChannel supplies seek/tell, so libFLAC can return to the
         * header and patch final STREAMINFO fields during finish(). Ogg FLAC
         * also needs read-back while patching pages, so the read callback is
         * registered for both containers and ignored by native FLAC.
         */
        init_status = init_stream_encoder_for_container(context->encoder, encode_channel_read_callback,
                                                        encode_write_callback, encode_channel_seek_callback,
                                                        encode_channel_tell_callback, NULL, context, container);
    }
    else
    {
        init_status = init_file_encoder_for_container(context->encoder, utf8_path, NULL, NULL, container);
    }

    free(utf8_path);
    if (init_status != FLAC__STREAM_ENCODER_INIT_STATUS_OK)
    {
        char buffer[JFLAC_MESSAGE_BUFFER_SIZE];
        snprintf(buffer, sizeof(buffer), "Failed to initialize FLAC encoder output%s%s.",
                 encoder_state_or_unknown(context->encoder) != NULL ? ": " : "",
                 encoder_state_or_unknown(context->encoder));
        destroy_encode_context(env, context);
        if (!(*env)->ExceptionCheck(env))
        {
            throw_encode_exception(env, buffer);
        }

        return 0;
    }

    jlong handle = register_encode_context(context);
    if (handle == 0)
    {
        destroy_encode_context(env, context);
        throw_encode_exception(env, "Failed to register FLAC encoder handle.");
        return 0;
    }

    return handle;
}

/*
 * Opens and configures a file encoder, returning an opaque native handle. File
 * output remains the strongest path because libFLAC owns the seekable file
 * descriptor and can patch final STREAMINFO statistics.
 */
JNIEXPORT jlong JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_openEncoderFile(JNIEnv *env, jclass clazz,
                                                                                      jstring path, jobject request)
{
    (void)clazz;
    if (!flac_api_ready(env))
    {
        return 0;
    }

    if (path == NULL || request == NULL)
    {
        throw_illegal_argument_exception(env, "Encoder path and request must not be null.");
        return 0;
    }

    char *utf8_path = jstring_to_utf8(env, path);
    if (utf8_path == NULL)
    {
        throw_encode_exception(env, "Failed to encode output file path to UTF-8.");
        return 0;
    }

    return open_encoder_internal(env, utf8_path, NULL, NULL, request);
}

/*
 * Opens and configures a sequential OutputStream encoder. The handle remains
 * valid until finishEncoder() finalises and flushes the stream, or
 * releaseEncoder() abandons the encoder after failure.
 */
JNIEXPORT jlong JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_openEncoderStream(JNIEnv *env, jclass clazz,
                                                                                        jobject output_stream,
                                                                                        jobject request)
{
    (void)clazz;
    if (!flac_api_ready(env))
    {
        return 0;
    }

    return open_encoder_internal(env, NULL, output_stream, NULL, request);
}

/*
 * Opens and configures a seekable channel encoder. The channel position at
 * open becomes the FLAC stream origin for all later write/seek/tell callbacks.
 */
JNIEXPORT jlong JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_openEncoderChannel(JNIEnv *env, jclass clazz,
                                                                                         jobject output_channel,
                                                                                         jobject request)
{
    (void)clazz;
    if (!flac_api_ready(env))
    {
        return 0;
    }

    return open_encoder_internal(env, NULL, NULL, output_channel, request);
}

/*
 * Writes one interleaved PCM chunk into an active encoder handle. The sample
 * array is read-only from native code, so ReleaseIntArrayElements uses
 * JNI_ABORT to avoid copying unchanged PCM samples back into Java.
 */
JNIEXPORT void JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_writeEncoderInterleaved(JNIEnv *env, jclass clazz,
                                                                                             jlong handle,
                                                                                             jintArray samples,
                                                                                             jint frames)
{
    (void)clazz;
    EncodeContext *context = acquire_encode_context(env, handle);
    if (context == NULL)
    {
        return;
    }

    if (frames < 0)
    {
        release_encode_context_reference(env, context);
        throw_illegal_argument_exception(env, "Frame count must be non-negative.");
        return;
    }

    if (frames == 0)
    {
        release_encode_context_reference(env, context);
        return;
    }

    if (samples == NULL)
    {
        release_encode_context_reference(env, context);
        throw_illegal_argument_exception(env, "PCM sample buffer must not be null.");
        return;
    }

    jsize sample_count = (*env)->GetArrayLength(env, samples);
    if ((uint64_t)sample_count != ((uint64_t)frames * (uint64_t)context->channels))
    {
        release_encode_context_reference(env, context);
        throw_illegal_argument_exception(env, "Sample array length must equal frames * channels.");
        return;
    }

    jint *elements = (*env)->GetIntArrayElements(env, samples, NULL);
    if (elements == NULL)
    {
        release_encode_context_reference(env, context);
        throw_encode_exception(env, "Failed to access PCM samples for encoding.");
        return;
    }

    FLAC__bool success = g_flac_api.stream_encoder_process_interleaved(context->encoder, (const FLAC__int32 *)elements,
                                                                       (uint32_t)frames);
    /* PCM input is read-only for native code, so there is nothing to copy back. */
    (*env)->ReleaseIntArrayElements(env, samples, elements, JNI_ABORT);

    if (!success && !(*env)->ExceptionCheck(env))
    {
        char buffer[JFLAC_MESSAGE_BUFFER_SIZE];
        snprintf(buffer, sizeof(buffer), "FLAC encoding failed while processing PCM: %s.",
                 encoder_state_or_unknown(context->encoder));
        release_encode_context_reference(env, context);
        throw_encode_exception(env, buffer);
        return;
    }

    release_encode_context_reference(env, context);
}

/*
 * Finalises an encoder handle and releases all owned native state. After this
 * call, the handle is invalid whether finalisation succeeds or fails.
 */
JNIEXPORT void JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_finishEncoder(JNIEnv *env, jclass clazz,
                                                                                   jlong handle)
{
    (void)clazz;
    EncodeContext *context = acquire_encode_context(env, handle);
    if (context == NULL)
    {
        return;
    }

    remove_encode_context(handle);

    FLAC__bool success = g_flac_api.stream_encoder_finish(context->encoder);
    char buffer[JFLAC_MESSAGE_BUFFER_SIZE];
    if (!success)
    {
        snprintf(buffer, sizeof(buffer), "Failed to finalize FLAC encoding: %s.",
                 encoder_state_or_unknown(context->encoder));
    }

    if (success && context->output_stream != NULL && context->output_flush != NULL)
    {
        (*env)->CallVoidMethod(env, context->output_stream, context->output_flush);
        (void)(*env)->ExceptionCheck(env);
    }

    release_encode_context_reference(env, context);
    if ((*env)->ExceptionCheck(env))
    {
        return;
    }

    if (!success)
    {
        throw_encode_exception(env, buffer);
    }
}

/*
 * Releases an encoder handle without finalising; used after failure or abandon
 * paths. This may leave incomplete FLAC output and must not be used as normal
 * successful close.
 */
JNIEXPORT void JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_releaseEncoder(JNIEnv *env, jclass clazz,
                                                                                    jlong handle)
{
    (void)clazz;
    EncodeContext *context = remove_encode_context(handle);
    destroy_encode_context(env, context);
}

JNIEXPORT jlong JNICALL
Java_org_zzvsjs_jflac_internal_NativeBindings_activeEncoderContextCount(JNIEnv *env, jclass clazz)
{
    (void)env;
    (void)clazz;
    return active_encode_context_count();
}
