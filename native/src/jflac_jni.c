/*
 * JNI shim for the Kotlin/Java wrapper.
 *
 * Responsibilities of this layer:
 * - resolve the small subset of libFLAC symbols used by V1
 * - convert Java strings and callbacks into the forms expected by libFLAC
 * - translate native metadata and PCM buffers into JVM-owned objects
 * - keep raw FLAC pointers and lifecycle management hidden from callers
 */
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <windows.h>

#include "FLAC/format.h"
#include "FLAC/metadata.h"
#include "FLAC/stream_decoder.h"
#include "FLAC/stream_encoder.h"
#include <jni.h>

/*
 * Central native constants. These values come from either the FLAC bitstream
 * format (MD5 and APPLICATION lengths) or wrapper policy (message buffer,
 * handle range, and metadata-callback policy flags).
 */
#define JFLAC_MESSAGE_BUFFER_SIZE 256u
#define JFLAC_STREAMINFO_MD5_LENGTH 16u
#define JFLAC_APPLICATION_ID_LENGTH 4u
#define JFLAC_FIRST_VALID_SESSION_HANDLE 1LL
#define JFLAC_EMIT_METADATA_CALLBACKS 0
#define JFLAC_SUPPRESS_METADATA_CALLBACKS 1
#define JFLAC_UNLIMITED_RANGE 0
#define JFLAC_LIMITED_RANGE 1
#define JFLAC_SEEKPOINT_PLACEHOLDER_JAVA_VALUE -1LL
/*
 * FLAC stores metadata body lengths in a 24-bit header field and reserves
 * type codes 7..126 for block types this wrapper may not understand yet.
 */
#define JFLAC_METADATA_MAX_BLOCK_LENGTH 0xFFFFFFu
#define JFLAC_UNKNOWN_METADATA_MIN_TYPE 7
#define JFLAC_UNKNOWN_METADATA_MAX_TYPE 126
/*
 * The FLAC seekpoint placeholder is all bits set. Keep a local constant here
 * instead of linking against libFLAC's exported variable because this shim
 * resolves FLAC.dll symbols dynamically at runtime.
 * The Java-facing value is -1 because signed long cannot represent UINT64_MAX.
 */
#define JFLAC_SEEKPOINT_PLACEHOLDER UINT64_MAX

typedef struct FlacApi
{
    FLAC__Metadata_Chain *(*metadata_chain_new)(void);
    void (*metadata_chain_delete)(FLAC__Metadata_Chain *chain);
    FLAC__bool (*metadata_chain_read)(FLAC__Metadata_Chain *chain, const char *filename);
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
    FLAC__StreamDecoderInitStatus (*stream_decoder_init_file)(FLAC__StreamDecoder *decoder, const char *filename,
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
    FLAC__bool (*stream_decoder_process_until_end_of_metadata)(FLAC__StreamDecoder *decoder);
    FLAC__bool (*stream_decoder_seek_absolute)(FLAC__StreamDecoder *decoder, FLAC__uint64 sample);
    FLAC__bool (*stream_decoder_process_single)(FLAC__StreamDecoder *decoder);
    FLAC__bool (*stream_decoder_process_until_end_of_stream)(FLAC__StreamDecoder *decoder);
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
    FLAC__bool (*stream_encoder_set_total_samples_estimate)(FLAC__StreamEncoder *encoder, FLAC__uint64 value);
    FLAC__bool (*stream_encoder_set_metadata)(FLAC__StreamEncoder *encoder, FLAC__StreamMetadata **metadata,
                                              uint32_t num_blocks);
    FLAC__StreamEncoderInitStatus (*stream_encoder_init_file)(FLAC__StreamEncoder *encoder, const char *filename,
                                                              FLAC__StreamEncoderProgressCallback progress_callback,
                                                              void *client_data);
    FLAC__StreamEncoderInitStatus (*stream_encoder_init_stream)(
        FLAC__StreamEncoder *encoder, FLAC__StreamEncoderWriteCallback write_callback,
        FLAC__StreamEncoderSeekCallback seek_callback, FLAC__StreamEncoderTellCallback tell_callback,
        FLAC__StreamEncoderMetadataCallback metadata_callback, void *client_data);
    FLAC__bool (*stream_encoder_process_interleaved)(FLAC__StreamEncoder *encoder, const FLAC__int32 buffer[],
                                                     uint32_t samples);
    FLAC__bool (*stream_encoder_finish)(FLAC__StreamEncoder *encoder);
    const char *(*stream_encoder_get_resolved_state_string)(const FLAC__StreamEncoder *encoder);

    FLAC__StreamMetadata *(*metadata_object_new)(FLAC__MetadataType type);
    void (*metadata_object_delete)(FLAC__StreamMetadata *object);
    FLAC__bool (*metadata_object_vorbiscomment_entry_from_name_value_pair)(
        FLAC__StreamMetadata_VorbisComment_Entry *entry, const char *field_name, const char *field_value);
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

static FlacApi g_flac_api;
static INIT_ONCE g_flac_init_once = INIT_ONCE_STATIC_INIT;
static char g_flac_init_error[JFLAC_MESSAGE_BUFFER_SIZE] = "";
static int g_flac_api_initialized = 0;

/*
 * Stores the first native initialisation failure in a fixed-size buffer so the
 * Java exception can be raised after InitOnceExecuteOnce returns.
 */
static void set_last_init_error(const char *message)
{
#ifdef _MSC_VER
    strncpy_s(g_flac_init_error, sizeof(g_flac_init_error), message, _TRUNCATE);
#else
    strncpy(g_flac_init_error, message, sizeof(g_flac_init_error) - 1);
    g_flac_init_error[sizeof(g_flac_init_error) - 1] = '\0';
#endif
}

/*
 * Resolves one required libFLAC export and records a useful message if the DLL
 * does not contain the symbol expected by this wrapper build.
 */
static BOOL resolve_symbol(HMODULE module, FARPROC *target, const char *name)
{
    *target = GetProcAddress(module, name);
    if (*target == NULL)
    {
        char buffer[JFLAC_MESSAGE_BUFFER_SIZE];
        snprintf(buffer, sizeof(buffer), "Missing symbol in FLAC.dll: %s", name);
        set_last_init_error(buffer);
        return FALSE;
    }
    return TRUE;
}

/*
 * One-time dynamic resolver for FLAC.dll. The JNI library does not link
 * directly to libFLAC, so all required exports are discovered after the JVM
 * has loaded the bundled FLAC runtime.
 */
static BOOL CALLBACK init_flac_api(PINIT_ONCE init_once, PVOID parameter, PVOID *context)
{
    (void)init_once;
    (void)parameter;
    (void)context;

    g_flac_api_initialized = 0;

    HMODULE module = GetModuleHandleW(L"FLAC.dll");
    if (module == NULL)
    {
        module = LoadLibraryW(L"FLAC.dll");
    }
    if (module == NULL)
    {
        set_last_init_error("FLAC.dll is not loaded.");
        return TRUE;
    }

#define RESOLVE(field, symbol)                                                                                         \
    if (!resolve_symbol(module, (FARPROC *)&g_flac_api.field, #symbol))                                                \
    {                                                                                                                  \
        return TRUE;                                                                                                   \
    }

    RESOLVE(metadata_chain_new, FLAC__metadata_chain_new);
    RESOLVE(metadata_chain_delete, FLAC__metadata_chain_delete);
    RESOLVE(metadata_chain_read, FLAC__metadata_chain_read);
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
    RESOLVE(stream_decoder_init_file, FLAC__stream_decoder_init_file);
    RESOLVE(stream_decoder_init_stream, FLAC__stream_decoder_init_stream);
    RESOLVE(stream_decoder_process_until_end_of_metadata, FLAC__stream_decoder_process_until_end_of_metadata);
    RESOLVE(stream_decoder_seek_absolute, FLAC__stream_decoder_seek_absolute);
    RESOLVE(stream_decoder_process_single, FLAC__stream_decoder_process_single);
    RESOLVE(stream_decoder_process_until_end_of_stream, FLAC__stream_decoder_process_until_end_of_stream);
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
    RESOLVE(stream_encoder_set_total_samples_estimate, FLAC__stream_encoder_set_total_samples_estimate);
    RESOLVE(stream_encoder_set_metadata, FLAC__stream_encoder_set_metadata);
    RESOLVE(stream_encoder_init_file, FLAC__stream_encoder_init_file);
    RESOLVE(stream_encoder_init_stream, FLAC__stream_encoder_init_stream);
    RESOLVE(stream_encoder_process_interleaved, FLAC__stream_encoder_process_interleaved);
    RESOLVE(stream_encoder_finish, FLAC__stream_encoder_finish);
    RESOLVE(stream_encoder_get_resolved_state_string, FLAC__stream_encoder_get_resolved_state_string);
    RESOLVE(metadata_object_new, FLAC__metadata_object_new);
    RESOLVE(metadata_object_delete, FLAC__metadata_object_delete);
    RESOLVE(metadata_object_vorbiscomment_entry_from_name_value_pair,
            FLAC__metadata_object_vorbiscomment_entry_from_name_value_pair);
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
    g_flac_api_initialized = 1;
    return TRUE;
}

/*
 * Ensures libFLAC exports are available before a JNI entry point does native
 * work. Failure is translated into the public NativeLoadException.
 */
static int flac_api_ready(JNIEnv *env)
{
    InitOnceExecuteOnce(&g_flac_init_once, init_flac_api, NULL, NULL);
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

/* Raises the public decode exception from native read/decode failures. */
static void throw_decode_exception(JNIEnv *env, const char *message)
{
    jclass exception_class = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacDecodeException");
    if (exception_class != NULL)
    {
        (*env)->ThrowNew(env, exception_class, message);
    }
}

/* Raises the public encode exception from native encode failures. */
static void throw_encode_exception(JNIEnv *env, const char *message)
{
    jclass exception_class = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacEncodeException");
    if (exception_class != NULL)
    {
        (*env)->ThrowNew(env, exception_class, message);
    }
}

/* Raises the public metadata edit exception from native edit failures. */
static void throw_metadata_edit_exception(JNIEnv *env, const char *message)
{
    jclass exception_class = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacMetadataEditException");
    if (exception_class != NULL)
    {
        (*env)->ThrowNew(env, exception_class, message);
    }
}

/* Raises IllegalArgumentException for caller contract violations. */
static void throw_illegal_argument_exception(JNIEnv *env, const char *message)
{
    jclass exception_class = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
    if (exception_class != NULL)
    {
        (*env)->ThrowNew(env, exception_class, message);
    }
}

/* Raises IllegalStateException for invalid native handle/session state. */
static void throw_illegal_state_exception(JNIEnv *env, const char *message)
{
    jclass exception_class = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (exception_class != NULL)
    {
        (*env)->ThrowNew(env, exception_class, message);
    }
}

/* Maps libFLAC metadata-chain statuses without relying on exported strings. */
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
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    jclass charsets_class = (*env)->FindClass(env, "java/nio/charset/StandardCharsets");
    if (string_class == NULL || charsets_class == NULL)
    {
        return NULL;
    }

    jmethodID ctor = (*env)->GetMethodID(env, string_class, "<init>", "([BLjava/nio/charset/Charset;)V");
    jfieldID utf8_field = (*env)->GetStaticFieldID(env, charsets_class, "UTF_8", "Ljava/nio/charset/Charset;");
    if (ctor == NULL || utf8_field == NULL)
    {
        return NULL;
    }

    jobject charset = (*env)->GetStaticObjectField(env, charsets_class, utf8_field);
    jbyteArray byte_array = (*env)->NewByteArray(env, (jsize)length);
    if (byte_array == NULL || charset == NULL)
    {
        return NULL;
    }

    if (length > 0u)
    {
        (*env)->SetByteArrayRegion(env, byte_array, 0, (jsize)length, (const jbyte *)bytes);
        if ((*env)->ExceptionCheck(env))
        {
            return NULL;
        }
    }

    return (*env)->NewObject(env, string_class, ctor, byte_array, charset);
}

/* Converts libFLAC STREAMINFO into the public FlacStreamInfo model. */
static jobject new_stream_info(JNIEnv *env, const FLAC__StreamMetadata_StreamInfo *stream_info)
{
    jclass clazz = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacStreamInfo");
    if (clazz == NULL)
    {
        return NULL;
    }
    jmethodID ctor = (*env)->GetMethodID(env, clazz, "<init>", "(IIIJIIII[B)V");
    if (ctor == NULL)
    {
        return NULL;
    }

    jbyteArray md5_signature = (*env)->NewByteArray(env, (jsize)JFLAC_STREAMINFO_MD5_LENGTH);
    if (md5_signature == NULL)
    {
        return NULL;
    }
    (*env)->SetByteArrayRegion(env, md5_signature, 0, (jsize)JFLAC_STREAMINFO_MD5_LENGTH,
                               (const jbyte *)stream_info->md5sum);
    if ((*env)->ExceptionCheck(env))
    {
        return NULL;
    }

    return (*env)->NewObject(env, clazz, ctor, (jint)stream_info->sample_rate, (jint)stream_info->channels,
                             (jint)stream_info->bits_per_sample, (jlong)stream_info->total_samples,
                             (jint)stream_info->min_blocksize, (jint)stream_info->max_blocksize,
                             (jint)stream_info->min_framesize, (jint)stream_info->max_framesize, md5_signature);
}

/* Converts an APPLICATION metadata block into the public JVM model. */
static jobject new_application_block(JNIEnv *env, const FLAC__StreamMetadata *block)
{
    const FLAC__StreamMetadata_Application *application = &block->data.application;
    uint32_t data_length =
        block->length >= JFLAC_APPLICATION_ID_LENGTH ? block->length - JFLAC_APPLICATION_ID_LENGTH : 0u;
    jclass clazz = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacApplicationBlock");
    if (clazz == NULL)
    {
        return NULL;
    }
    jmethodID ctor = (*env)->GetMethodID(env, clazz, "<init>", "([B[B)V");
    if (ctor == NULL)
    {
        return NULL;
    }

    jbyteArray id = (*env)->NewByteArray(env, (jsize)JFLAC_APPLICATION_ID_LENGTH);
    if (data_length > (uint32_t)INT_MAX)
    {
        throw_decode_exception(env, "APPLICATION metadata block is too large for one JVM ByteArray.");
        return NULL;
    }

    jbyteArray data = (*env)->NewByteArray(env, (jsize)data_length);
    if (id == NULL || data == NULL)
    {
        return NULL;
    }

    (*env)->SetByteArrayRegion(env, id, 0, (jsize)JFLAC_APPLICATION_ID_LENGTH, (const jbyte *)application->id);
    if ((*env)->ExceptionCheck(env))
    {
        return NULL;
    }

    if (data_length > 0u)
    {
        (*env)->SetByteArrayRegion(env, data, 0, (jsize)data_length, (const jbyte *)application->data);
        if ((*env)->ExceptionCheck(env))
        {
            return NULL;
        }
    }

    return (*env)->NewObject(env, clazz, ctor, id, data);
}

/* Converts a libFLAC PADDING block into the public JVM model. */
static jobject new_padding_block(JNIEnv *env, const FLAC__StreamMetadata *block)
{
    jclass clazz = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacPaddingBlock");
    if (clazz == NULL)
    {
        return NULL;
    }
    jmethodID ctor = (*env)->GetMethodID(env, clazz, "<init>", "(I)V");
    if (ctor == NULL)
    {
        return NULL;
    }

    return (*env)->NewObject(env, clazz, ctor, (jint)block->length);
}

/* Converts an opaque libFLAC metadata block into the public JVM model. */
static jobject new_unknown_metadata_block(JNIEnv *env, const FLAC__StreamMetadata *block)
{
    if (block->length > (uint32_t)INT_MAX)
    {
        throw_decode_exception(env, "Unknown metadata block is too large for one JVM ByteArray.");
        return NULL;
    }

    jclass clazz = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacUnknownMetadataBlock");
    if (clazz == NULL)
    {
        return NULL;
    }
    jmethodID ctor = (*env)->GetMethodID(env, clazz, "<init>", "(I[B)V");
    if (ctor == NULL)
    {
        return NULL;
    }

    jbyteArray data = (*env)->NewByteArray(env, (jsize)block->length);
    if (data == NULL)
    {
        return NULL;
    }
    if (block->length > 0u)
    {
        (*env)->SetByteArrayRegion(env, data, 0, (jsize)block->length, (const jbyte *)block->data.unknown.data);
        if ((*env)->ExceptionCheck(env))
        {
            return NULL;
        }
    }

    return (*env)->NewObject(env, clazz, ctor, (jint)block->type, data);
}

/*
 * Converts a libFLAC SEEKTABLE into JVM seek points. The FLAC placeholder
 * sample number is represented as -1 because Java long has no unsigned value.
 */
static jobject new_seek_table(JNIEnv *env, const FLAC__StreamMetadata_SeekTable *seek_table)
{
    jclass point_class = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacSeekPoint");
    if (point_class == NULL)
    {
        return NULL;
    }
    jmethodID point_ctor = (*env)->GetMethodID(env, point_class, "<init>", "(JJI)V");
    jclass list_class = (*env)->FindClass(env, "java/util/ArrayList");
    if (list_class == NULL)
    {
        return NULL;
    }
    jmethodID list_ctor = (*env)->GetMethodID(env, list_class, "<init>", "(I)V");
    jmethodID list_add = (*env)->GetMethodID(env, list_class, "add", "(Ljava/lang/Object;)Z");
    jclass table_class = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacSeekTable");
    if (table_class == NULL)
    {
        return NULL;
    }
    jmethodID table_ctor = (*env)->GetMethodID(env, table_class, "<init>", "(Ljava/util/List;)V");
    if (point_ctor == NULL || list_ctor == NULL || list_add == NULL || table_ctor == NULL)
    {
        return NULL;
    }

    jobject points = (*env)->NewObject(env, list_class, list_ctor, (jint)seek_table->num_points);
    if (points == NULL)
    {
        return NULL;
    }

    for (uint32_t i = 0; i < seek_table->num_points; ++i)
    {
        const FLAC__StreamMetadata_SeekPoint *native_point = &seek_table->points[i];
        jobject point = (*env)->NewObject(env, point_class, point_ctor,
                                          (jlong)(native_point->sample_number == JFLAC_SEEKPOINT_PLACEHOLDER
                                                      ? JFLAC_SEEKPOINT_PLACEHOLDER_JAVA_VALUE
                                                      : native_point->sample_number),
                                          (jlong)native_point->stream_offset, (jint)native_point->frame_samples);
        if (point == NULL)
        {
            return NULL;
        }

        (*env)->CallBooleanMethod(env, points, list_add, point);
        (*env)->DeleteLocalRef(env, point);
        if ((*env)->ExceptionCheck(env))
        {
            return NULL;
        }
    }

    return (*env)->NewObject(env, table_class, table_ctor, points);
}

/* Converts one libFLAC CUESHEET index into the public FlacCueSheetIndex model. */
static jobject new_cue_sheet_index(JNIEnv *env, const FLAC__StreamMetadata_CueSheet_Index *index)
{
    jclass index_class = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacCueSheetIndex");
    if (index_class == NULL)
    {
        return NULL;
    }
    jmethodID index_ctor = (*env)->GetMethodID(env, index_class, "<init>", "(JI)V");
    if (index_ctor == NULL)
    {
        return NULL;
    }

    return (*env)->NewObject(env, index_class, index_ctor, (jlong)index->offset, (jint)index->number);
}

/* Converts one libFLAC CUESHEET track and its indices into the public model. */
static jobject new_cue_sheet_track(JNIEnv *env, const FLAC__StreamMetadata_CueSheet_Track *track)
{
    jclass list_class = (*env)->FindClass(env, "java/util/ArrayList");
    jclass track_class = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacCueSheetTrack");
    if (list_class == NULL || track_class == NULL)
    {
        return NULL;
    }
    jmethodID list_ctor = (*env)->GetMethodID(env, list_class, "<init>", "(I)V");
    jmethodID list_add = (*env)->GetMethodID(env, list_class, "add", "(Ljava/lang/Object;)Z");
    jmethodID track_ctor = (*env)->GetMethodID(env, track_class, "<init>", "(JILjava/lang/String;IZLjava/util/List;)V");
    if (list_ctor == NULL || list_add == NULL || track_ctor == NULL)
    {
        return NULL;
    }

    jobject indices = (*env)->NewObject(env, list_class, list_ctor, (jint)track->num_indices);
    if (indices == NULL)
    {
        return NULL;
    }

    for (uint32_t i = 0; i < track->num_indices; ++i)
    {
        jobject index = new_cue_sheet_index(env, &track->indices[i]);
        if (index == NULL)
        {
            return NULL;
        }
        (*env)->CallBooleanMethod(env, indices, list_add, index);
        (*env)->DeleteLocalRef(env, index);
        if ((*env)->ExceptionCheck(env))
        {
            return NULL;
        }
    }

    jobject isrc = new_utf8_string(env, track->isrc, fixed_ascii_length(track->isrc, sizeof(track->isrc) - 1u));
    if (isrc == NULL)
    {
        return NULL;
    }

    return (*env)->NewObject(env, track_class, track_ctor, (jlong)track->offset, (jint)track->number, isrc,
                             (jint)track->type, track->pre_emphasis ? JNI_TRUE : JNI_FALSE, indices);
}

/* Converts one libFLAC CUESHEET block into the public FlacCueSheet model. */
static jobject new_cue_sheet(JNIEnv *env, const FLAC__StreamMetadata_CueSheet *cue_sheet)
{
    jclass list_class = (*env)->FindClass(env, "java/util/ArrayList");
    jclass cue_sheet_class = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacCueSheet");
    if (list_class == NULL || cue_sheet_class == NULL)
    {
        return NULL;
    }
    jmethodID list_ctor = (*env)->GetMethodID(env, list_class, "<init>", "(I)V");
    jmethodID list_add = (*env)->GetMethodID(env, list_class, "add", "(Ljava/lang/Object;)Z");
    jmethodID cue_sheet_ctor =
        (*env)->GetMethodID(env, cue_sheet_class, "<init>", "(Ljava/lang/String;JZLjava/util/List;)V");
    if (list_ctor == NULL || list_add == NULL || cue_sheet_ctor == NULL)
    {
        return NULL;
    }

    jobject tracks = (*env)->NewObject(env, list_class, list_ctor, (jint)cue_sheet->num_tracks);
    if (tracks == NULL)
    {
        return NULL;
    }

    for (uint32_t i = 0; i < cue_sheet->num_tracks; ++i)
    {
        jobject track = new_cue_sheet_track(env, &cue_sheet->tracks[i]);
        if (track == NULL)
        {
            return NULL;
        }
        (*env)->CallBooleanMethod(env, tracks, list_add, track);
        (*env)->DeleteLocalRef(env, track);
        if ((*env)->ExceptionCheck(env))
        {
            return NULL;
        }
    }

    jobject media_catalog_number = new_utf8_string(
        env, cue_sheet->media_catalog_number,
        fixed_ascii_length(cue_sheet->media_catalog_number, sizeof(cue_sheet->media_catalog_number) - 1u));
    if (media_catalog_number == NULL)
    {
        return NULL;
    }

    return (*env)->NewObject(env, cue_sheet_class, cue_sheet_ctor, media_catalog_number, (jlong)cue_sheet->lead_in,
                             cue_sheet->is_cd ? JNI_TRUE : JNI_FALSE, tracks);
}

/* Converts a libFLAC PICTURE block into the public FlacPicture model. */
static jobject new_picture(JNIEnv *env, const FLAC__StreamMetadata_Picture *picture)
{
    jclass clazz = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacPicture");
    if (clazz == NULL)
    {
        return NULL;
    }
    jmethodID ctor = (*env)->GetMethodID(env, clazz, "<init>", "(ILjava/lang/String;Ljava/lang/String;IIII[B)V");
    if (ctor == NULL)
    {
        return NULL;
    }

    jobject mime_type = new_utf8_string(env, picture->mime_type, strlen(picture->mime_type));
    jobject description =
        new_utf8_string(env, (const char *)picture->description, strlen((const char *)picture->description));
    jbyteArray data = (*env)->NewByteArray(env, (jsize)picture->data_length);
    if (mime_type == NULL || description == NULL || data == NULL)
    {
        return NULL;
    }

    if (picture->data_length > 0u)
    {
        (*env)->SetByteArrayRegion(env, data, 0, (jsize)picture->data_length, (const jbyte *)picture->data);
        if ((*env)->ExceptionCheck(env))
        {
            return NULL;
        }
    }

    return (*env)->NewObject(env, clazz, ctor, (jint)picture->type, mime_type, description, (jint)picture->width,
                             (jint)picture->height, (jint)picture->depth, (jint)picture->colors, data);
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
 */
JNIEXPORT jobject JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_readMetadata(JNIEnv *env, jclass clazz,
                                                                                     jstring path)
{
    (void)clazz;
    if (!flac_api_ready(env))
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

    if (!g_flac_api.metadata_chain_read(chain, utf8_path))
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

    jobject stream_info = new_stream_info(env, &streaminfo_block->data.stream_info);
    if (stream_info == NULL)
    {
        g_flac_api.metadata_iterator_delete(iterator);
        g_flac_api.metadata_chain_delete(chain);
        return NULL;
    }

    jobject vendor = NULL;
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    jobjectArray comment_entries = (*env)->NewObjectArray(env, 0, string_class, NULL);
    if (comment_entries == NULL)
    {
        g_flac_api.metadata_iterator_delete(iterator);
        g_flac_api.metadata_chain_delete(chain);
        return NULL;
    }

    if (vorbis_block != NULL)
    {
        const FLAC__StreamMetadata_VorbisComment *vorbis_comment = &vorbis_block->data.vorbis_comment;
        vendor = new_utf8_string(env, (const char *)vorbis_comment->vendor_string.entry,
                                 vorbis_comment->vendor_string.length);
        comment_entries = (*env)->NewObjectArray(env, (jsize)vorbis_comment->num_comments, string_class, NULL);
        if (comment_entries == NULL)
        {
            g_flac_api.metadata_iterator_delete(iterator);
            g_flac_api.metadata_chain_delete(chain);
            return NULL;
        }

        for (uint32_t i = 0; i < vorbis_comment->num_comments; ++i)
        {
            jobject entry = new_utf8_string(env, (const char *)vorbis_comment->comments[i].entry,
                                            vorbis_comment->comments[i].length);
            if (entry == NULL)
            {
                g_flac_api.metadata_iterator_delete(iterator);
                g_flac_api.metadata_chain_delete(chain);
                return NULL;
            }
            (*env)->SetObjectArrayElement(env, comment_entries, (jsize)i, entry);
            if ((*env)->ExceptionCheck(env))
            {
                g_flac_api.metadata_iterator_delete(iterator);
                g_flac_api.metadata_chain_delete(chain);
                return NULL;
            }
        }
    }

    jclass picture_class = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacPicture");
    jobjectArray pictures = (*env)->NewObjectArray(env, picture_count, picture_class, NULL);
    if (pictures == NULL)
    {
        g_flac_api.metadata_iterator_delete(iterator);
        g_flac_api.metadata_chain_delete(chain);
        return NULL;
    }

    jint picture_index = 0;
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
            if ((*env)->ExceptionCheck(env))
            {
                g_flac_api.metadata_iterator_delete(iterator);
                g_flac_api.metadata_chain_delete(chain);
                return NULL;
            }
        }
    } while (g_flac_api.metadata_iterator_next(iterator));

    jclass application_class = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacApplicationBlock");
    jobjectArray application_blocks = (*env)->NewObjectArray(env, application_count, application_class, NULL);
    if (application_blocks == NULL)
    {
        g_flac_api.metadata_iterator_delete(iterator);
        g_flac_api.metadata_chain_delete(chain);
        return NULL;
    }

    jint application_index = 0;
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
            (*env)->DeleteLocalRef(env, application);
            if ((*env)->ExceptionCheck(env))
            {
                g_flac_api.metadata_iterator_delete(iterator);
                g_flac_api.metadata_chain_delete(chain);
                return NULL;
            }
        }
    } while (g_flac_api.metadata_iterator_next(iterator));

    jclass seek_table_class = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacSeekTable");
    jobjectArray seek_tables = (*env)->NewObjectArray(env, seek_table_count, seek_table_class, NULL);
    if (seek_tables == NULL)
    {
        g_flac_api.metadata_iterator_delete(iterator);
        g_flac_api.metadata_chain_delete(chain);
        return NULL;
    }

    jint seek_table_index = 0;
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
            (*env)->DeleteLocalRef(env, seek_table);
            if ((*env)->ExceptionCheck(env))
            {
                g_flac_api.metadata_iterator_delete(iterator);
                g_flac_api.metadata_chain_delete(chain);
                return NULL;
            }
        }
    } while (g_flac_api.metadata_iterator_next(iterator));

    jclass cue_sheet_class = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacCueSheet");
    jobjectArray cue_sheets = (*env)->NewObjectArray(env, cue_sheet_count, cue_sheet_class, NULL);
    if (cue_sheets == NULL)
    {
        g_flac_api.metadata_iterator_delete(iterator);
        g_flac_api.metadata_chain_delete(chain);
        return NULL;
    }

    jint cue_sheet_index = 0;
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
            (*env)->DeleteLocalRef(env, cue_sheet);
            if ((*env)->ExceptionCheck(env))
            {
                g_flac_api.metadata_iterator_delete(iterator);
                g_flac_api.metadata_chain_delete(chain);
                return NULL;
            }
        }
    } while (g_flac_api.metadata_iterator_next(iterator));

    jclass padding_class = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacPaddingBlock");
    jobjectArray padding_blocks = (*env)->NewObjectArray(env, padding_count, padding_class, NULL);
    if (padding_blocks == NULL)
    {
        g_flac_api.metadata_iterator_delete(iterator);
        g_flac_api.metadata_chain_delete(chain);
        return NULL;
    }

    jint padding_index = 0;
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
            (*env)->DeleteLocalRef(env, padding);
            if ((*env)->ExceptionCheck(env))
            {
                g_flac_api.metadata_iterator_delete(iterator);
                g_flac_api.metadata_chain_delete(chain);
                return NULL;
            }
        }
    } while (g_flac_api.metadata_iterator_next(iterator));

    jclass unknown_class = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacUnknownMetadataBlock");
    jobjectArray unknown_blocks = (*env)->NewObjectArray(env, unknown_count, unknown_class, NULL);
    if (unknown_blocks == NULL)
    {
        g_flac_api.metadata_iterator_delete(iterator);
        g_flac_api.metadata_chain_delete(chain);
        return NULL;
    }

    jint unknown_index = 0;
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
            (*env)->DeleteLocalRef(env, unknown);
            if ((*env)->ExceptionCheck(env))
            {
                g_flac_api.metadata_iterator_delete(iterator);
                g_flac_api.metadata_chain_delete(chain);
                return NULL;
            }
        }
    } while (g_flac_api.metadata_iterator_next(iterator));

    jintArray metadata_block_types = (*env)->NewIntArray(env, ordered_block_count);
    jintArray metadata_block_indices = (*env)->NewIntArray(env, ordered_block_count);
    if (metadata_block_types == NULL || metadata_block_indices == NULL)
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
    jmethodID payload_ctor =
        (*env)->GetMethodID(env, payload_class, "<init>",
                            "(Lorg/zzvsjs/jflac/FlacStreamInfo;Ljava/lang/String;[Ljava/lang/String;[Lorg/zzvsjs/jflac/"
                            "FlacPicture;[Lorg/zzvsjs/jflac/FlacApplicationBlock;[Lorg/zzvsjs/jflac/FlacSeekTable;"
                            "[Lorg/zzvsjs/jflac/FlacCueSheet;[Lorg/zzvsjs/jflac/FlacPaddingBlock;"
                            "[Lorg/zzvsjs/jflac/FlacUnknownMetadataBlock;[I[I)V");
    if (payload_class == NULL || payload_ctor == NULL)
    {
        g_flac_api.metadata_iterator_delete(iterator);
        g_flac_api.metadata_chain_delete(chain);
        return NULL;
    }

    jobject payload = (*env)->NewObject(env, payload_class, payload_ctor, stream_info, vendor, comment_entries,
                                        pictures, application_blocks, seek_tables, cue_sheets, padding_blocks,
                                        unknown_blocks, metadata_block_types, metadata_block_indices);
    g_flac_api.metadata_iterator_delete(iterator);
    g_flac_api.metadata_chain_delete(chain);
    return payload;
}

/* Exposes FLAC__format_sample_rate_is_valid to Java/Kotlin callers. */
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

/* Exposes FLAC__format_sample_rate_is_subset to Java/Kotlin callers. */
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

/* Exposes FLAC__format_blocksize_is_subset with the required sample rate. */
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

/* Checks a Vorbis comment field name with libFLAC's legality helper. */
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

/* Checks a Vorbis comment field value with libFLAC's legality helper. */
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

/* Checks a complete KEY=value Vorbis comment entry with libFLAC. */
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
 * block, then returning libFLAC's
 * violation message when the block is illegal.
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
     * SeekableByteChannel decode uses the same libFLAC stream API as
     * InputStream, but also supplies seek/tell/length/eof callbacks. The
     * global reference is needed for reusable decoder sessions.
     */
    JavaVM *jvm;
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

    /*
     * Range decode is frame-limited, where one frame means one sample per
     * channel. The write callback uses these fields to crop the final libFLAC
     * block before it is copied into a JVM IntArray.
     */
    int range_limited;
    FLAC__uint64 max_frames;
    FLAC__uint64 emitted_frames;
    int range_complete;
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

static INIT_ONCE g_decode_session_registry_once = INIT_ONCE_STATIC_INIT;
static CRITICAL_SECTION g_decode_session_registry_lock;
static DecodeSessionRegistryEntry *g_decode_session_registry = NULL;
static jlong g_next_decode_session_handle = JFLAC_FIRST_VALID_SESSION_HANDLE;

/*
 * The session registry is process-local because JNI callers can pass any long
 * value back into native code. Looking up handles in this list gives us a
 * controlled IllegalStateException for 0, stale handles, and arbitrary forged
 * values, rather than dereferencing attacker- or bug-supplied addresses.
 */
static BOOL CALLBACK init_decode_session_registry(PINIT_ONCE init_once, PVOID parameter, PVOID *context)
{
    (void)init_once;
    (void)parameter;
    (void)context;
    InitializeCriticalSection(&g_decode_session_registry_lock);
    return TRUE;
}

/* Enters the process-wide registry lock for reusable decoder sessions. */
static void lock_decode_session_registry(void)
{
    InitOnceExecuteOnce(&g_decode_session_registry_once, init_decode_session_registry, NULL, NULL);
    EnterCriticalSection(&g_decode_session_registry_lock);
}

/* Leaves the process-wide registry lock for reusable decoder sessions. */
static void unlock_decode_session_registry(void)
{
    LeaveCriticalSection(&g_decode_session_registry_lock);
}

/* Deletes a global seekable-channel reference held by a decode context. */
static void clear_decode_seekable_channel(DecodeContext *context)
{
    if (context == NULL || context->seekable_channel == NULL || context->jvm == NULL)
    {
        return;
    }

    JNIEnv *env = NULL;
    if ((*context->jvm)->GetEnv(context->jvm, (void **)&env, JNI_VERSION_1_8) == JNI_OK && env != NULL)
    {
        (*env)->DeleteGlobalRef(env, context->seekable_channel);
    }
    context->seekable_channel = NULL;
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

/* Releases the libFLAC decoder and heap storage owned by one session. */
static void destroy_decode_session(DecodeSession *session)
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
    clear_decode_seekable_channel(&session->context);
    free(session);
}

/* Adds a new decoder session to the registry and returns its handle. */
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
        if (entry != NULL && entry->session != NULL && !entry->session->released && !entry->session->in_use &&
            entry->session->decoder != NULL)
        {
            /*
             * The registry lock covers the transition from idle to in-use.
             * That makes direct JNI calls safe even if Java bypasses the
             * higher-level Kotlin session object and calls decodeDecoderRange
             * twice from different threads.
             */
            session = entry->session;
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
 * already removed it from the active
 * registry.
 */
static void release_decode_session_reference(DecodeSession *session)
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
        destroy_decode_session(session);
    }
}

/*
 * Removes a decoder session handle from the active registry. The caller either
 * receives the session for immediate
 * destruction or leaves destruction to the
 * final operation reference.
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
    context->input_stream = NULL;
    context->input_read = NULL;
    context->saw_error = 0;
    context->suppress_metadata = 0;
    context->range_limited = 0;
    context->max_frames = 0;
    context->emitted_frames = 0;
    context->range_complete = 0;
    if (context->on_stream_info == NULL || context->on_pcm_interleaved == NULL || context->on_complete == NULL)
    {
        return 0;
    }
    return 1;
}

/* Configures optional range limiting for the decode write callback. */
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

/* Validates common seek/range arguments before libFLAC is called. */
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

/* Checks whether a range starts beyond the STREAMINFO total sample count. */
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

/* Checks whether a range starts exactly at end-of-stream. */
static int decode_range_starts_at_stream_end(DecodeContext *context, jlong first_sample)
{
    return context->saw_stream_info && context->stream_total_samples > 0u &&
           (FLAC__uint64)first_sample == context->stream_total_samples;
}

/*
 * Processes the stream one frame at a time so a range-limited decode can stop
 * immediately after the callback emits the requested final frame.
 */
static FLAC__bool process_decode_stream(FLAC__StreamDecoder *decoder, DecodeContext *context)
{
    if (!context->range_limited)
    {
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

/* Throws the public exception for a decode range that starts after EOF. */
static void throw_range_after_stream_exception(JNIEnv *env)
{
    throw_decode_exception(env, "Requested decode range starts after the end of the FLAC stream.");
}

/* Maps libFLAC decoder error callback statuses to stable diagnostic text. */
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

/* Resolves InputStream.read(byte[], int, int) for sequential stream decode. */
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

/* Resolves SeekableByteChannel callbacks and promotes the channel reference. */
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
    if (context->channel_read == NULL || context->channel_position == NULL || context->channel_seek == NULL ||
        context->channel_size == NULL)
    {
        return 0;
    }

    if ((*env)->GetJavaVM(env, &context->jvm) != JNI_OK)
    {
        throw_decode_exception(env, "Failed to access JVM for channel decoder callbacks.");
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
        clear_decode_seekable_channel(context);
        return 0;
    }
    if (context->channel_base_offset < 0)
    {
        clear_decode_seekable_channel(context);
        throw_decode_exception(env, "SeekableByteChannel returned a negative position.");
        return 0;
    }

    return 1;
}

/* libFLAC read callback backed by java.nio.channels.SeekableByteChannel. */
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
    (*env)->DeleteLocalRef(env, byte_buffer);
    if ((*env)->ExceptionCheck(env))
    {
        *bytes = 0u;
        return FLAC__STREAM_DECODER_READ_STATUS_ABORT;
    }

    if (read < 0)
    {
        *bytes = 0u;
        return FLAC__STREAM_DECODER_READ_STATUS_END_OF_STREAM;
    }
    if (read == 0)
    {
        *bytes = 0u;
        throw_decode_exception(env, "SeekableByteChannel returned zero bytes for a non-empty read request.");
        return FLAC__STREAM_DECODER_READ_STATUS_ABORT;
    }

    *bytes = (size_t)read;
    return FLAC__STREAM_DECODER_READ_STATUS_CONTINUE;
}

/* libFLAC seek callback backed by SeekableByteChannel.position(long). */
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
    if (ignored != NULL)
    {
        (*env)->DeleteLocalRef(env, ignored);
    }
    return (*env)->ExceptionCheck(env) ? FLAC__STREAM_DECODER_SEEK_STATUS_ERROR : FLAC__STREAM_DECODER_SEEK_STATUS_OK;
}

/* libFLAC tell callback backed by SeekableByteChannel.position(). */
static FLAC__StreamDecoderTellStatus decode_channel_tell_callback(const FLAC__StreamDecoder *decoder,
                                                                  FLAC__uint64 *absolute_byte_offset,
                                                                  void *client_data)
{
    (void)decoder;
    DecodeContext *context = (DecodeContext *)client_data;
    JNIEnv *env = context->env;

    if (context->seekable_channel == NULL || context->channel_position == NULL || absolute_byte_offset == NULL)
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

/* libFLAC length callback backed by SeekableByteChannel.size(). */
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

/* libFLAC EOF callback backed by current channel position and size. */
static FLAC__bool decode_channel_eof_callback(const FLAC__StreamDecoder *decoder, void *client_data)
{
    (void)decoder;
    DecodeContext *context = (DecodeContext *)client_data;
    JNIEnv *env = context->env;

    if (context->seekable_channel == NULL || context->channel_position == NULL || context->channel_size == NULL)
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
    if (context != NULL && context->seekable_channel != NULL)
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
        *bytes = 0u;
        return FLAC__STREAM_DECODER_READ_STATUS_END_OF_STREAM;
    }
    if (read == 0)
    {
        (*env)->DeleteLocalRef(env, chunk);
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
    (*env)->DeleteLocalRef(env, samples);
    if ((*env)->ExceptionCheck(env))
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

    /*
     * Always capture STREAMINFO totals, even when Java metadata delivery is
     * suppressed for reusable sessions. Range validation needs total_samples to
     * distinguish an empty range at EOF from a range that starts past EOF.
     */
    context->saw_stream_info = 1;
    context->stream_total_samples = metadata->data.stream_info.total_samples;
    if (context->consumer == NULL || context->suppress_metadata)
    {
        return;
    }

    jobject stream_info = new_stream_info(context->env, &metadata->data.stream_info);
    if (stream_info == NULL)
    {
        return;
    }

    (*context->env)->CallVoidMethod(context->env, context->consumer, context->on_stream_info, stream_info);
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
 * Shared one-shot file decode implementation for full, seeked, and ranged
 * public JNI entry points.
 */
static void decode_file_internal(JNIEnv *env, jstring path, jlong first_sample, jlong max_frames, jobject consumer,
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

    if (path == NULL || consumer == NULL)
    {
        throw_illegal_argument_exception(env, "Decoder path and consumer must not be null.");
        return;
    }

    if ((seek_before_decode || range_limited) && !validate_decode_request(env, first_sample, range_limited, max_frames))
    {
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

    FLAC__StreamDecoderInitStatus init_status = g_flac_api.stream_decoder_init_file(
        decoder, utf8_path, decode_write_callback, decode_metadata_callback, decode_error_callback, &context);
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
    FLAC__bool finish_success = g_flac_api.stream_decoder_finish(decoder);

    /*
     * Java exceptions raised by callbacks must win over synthetic native errors.
     * ExceptionCheck gates every later throw so user callback failures preserve
     * their original Java exception type and stack.
     */
    if (!(*env)->ExceptionCheck(env) && !success)
    {
        const char *state = g_flac_api.stream_decoder_get_resolved_state_string(decoder);
        char buffer[JFLAC_MESSAGE_BUFFER_SIZE];
        snprintf(buffer, sizeof(buffer), "FLAC decoding failed%s%s%s%s%s.", state != NULL ? ": " : "",
                 state != NULL ? state : "", context.saw_error ? " (decoder error callback: " : "",
                 context.saw_error ? decoder_error_status_name(context.last_error_status) : "",
                 context.saw_error ? ")" : "");
        throw_decode_exception(env, buffer);
    }

    if (!(*env)->ExceptionCheck(env) && success && !finish_success)
    {
        const char *state = g_flac_api.stream_decoder_get_resolved_state_string(decoder);
        char buffer[JFLAC_MESSAGE_BUFFER_SIZE];
        snprintf(buffer, sizeof(buffer), "FLAC decoding failed while finishing%s%s.", state != NULL ? ": " : "",
                 state != NULL ? state : "");
        throw_decode_exception(env, buffer);
    }

    if (!(*env)->ExceptionCheck(env) && success && finish_success)
    {
        (*env)->CallVoidMethod(env, consumer, context.on_complete);
    }

    g_flac_api.stream_decoder_delete(decoder);
}

/*
 * Decodes a sequential InputStream through libFLAC's stream callback API.
 * Range and reusable-session decode stay file-only because plain InputStream
 * does not provide a clean seek/tell/length contract.
 */
static void decode_stream_internal(JNIEnv *env, jobject input_stream, jobject consumer)
{
    if (!flac_api_ready(env))
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
    if (!prepare_decode_context(env, &context, consumer) || !prepare_decode_input_stream(env, &context, input_stream))
    {
        return;
    }

    FLAC__StreamDecoder *decoder = g_flac_api.stream_decoder_new();
    if (decoder == NULL)
    {
        throw_decode_exception(env, "Failed to allocate FLAC stream decoder.");
        return;
    }

    FLAC__StreamDecoderInitStatus init_status = g_flac_api.stream_decoder_init_stream(
        decoder, decode_read_callback, NULL, NULL, NULL, NULL, decode_write_callback, decode_metadata_callback,
        decode_error_callback, &context);
    if (init_status != FLAC__STREAM_DECODER_INIT_STATUS_OK)
    {
        g_flac_api.stream_decoder_delete(decoder);
        throw_decode_exception(env, "Failed to initialize FLAC stream decoder.");
        return;
    }

    FLAC__bool success = process_decode_stream(decoder, &context);
    FLAC__bool finish_success = g_flac_api.stream_decoder_finish(decoder);

    if (!(*env)->ExceptionCheck(env) && !success)
    {
        const char *state = g_flac_api.stream_decoder_get_resolved_state_string(decoder);
        char buffer[JFLAC_MESSAGE_BUFFER_SIZE];
        snprintf(buffer, sizeof(buffer), "FLAC stream decoding failed%s%s%s%s%s.", state != NULL ? ": " : "",
                 state != NULL ? state : "", context.saw_error ? " (decoder error callback: " : "",
                 context.saw_error ? decoder_error_status_name(context.last_error_status) : "",
                 context.saw_error ? ")" : "");
        throw_decode_exception(env, buffer);
    }

    if (!(*env)->ExceptionCheck(env) && success && !finish_success)
    {
        const char *state = g_flac_api.stream_decoder_get_resolved_state_string(decoder);
        char buffer[JFLAC_MESSAGE_BUFFER_SIZE];
        snprintf(buffer, sizeof(buffer), "FLAC stream decoding failed while finishing%s%s.",
                 state != NULL ? ": " : "", state != NULL ? state : "");
        throw_decode_exception(env, buffer);
    }

    if (!(*env)->ExceptionCheck(env) && success && finish_success)
    {
        (*env)->CallVoidMethod(env, consumer, context.on_complete);
    }

    g_flac_api.stream_decoder_delete(decoder);
}

/*
 * Decodes a SeekableByteChannel through libFLAC's direct callback API. The
 * channel position at call entry is treated as byte offset zero for libFLAC.
 */
static void decode_channel_internal(JNIEnv *env, jobject channel, jlong first_sample, jlong max_frames, jobject consumer,
                                    int seek_before_decode, int range_limited)
{
    if (!flac_api_ready(env))
    {
        return;
    }

    if (channel == NULL || consumer == NULL)
    {
        throw_illegal_argument_exception(env, "Decoder seekable channel and consumer must not be null.");
        return;
    }

    if ((seek_before_decode || range_limited) && !validate_decode_request(env, first_sample, range_limited, max_frames))
    {
        return;
    }

    DecodeContext context;
    memset(&context, 0, sizeof(context));
    if (!prepare_decode_context(env, &context, consumer) || !prepare_decode_seekable_channel(env, &context, channel))
    {
        clear_decode_seekable_channel(&context);
        return;
    }
    configure_decode_range(&context, range_limited, max_frames);

    FLAC__StreamDecoder *decoder = g_flac_api.stream_decoder_new();
    if (decoder == NULL)
    {
        clear_decode_seekable_channel(&context);
        throw_decode_exception(env, "Failed to allocate FLAC channel decoder.");
        return;
    }

    FLAC__StreamDecoderInitStatus init_status = g_flac_api.stream_decoder_init_stream(
        decoder, decode_read_callback, decode_channel_seek_callback, decode_channel_tell_callback,
        decode_channel_length_callback, decode_channel_eof_callback, decode_write_callback, decode_metadata_callback,
        decode_error_callback, &context);
    if (init_status != FLAC__STREAM_DECODER_INIT_STATUS_OK)
    {
        g_flac_api.stream_decoder_delete(decoder);
        clear_decode_seekable_channel(&context);
        throw_decode_exception(env, "Failed to initialize FLAC channel decoder.");
        return;
    }

    if (seek_before_decode || range_limited)
    {
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
            clear_decode_seekable_channel(&context);
            return;
        }

        if (decode_range_starts_after_stream(&context, first_sample))
        {
            g_flac_api.stream_decoder_finish(decoder);
            g_flac_api.stream_decoder_delete(decoder);
            clear_decode_seekable_channel(&context);
            throw_range_after_stream_exception(env);
            return;
        }

        if (context.range_complete || decode_range_starts_at_stream_end(&context, first_sample))
        {
            FLAC__bool finish_success = g_flac_api.stream_decoder_finish(decoder);
            if (!(*env)->ExceptionCheck(env) && finish_success)
            {
                (*env)->CallVoidMethod(env, consumer, context.on_complete);
            }
            g_flac_api.stream_decoder_delete(decoder);
            clear_decode_seekable_channel(&context);
            return;
        }

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
            clear_decode_seekable_channel(&context);
            return;
        }
    }

    FLAC__bool success = process_decode_stream(decoder, &context);
    FLAC__bool finish_success = g_flac_api.stream_decoder_finish(decoder);

    if (!(*env)->ExceptionCheck(env) && !success)
    {
        const char *state = g_flac_api.stream_decoder_get_resolved_state_string(decoder);
        char buffer[JFLAC_MESSAGE_BUFFER_SIZE];
        snprintf(buffer, sizeof(buffer), "FLAC channel decoding failed%s%s%s%s%s.", state != NULL ? ": " : "",
                 state != NULL ? state : "", context.saw_error ? " (decoder error callback: " : "",
                 context.saw_error ? decoder_error_status_name(context.last_error_status) : "",
                 context.saw_error ? ")" : "");
        throw_decode_exception(env, buffer);
    }

    if (!(*env)->ExceptionCheck(env) && success && !finish_success)
    {
        const char *state = g_flac_api.stream_decoder_get_resolved_state_string(decoder);
        char buffer[JFLAC_MESSAGE_BUFFER_SIZE];
        snprintf(buffer, sizeof(buffer), "FLAC channel decoding failed while finishing%s%s.",
                 state != NULL ? ": " : "", state != NULL ? state : "");
        throw_decode_exception(env, buffer);
    }

    if (!(*env)->ExceptionCheck(env) && success && finish_success)
    {
        (*env)->CallVoidMethod(env, consumer, context.on_complete);
    }

    g_flac_api.stream_decoder_delete(decoder);
    clear_decode_seekable_channel(&context);
}

/* Decodes a whole FLAC file and emits STREAMINFO plus PCM callbacks. */
JNIEXPORT void JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_decodeFile(JNIEnv *env, jclass clazz, jstring path,
                                                                                jobject consumer)
{
    (void)clazz;
    decode_file_internal(env, path, 0, 0, consumer, JFLAC_EMIT_METADATA_CALLBACKS, JFLAC_UNLIMITED_RANGE);
}

/* Decodes a whole FLAC stream and emits STREAMINFO plus PCM callbacks. */
JNIEXPORT void JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_decodeStream(JNIEnv *env, jclass clazz,
                                                                                  jobject input_stream,
                                                                                  jobject consumer)
{
    (void)clazz;
    decode_stream_internal(env, input_stream, consumer);
}

/* Decodes a whole seekable channel and emits STREAMINFO plus PCM callbacks. */
JNIEXPORT void JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_decodeChannel(JNIEnv *env, jclass clazz,
                                                                                   jobject channel, jobject consumer)
{
    (void)clazz;
    decode_channel_internal(env, channel, 0, 0, consumer, JFLAC_EMIT_METADATA_CALLBACKS, JFLAC_UNLIMITED_RANGE);
}

/* Decodes from an absolute sample frame to end-of-stream. */
JNIEXPORT void JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_decodeFileFrom(JNIEnv *env, jclass clazz,
                                                                                    jstring path, jlong first_sample,
                                                                                    jobject consumer)
{
    (void)clazz;
    decode_file_internal(env, path, first_sample, 0, consumer, JFLAC_SUPPRESS_METADATA_CALLBACKS,
                         JFLAC_UNLIMITED_RANGE);
}

/* Decodes from an absolute sample frame to end-of-stream from a seekable channel. */
JNIEXPORT void JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_decodeChannelFrom(JNIEnv *env, jclass clazz,
                                                                                       jobject channel,
                                                                                       jlong first_sample,
                                                                                       jobject consumer)
{
    (void)clazz;
    decode_channel_internal(env, channel, first_sample, 0, consumer, JFLAC_SUPPRESS_METADATA_CALLBACKS,
                            JFLAC_UNLIMITED_RANGE);
}

/* Decodes a bounded frame range from a file. */
JNIEXPORT void JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_decodeFileRange(JNIEnv *env, jclass clazz,
                                                                                     jstring path, jlong first_sample,
                                                                                     jlong max_frames, jobject consumer)
{
    (void)clazz;
    decode_file_internal(env, path, first_sample, max_frames, consumer, JFLAC_SUPPRESS_METADATA_CALLBACKS,
                         JFLAC_LIMITED_RANGE);
}

/* Decodes a bounded frame range from a seekable channel. */
JNIEXPORT void JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_decodeChannelRange(
    JNIEnv *env, jclass clazz, jobject channel, jlong first_sample, jlong max_frames, jobject consumer)
{
    (void)clazz;
    decode_channel_internal(env, channel, first_sample, max_frames, consumer, JFLAC_SUPPRESS_METADATA_CALLBACKS,
                            JFLAC_LIMITED_RANGE);
}

/*
 * Opens a reusable file decoder session and reads STREAMINFO once so later
 * range decodes can avoid duplicate
 * metadata callbacks.
 */
JNIEXPORT jlong JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_openDecoderFile(JNIEnv *env, jclass clazz,
                                                                                      jstring path)
{
    (void)clazz;
    if (!flac_api_ready(env))
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
        free(session);
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
    FLAC__StreamDecoderInitStatus init_status =
        g_flac_api.stream_decoder_init_file(session->decoder, utf8_path, decode_write_callback,
                                            decode_metadata_callback, decode_error_callback, &session->context);
    free(utf8_path);

    if (init_status != FLAC__STREAM_DECODER_INIT_STATUS_OK)
    {
        g_flac_api.stream_decoder_delete(session->decoder);
        free(session);
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
        g_flac_api.stream_decoder_finish(session->decoder);
        g_flac_api.stream_decoder_delete(session->decoder);
        free(session);
        throw_decode_exception(env, buffer);
        return 0;
    }

    jlong handle = register_decode_session(session);
    if (handle == 0)
    {
        destroy_decode_session(session);
        throw_decode_exception(env, "Failed to register FLAC decoder session.");
        return 0;
    }

    return handle;
}

/*
 * Opens a reusable seekable-channel decoder session and reads STREAMINFO once
 * so later range decodes can use the same native handle.
 */
JNIEXPORT jlong JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_openDecoderChannel(JNIEnv *env, jclass clazz,
                                                                                         jobject channel)
{
    (void)clazz;
    if (!flac_api_ready(env))
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
        free(session);
        throw_decode_exception(env, "Failed to allocate FLAC decoder.");
        return 0;
    }

    session->context.env = env;
    session->context.consumer = NULL;
    session->context.suppress_metadata = JFLAC_SUPPRESS_METADATA_CALLBACKS;
    if (!prepare_decode_seekable_channel(env, &session->context, channel))
    {
        destroy_decode_session(session);
        return 0;
    }

    FLAC__StreamDecoderInitStatus init_status = g_flac_api.stream_decoder_init_stream(
        session->decoder, decode_read_callback, decode_channel_seek_callback, decode_channel_tell_callback,
        decode_channel_length_callback, decode_channel_eof_callback, decode_write_callback, decode_metadata_callback,
        decode_error_callback, &session->context);

    if (init_status != FLAC__STREAM_DECODER_INIT_STATUS_OK)
    {
        destroy_decode_session(session);
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
        destroy_decode_session(session);
        throw_decode_exception(env, buffer);
        return 0;
    }
    if ((*env)->ExceptionCheck(env) || !metadata_success)
    {
        destroy_decode_session(session);
        return 0;
    }

    jlong handle = register_decode_session(session);
    if (handle == 0)
    {
        destroy_decode_session(session);
        throw_decode_exception(env, "Failed to register FLAC channel decoder session.");
        return 0;
    }

    return handle;
}

/* Decodes from a reusable decoder session to end-of-stream. */
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

    if (first_sample < 0)
    {
        release_decode_session_reference(session);
        throw_illegal_argument_exception(env, "First sample must be non-negative.");
        return;
    }

    if (consumer == NULL)
    {
        release_decode_session_reference(session);
        throw_illegal_argument_exception(env, "Decoder consumer must not be null.");
        return;
    }

    if (!prepare_decode_context(env, &session->context, consumer))
    {
        release_decode_session_reference(session);
        return;
    }
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
        release_decode_session_reference(session);
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
        release_decode_session_reference(session);
        return;
    }

    if (!(*env)->ExceptionCheck(env) && success)
    {
        (*env)->CallVoidMethod(env, consumer, session->context.on_complete);
    }
    /*
     * Do this even when onComplete throws. The pending Java exception is left
     * untouched, while the native context stops retaining a stale local ref.
     */
    session->context.consumer = NULL;
    release_decode_session_reference(session);
}

/* Decodes a bounded range through a reusable decoder session. */
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

    if (!validate_decode_request(env, first_sample, JFLAC_LIMITED_RANGE, max_frames))
    {
        release_decode_session_reference(session);
        return;
    }

    if (consumer == NULL)
    {
        release_decode_session_reference(session);
        throw_illegal_argument_exception(env, "Decoder consumer must not be null.");
        return;
    }

    if (!prepare_decode_context(env, &session->context, consumer))
    {
        release_decode_session_reference(session);
        return;
    }
    session->context.suppress_metadata = JFLAC_SUPPRESS_METADATA_CALLBACKS;
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
        release_decode_session_reference(session);
        return;
    }

    if (session->context.range_complete || decode_range_starts_at_stream_end(&session->context, first_sample))
    {
        if (!(*env)->ExceptionCheck(env))
        {
            (*env)->CallVoidMethod(env, consumer, session->context.on_complete);
        }
        /*
         * No seek or finish is needed for a successful empty range. Keeping the
         * decoder open preserves the session for the caller's next range.
         */
        session->context.consumer = NULL;
        release_decode_session_reference(session);
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
        release_decode_session_reference(session);
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
        release_decode_session_reference(session);
        return;
    }

    if (!(*env)->ExceptionCheck(env) && success)
    {
        (*env)->CallVoidMethod(env, consumer, session->context.on_complete);
    }
    /*
     * As above, never leave a JNI local reference in the reusable context after
     * the call has returned, regardless of whether onComplete succeeded.
     */
    session->context.consumer = NULL;
    release_decode_session_reference(session);
}

/* Releases a reusable decoder session handle. */
JNIEXPORT void JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_releaseDecoder(JNIEnv *env, jclass clazz,
                                                                                    jlong handle)
{
    (void)env;
    (void)clazz;
    DecodeSession *session = remove_decode_session(handle);
    destroy_decode_session(session);
}

typedef struct EncodeContext
{
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
    jmethodID channel_write;
    jmethodID channel_position;
    jmethodID channel_seek;
    jlong channel_base_offset;
} EncodeContext;

/* Deletes all libFLAC metadata blocks owned by an encoder context. */
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

/* Destroys the encoder object, metadata blocks, and heap context. */
static void destroy_encode_context(EncodeContext *context)
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

    if (context->output_stream != NULL && context->jvm != NULL)
    {
        JNIEnv *env = NULL;
        if ((*context->jvm)->GetEnv(context->jvm, (void **)&env, JNI_VERSION_1_8) == JNI_OK && env != NULL)
        {
            (*env)->DeleteGlobalRef(env, context->output_stream);
        }
        context->output_stream = NULL;
    }

    if (context->output_channel != NULL && context->jvm != NULL)
    {
        JNIEnv *env = NULL;
        if ((*context->jvm)->GetEnv(context->jvm, (void **)&env, JNI_VERSION_1_8) == JNI_OK && env != NULL)
        {
            (*env)->DeleteGlobalRef(env, context->output_channel);
        }
        context->output_channel = NULL;
    }

    free(context);
}

/* Builds one Vorbis comment metadata block from flattened KEY=value strings. */
static FLAC__bool build_vorbis_comment_block(JNIEnv *env, jobjectArray comment_entries, FLAC__StreamMetadata **result)
{
    *result = NULL;
    if (comment_entries == NULL)
    {
        return true;
    }

    jsize entry_count = (*env)->GetArrayLength(env, comment_entries);
    if (entry_count == 0)
    {
        return true;
    }

    FLAC__StreamMetadata *block = g_flac_api.metadata_object_new(FLAC__METADATA_TYPE_VORBIS_COMMENT);
    if (block == NULL)
    {
        return false;
    }

    for (jsize i = 0; i < entry_count; ++i)
    {
        jstring entry_string = (jstring)(*env)->GetObjectArrayElement(env, comment_entries, i);
        if ((*env)->ExceptionCheck(env))
        {
            g_flac_api.metadata_object_delete(block);
            return false;
        }

        char *utf8_entry = jstring_to_utf8(env, entry_string);
        const char *separator = utf8_entry != NULL ? strchr(utf8_entry, '=') : NULL;
        size_t name_length =
            separator != NULL ? (size_t)(separator - utf8_entry) : (utf8_entry != NULL ? strlen(utf8_entry) : 0u);
        size_t value_length = separator != NULL ? strlen(separator + 1) : 0u;
        char *name = (char *)malloc(name_length + 1u);
        char *value = (char *)malloc(value_length + 1u);
        FLAC__StreamMetadata_VorbisComment_Entry native_entry;
        memset(&native_entry, 0, sizeof(native_entry));

        if (utf8_entry == NULL || name == NULL || value == NULL)
        {
            free(utf8_entry);
            free(name);
            free(value);
            g_flac_api.metadata_object_delete(block);
            return false;
        }

        memcpy(name, utf8_entry, name_length);
        name[name_length] = '\0';
        if (separator != NULL)
        {
            memcpy(value, separator + 1, value_length);
        }
        value[value_length] = '\0';

        if (!g_flac_api.metadata_object_vorbiscomment_entry_from_name_value_pair(&native_entry, name, value))
        {
            free(utf8_entry);
            free(name);
            free(value);
            g_flac_api.metadata_object_delete(block);
            return false;
        }

        if (!g_flac_api.metadata_object_vorbiscomment_append_comment(block, native_entry, false))
        {
            free(native_entry.entry);
            free(utf8_entry);
            free(name);
            free(value);
            g_flac_api.metadata_object_delete(block);
            return false;
        }

        free(utf8_entry);
        free(name);
        free(value);
        (*env)->DeleteLocalRef(env, entry_string);
    }

    *result = block;
    return true;
}

/* Builds one libFLAC picture metadata block from the public FlacPicture model. */
static FLAC__bool build_picture_block(JNIEnv *env, jobject picture_object, FLAC__StreamMetadata **result)
{
    *result = NULL;
    if (picture_object == NULL)
    {
        return true;
    }

    jclass picture_class = (*env)->GetObjectClass(env, picture_object);
    jmethodID get_type = (*env)->GetMethodID(env, picture_class, "getType", "()I");
    jmethodID get_mime_type = (*env)->GetMethodID(env, picture_class, "getMimeType", "()Ljava/lang/String;");
    jmethodID get_description = (*env)->GetMethodID(env, picture_class, "getDescription", "()Ljava/lang/String;");
    jmethodID get_width = (*env)->GetMethodID(env, picture_class, "getWidth", "()I");
    jmethodID get_height = (*env)->GetMethodID(env, picture_class, "getHeight", "()I");
    jmethodID get_depth = (*env)->GetMethodID(env, picture_class, "getDepth", "()I");
    jmethodID get_colors = (*env)->GetMethodID(env, picture_class, "getColors", "()I");
    jmethodID get_data = (*env)->GetMethodID(env, picture_class, "getData", "()[B");
    if (get_type == NULL || get_mime_type == NULL || get_description == NULL || get_width == NULL ||
        get_height == NULL || get_depth == NULL || get_colors == NULL || get_data == NULL)
    {
        return false;
    }

    jint type = (*env)->CallIntMethod(env, picture_object, get_type);
    jint width = (*env)->CallIntMethod(env, picture_object, get_width);
    jint height = (*env)->CallIntMethod(env, picture_object, get_height);
    jint depth = (*env)->CallIntMethod(env, picture_object, get_depth);
    jint colors = (*env)->CallIntMethod(env, picture_object, get_colors);
    jstring mime_type_object = (jstring)(*env)->CallObjectMethod(env, picture_object, get_mime_type);
    jstring description_object = (jstring)(*env)->CallObjectMethod(env, picture_object, get_description);
    jbyteArray data_object = (jbyteArray)(*env)->CallObjectMethod(env, picture_object, get_data);
    if ((*env)->ExceptionCheck(env))
    {
        return false;
    }

    FLAC__StreamMetadata *block = g_flac_api.metadata_object_new(FLAC__METADATA_TYPE_PICTURE);
    if (block == NULL)
    {
        return false;
    }

    char *mime_type = jstring_to_utf8(env, mime_type_object);
    char *description = jstring_to_utf8(env, description_object);
    jsize data_length = data_object != NULL ? (*env)->GetArrayLength(env, data_object) : 0;
    FLAC__byte *data = NULL;

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

    if (mime_type == NULL || description == NULL)
    {
        free(mime_type);
        free(description);
        free(data);
        g_flac_api.metadata_object_delete(block);
        return false;
    }

    block->data.picture.type = (FLAC__StreamMetadata_Picture_Type)type;
    block->data.picture.width = (FLAC__uint32)width;
    block->data.picture.height = (FLAC__uint32)height;
    block->data.picture.depth = (FLAC__uint32)depth;
    block->data.picture.colors = (FLAC__uint32)colors;

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

/* Builds one libFLAC APPLICATION block from the public FlacApplicationBlock model. */
static FLAC__bool build_application_block(JNIEnv *env, jobject application_object, FLAC__StreamMetadata **result)
{
    *result = NULL;
    if (application_object == NULL)
    {
        throw_illegal_argument_exception(env, "APPLICATION metadata block must not be null.");
        return false;
    }

    jclass application_class = (*env)->GetObjectClass(env, application_object);
    jmethodID get_id = (*env)->GetMethodID(env, application_class, "getId", "()[B");
    jmethodID get_data = (*env)->GetMethodID(env, application_class, "getData", "()[B");
    if (get_id == NULL || get_data == NULL)
    {
        return false;
    }

    jbyteArray id_object = (jbyteArray)(*env)->CallObjectMethod(env, application_object, get_id);
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
    free(data);
    if (!success)
    {
        g_flac_api.metadata_object_delete(block);
        return false;
    }

    *result = block;
    return true;
}

/* Builds one libFLAC PADDING block from the public FlacPaddingBlock model. */
static FLAC__bool build_padding_block(JNIEnv *env, jobject padding_object, FLAC__StreamMetadata **result)
{
    *result = NULL;
    if (padding_object == NULL)
    {
        throw_illegal_argument_exception(env, "PADDING metadata block must not be null.");
        return false;
    }

    jclass padding_class = (*env)->GetObjectClass(env, padding_object);
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

/* Builds one opaque libFLAC metadata block from the public raw-block model. */
static FLAC__bool build_unknown_metadata_block(JNIEnv *env, jobject unknown_object, FLAC__StreamMetadata **result)
{
    *result = NULL;
    if (unknown_object == NULL)
    {
        throw_illegal_argument_exception(env, "Unknown metadata block must not be null.");
        return false;
    }

    jclass unknown_class = (*env)->GetObjectClass(env, unknown_object);
    jmethodID get_type = (*env)->GetMethodID(env, unknown_class, "getType", "()I");
    jmethodID get_data = (*env)->GetMethodID(env, unknown_class, "getData", "()[B");
    if (get_type == NULL || get_data == NULL)
    {
        return false;
    }

    jint type = (*env)->CallIntMethod(env, unknown_object, get_type);
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

    FLAC__StreamMetadata *block = g_flac_api.metadata_object_new((FLAC__MetadataType)type);
    if (block == NULL)
    {
        return false;
    }

    FLAC__byte *data = NULL;
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

    block->length = (uint32_t)data_length;
    block->data.unknown.data = data;
    *result = block;
    return true;
}

/* Builds one libFLAC SEEKTABLE block from the public FlacSeekTable model. */
static FLAC__bool build_seek_table_block(JNIEnv *env, jobject seek_table_object, FLAC__StreamMetadata **result)
{
    *result = NULL;
    if (seek_table_object == NULL)
    {
        throw_illegal_argument_exception(env, "SEEKTABLE metadata block must not be null.");
        return false;
    }

    jclass table_class = (*env)->GetObjectClass(env, seek_table_object);
    jmethodID get_points = (*env)->GetMethodID(env, table_class, "getPoints", "()Ljava/util/List;");
    jclass list_class = (*env)->FindClass(env, "java/util/List");
    if (get_points == NULL || list_class == NULL)
    {
        return false;
    }

    jmethodID list_size = (*env)->GetMethodID(env, list_class, "size", "()I");
    jmethodID list_get = (*env)->GetMethodID(env, list_class, "get", "(I)Ljava/lang/Object;");
    jclass point_class = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacSeekPoint");
    if (list_size == NULL || list_get == NULL || point_class == NULL)
    {
        return false;
    }

    jmethodID get_sample_number = (*env)->GetMethodID(env, point_class, "getSampleNumber", "()J");
    jmethodID get_stream_offset = (*env)->GetMethodID(env, point_class, "getStreamOffset", "()J");
    jmethodID get_frame_samples = (*env)->GetMethodID(env, point_class, "getFrameSamples", "()I");
    if (get_sample_number == NULL || get_stream_offset == NULL || get_frame_samples == NULL)
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
        jlong stream_offset = (*env)->CallLongMethod(env, point_object, get_stream_offset);
        jint frame_samples = (*env)->CallIntMethod(env, point_object, get_frame_samples);
        if ((*env)->ExceptionCheck(env))
        {
            (*env)->DeleteLocalRef(env, point_object);
            g_flac_api.metadata_object_delete(block);
            return false;
        }

        FLAC__StreamMetadata_SeekPoint point;
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
 * FlacCueSheetTrack model.
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
    jmethodID get_offset = (*env)->GetMethodID(env, track_class, "getOffset", "()J");
    jmethodID get_number = (*env)->GetMethodID(env, track_class, "getNumber", "()I");
    jmethodID get_isrc = (*env)->GetMethodID(env, track_class, "getIsrc", "()Ljava/lang/String;");
    jmethodID get_type = (*env)->GetMethodID(env, track_class, "getType", "()I");
    jmethodID get_pre_emphasis = (*env)->GetMethodID(env, track_class, "getPreEmphasis", "()Z");
    jmethodID get_indices = (*env)->GetMethodID(env, track_class, "getIndices", "()Ljava/util/List;");
    if (get_offset == NULL || get_number == NULL || get_isrc == NULL || get_type == NULL || get_pre_emphasis == NULL ||
        get_indices == NULL)
    {
        return false;
    }

    jlong offset = (*env)->CallLongMethod(env, track_object, get_offset);
    jint number = (*env)->CallIntMethod(env, track_object, get_number);
    jstring isrc_object = (jstring)(*env)->CallObjectMethod(env, track_object, get_isrc);
    jint type = (*env)->CallIntMethod(env, track_object, get_type);
    jboolean pre_emphasis = (*env)->CallBooleanMethod(env, track_object, get_pre_emphasis);
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
    if (isrc == NULL)
    {
        return false;
    }

    jclass list_class = (*env)->FindClass(env, "java/util/List");
    jclass index_class = (*env)->FindClass(env, "org/zzvsjs/jflac/FlacCueSheetIndex");
    if (list_class == NULL || index_class == NULL)
    {
        free(isrc);
        return false;
    }
    jmethodID list_size = (*env)->GetMethodID(env, list_class, "size", "()I");
    jmethodID list_get = (*env)->GetMethodID(env, list_class, "get", "(I)Ljava/lang/Object;");
    jmethodID get_index_offset = (*env)->GetMethodID(env, index_class, "getOffset", "()J");
    jmethodID get_index_number = (*env)->GetMethodID(env, index_class, "getNumber", "()I");
    if (list_size == NULL || list_get == NULL || get_index_offset == NULL || get_index_number == NULL)
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

    if (!g_flac_api.metadata_object_cuesheet_track_resize_indices(block, track_index, (uint32_t)index_count))
    {
        free(isrc);
        return false;
    }

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
        jint index_number = (*env)->CallIntMethod(env, index_object, get_index_number);
        (*env)->DeleteLocalRef(env, index_object);
        if ((*env)->ExceptionCheck(env))
        {
            return false;
        }

        track->indices[i].offset = (FLAC__uint64)index_offset;
        track->indices[i].number = (FLAC__byte)index_number;
    }

    return true;
}

/* Builds one libFLAC CUESHEET block from the public FlacCueSheet model. */
static FLAC__bool build_cue_sheet_block(JNIEnv *env, jobject cue_sheet_object, FLAC__StreamMetadata **result)
{
    *result = NULL;
    if (cue_sheet_object == NULL)
    {
        throw_illegal_argument_exception(env, "CUESHEET metadata block must not be null.");
        return false;
    }

    jclass cue_sheet_class = (*env)->GetObjectClass(env, cue_sheet_object);
    jmethodID get_media_catalog_number =
        (*env)->GetMethodID(env, cue_sheet_class, "getMediaCatalogNumber", "()Ljava/lang/String;");
    jmethodID get_lead_in = (*env)->GetMethodID(env, cue_sheet_class, "getLeadIn", "()J");
    jmethodID is_cd = (*env)->GetMethodID(env, cue_sheet_class, "isCd", "()Z");
    jmethodID get_tracks = (*env)->GetMethodID(env, cue_sheet_class, "getTracks", "()Ljava/util/List;");
    if (get_media_catalog_number == NULL || get_lead_in == NULL || is_cd == NULL || get_tracks == NULL)
    {
        return false;
    }

    jstring media_catalog_number_object =
        (jstring)(*env)->CallObjectMethod(env, cue_sheet_object, get_media_catalog_number);
    jlong lead_in = (*env)->CallLongMethod(env, cue_sheet_object, get_lead_in);
    jboolean cd = (*env)->CallBooleanMethod(env, cue_sheet_object, is_cd);
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
    if (media_catalog_number == NULL)
    {
        return false;
    }

    jclass list_class = (*env)->FindClass(env, "java/util/List");
    if (list_class == NULL)
    {
        free(media_catalog_number);
        return false;
    }
    jmethodID list_size = (*env)->GetMethodID(env, list_class, "size", "()I");
    jmethodID list_get = (*env)->GetMethodID(env, list_class, "get", "(I)Ljava/lang/Object;");
    if (list_size == NULL || list_get == NULL)
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

    for (jint i = 0; i < track_count; ++i)
    {
        jobject track_object = (*env)->CallObjectMethod(env, tracks, list_get, i);
        if ((*env)->ExceptionCheck(env))
        {
            g_flac_api.metadata_object_delete(block);
            return false;
        }

        if (!fill_cue_sheet_track(env, block, (uint32_t)i, track_object))
        {
            (*env)->DeleteLocalRef(env, track_object);
            g_flac_api.metadata_object_delete(block);
            return false;
        }
        (*env)->DeleteLocalRef(env, track_object);
    }

    const char *violation = NULL;
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

/* Builds the metadata block array attached to a new encoder. */
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

    context->metadata_blocks = (FLAC__StreamMetadata **)calloc(total_blocks, sizeof(FLAC__StreamMetadata *));
    if (context->metadata_blocks == NULL)
    {
        return false;
    }

    uint32_t index = 0;
    if (comment_count > 0)
    {
        if (!build_vorbis_comment_block(env, comment_entries, &context->metadata_blocks[index]))
        {
            destroy_metadata_blocks(context);
            return false;
        }
        index += 1u;
    }

    for (jsize i = 0; i < picture_count; ++i)
    {
        jobject picture_object = (*env)->GetObjectArrayElement(env, pictures, i);
        if ((*env)->ExceptionCheck(env))
        {
            destroy_metadata_blocks(context);
            return false;
        }

        if (!build_picture_block(env, picture_object, &context->metadata_blocks[index]))
        {
            destroy_metadata_blocks(context);
            return false;
        }
        index += 1u;
        (*env)->DeleteLocalRef(env, picture_object);
    }

    for (jsize i = 0; i < application_count; ++i)
    {
        jobject application_object = (*env)->GetObjectArrayElement(env, application_blocks, i);
        if ((*env)->ExceptionCheck(env))
        {
            destroy_metadata_blocks(context);
            return false;
        }

        if (!build_application_block(env, application_object, &context->metadata_blocks[index]))
        {
            destroy_metadata_blocks(context);
            return false;
        }
        index += 1u;
        (*env)->DeleteLocalRef(env, application_object);
    }

    for (jsize i = 0; i < seek_table_count; ++i)
    {
        jobject seek_table_object = (*env)->GetObjectArrayElement(env, seek_tables, i);
        if ((*env)->ExceptionCheck(env))
        {
            destroy_metadata_blocks(context);
            return false;
        }

        if (!build_seek_table_block(env, seek_table_object, &context->metadata_blocks[index]))
        {
            destroy_metadata_blocks(context);
            return false;
        }
        index += 1u;
        (*env)->DeleteLocalRef(env, seek_table_object);
    }

    for (jsize i = 0; i < cue_sheet_count; ++i)
    {
        jobject cue_sheet_object = (*env)->GetObjectArrayElement(env, cue_sheets, i);
        if ((*env)->ExceptionCheck(env))
        {
            destroy_metadata_blocks(context);
            return false;
        }

        if (!build_cue_sheet_block(env, cue_sheet_object, &context->metadata_blocks[index]))
        {
            destroy_metadata_blocks(context);
            return false;
        }
        index += 1u;
        (*env)->DeleteLocalRef(env, cue_sheet_object);
    }

    for (jsize i = 0; i < padding_count; ++i)
    {
        jobject padding_object = (*env)->GetObjectArrayElement(env, padding_blocks, i);
        if ((*env)->ExceptionCheck(env))
        {
            destroy_metadata_blocks(context);
            return false;
        }

        if (!build_padding_block(env, padding_object, &context->metadata_blocks[index]))
        {
            destroy_metadata_blocks(context);
            return false;
        }
        index += 1u;
        (*env)->DeleteLocalRef(env, padding_object);
    }

    for (jsize i = 0; i < unknown_count; ++i)
    {
        jobject unknown_object = (*env)->GetObjectArrayElement(env, unknown_blocks, i);
        if ((*env)->ExceptionCheck(env))
        {
            destroy_metadata_blocks(context);
            return false;
        }

        if (!build_unknown_metadata_block(env, unknown_object, &context->metadata_blocks[index]))
        {
            destroy_metadata_blocks(context);
            return false;
        }
        index += 1u;
        (*env)->DeleteLocalRef(env, unknown_object);
    }

    context->metadata_count = total_blocks;
    return true;
}

/* Builds one ordered metadata block according to the Java-supplied FLAC type code. */
static FLAC__bool build_ordered_metadata_block(JNIEnv *env, jint block_type, jobject block_value,
                                               FLAC__StreamMetadata **result)
{
    if (block_value == NULL)
    {
        throw_illegal_argument_exception(env, "Ordered metadata block value must not be null.");
        return false;
    }

    switch ((FLAC__MetadataType)block_type)
    {
    case FLAC__METADATA_TYPE_STREAMINFO:
        throw_illegal_argument_exception(env, "STREAMINFO metadata blocks cannot be supplied to the encoder.");
        return false;
    case FLAC__METADATA_TYPE_PADDING:
        return build_padding_block(env, block_value, result);
    case FLAC__METADATA_TYPE_APPLICATION:
        return build_application_block(env, block_value, result);
    case FLAC__METADATA_TYPE_SEEKTABLE:
        return build_seek_table_block(env, block_value, result);
    case FLAC__METADATA_TYPE_VORBIS_COMMENT:
        if (!build_vorbis_comment_block(env, (jobjectArray)block_value, result))
        {
            return false;
        }
        if (*result == NULL)
        {
            *result = g_flac_api.metadata_object_new(FLAC__METADATA_TYPE_VORBIS_COMMENT);
            if (*result == NULL)
            {
                return false;
            }
        }
        return true;
    case FLAC__METADATA_TYPE_CUESHEET:
        return build_cue_sheet_block(env, block_value, result);
    case FLAC__METADATA_TYPE_PICTURE:
        return build_picture_block(env, block_value, result);
    default:
        return build_unknown_metadata_block(env, block_value, result);
    }
}

/* Builds metadata blocks in exact caller order, excluding encoder-owned STREAMINFO. */
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

    context->metadata_blocks = (FLAC__StreamMetadata **)calloc((size_t)type_count, sizeof(FLAC__StreamMetadata *));
    if (context->metadata_blocks == NULL)
    {
        return false;
    }

    for (jsize i = 0; i < type_count; ++i)
    {
        jint block_type = 0;
        (*env)->GetIntArrayRegion(env, metadata_block_types, i, 1, &block_type);
        if ((*env)->ExceptionCheck(env))
        {
            destroy_metadata_blocks(context);
            return false;
        }

        jobject block_value = (*env)->GetObjectArrayElement(env, metadata_block_values, i);
        if ((*env)->ExceptionCheck(env))
        {
            destroy_metadata_blocks(context);
            return false;
        }

        if (!build_ordered_metadata_block(env, block_type, block_value, &context->metadata_blocks[i]))
        {
            (*env)->DeleteLocalRef(env, block_value);
            destroy_metadata_blocks(context);
            return false;
        }

        (*env)->DeleteLocalRef(env, block_value);
    }

    context->metadata_count = (uint32_t)type_count;
    return true;
}

/* Replaces all non-STREAMINFO blocks in an existing FLAC file metadata chain. */
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
    jmethodID get_comment_entries =
        (*env)->GetMethodID(env, request_class, "getCommentEntries", "()[Ljava/lang/String;");
    jmethodID get_pictures =
        (*env)->GetMethodID(env, request_class, "getPictures", "()[Lorg/zzvsjs/jflac/FlacPicture;");
    jmethodID get_application_blocks =
        (*env)->GetMethodID(env, request_class, "getApplicationBlocks", "()[Lorg/zzvsjs/jflac/FlacApplicationBlock;");
    jmethodID get_seek_tables =
        (*env)->GetMethodID(env, request_class, "getSeekTables", "()[Lorg/zzvsjs/jflac/FlacSeekTable;");
    jmethodID get_cue_sheets =
        (*env)->GetMethodID(env, request_class, "getCueSheets", "()[Lorg/zzvsjs/jflac/FlacCueSheet;");
    jmethodID get_padding_blocks =
        (*env)->GetMethodID(env, request_class, "getPaddingBlocks", "()[Lorg/zzvsjs/jflac/FlacPaddingBlock;");
    jmethodID get_unknown_blocks =
        (*env)->GetMethodID(env, request_class, "getUnknownBlocks", "()[Lorg/zzvsjs/jflac/FlacUnknownMetadataBlock;");
    jmethodID get_metadata_block_types = (*env)->GetMethodID(env, request_class, "getMetadataBlockTypes", "()[I");
    jmethodID get_metadata_block_values =
        (*env)->GetMethodID(env, request_class, "getMetadataBlockValues", "()[Ljava/lang/Object;");
    if (get_comment_entries == NULL || get_pictures == NULL || get_application_blocks == NULL ||
        get_seek_tables == NULL || get_cue_sheets == NULL || get_padding_blocks == NULL ||
        get_unknown_blocks == NULL || get_metadata_block_types == NULL || get_metadata_block_values == NULL)
    {
        g_flac_api.metadata_iterator_delete(iterator);
        g_flac_api.metadata_chain_delete(chain);
        return;
    }

    jobjectArray comment_entries = (jobjectArray)(*env)->CallObjectMethod(env, request, get_comment_entries);
    jobjectArray pictures = (jobjectArray)(*env)->CallObjectMethod(env, request, get_pictures);
    jobjectArray application_blocks = (jobjectArray)(*env)->CallObjectMethod(env, request, get_application_blocks);
    jobjectArray seek_tables = (jobjectArray)(*env)->CallObjectMethod(env, request, get_seek_tables);
    jobjectArray cue_sheets = (jobjectArray)(*env)->CallObjectMethod(env, request, get_cue_sheets);
    jobjectArray padding_blocks = (jobjectArray)(*env)->CallObjectMethod(env, request, get_padding_blocks);
    jobjectArray unknown_blocks = (jobjectArray)(*env)->CallObjectMethod(env, request, get_unknown_blocks);
    jintArray metadata_block_types = (jintArray)(*env)->CallObjectMethod(env, request, get_metadata_block_types);
    jobjectArray metadata_block_values =
        (jobjectArray)(*env)->CallObjectMethod(env, request, get_metadata_block_values);
    if ((*env)->ExceptionCheck(env))
    {
        g_flac_api.metadata_iterator_delete(iterator);
        g_flac_api.metadata_chain_delete(chain);
        return;
    }

    EncodeContext metadata_context;
    memset(&metadata_context, 0, sizeof(metadata_context));
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

        metadata_context.metadata_blocks[i] = NULL;
    }
    destroy_metadata_blocks(&metadata_context);

    if (!g_flac_api.metadata_chain_write(chain, use_padding ? true : false, preserve_file_stats ? true : false))
    {
        g_flac_api.metadata_iterator_delete(iterator);
        throw_metadata_chain_edit_exception(env, "Failed to write FLAC metadata edits", chain);
        g_flac_api.metadata_chain_delete(chain);
        return;
    }

    g_flac_api.metadata_iterator_delete(iterator);
    g_flac_api.metadata_chain_delete(chain);
}

/* Returns the current encoder state text, falling back when libFLAC gives none. */
static const char *encoder_state_or_unknown(const FLAC__StreamEncoder *encoder)
{
    const char *state = g_flac_api.stream_encoder_get_resolved_state_string(encoder);
    return state != NULL ? state : "unknown encoder state";
}

/* Returns the JNIEnv for synchronous libFLAC callbacks on the calling thread. */
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

/* Resolves OutputStream callbacks and promotes the stream to session lifetime. */
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

/* Resolves SeekableByteChannel callbacks and promotes the channel reference. */
static int prepare_encode_output_channel(JNIEnv *env, EncodeContext *context, jobject output_channel)
{
    if (output_channel == NULL)
    {
        throw_illegal_argument_exception(env, "Encoder output channel must not be null.");
        return 0;
    }

    jclass writable_class = (*env)->FindClass(env, "java/nio/channels/WritableByteChannel");
    jclass seekable_class = (*env)->FindClass(env, "java/nio/channels/SeekableByteChannel");
    if (writable_class == NULL || seekable_class == NULL)
    {
        return 0;
    }

    context->channel_write = (*env)->GetMethodID(env, writable_class, "write", "(Ljava/nio/ByteBuffer;)I");
    context->channel_position = (*env)->GetMethodID(env, seekable_class, "position", "()J");
    context->channel_seek =
        (*env)->GetMethodID(env, seekable_class, "position", "(J)Ljava/nio/channels/SeekableByteChannel;");
    if (context->channel_write == NULL || context->channel_position == NULL || context->channel_seek == NULL)
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

/* libFLAC write callback backed by java.nio.channels.SeekableByteChannel. */
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
            throw_encode_exception(env, "SeekableByteChannel returned zero bytes for a non-empty write request.");
            return FLAC__STREAM_ENCODER_WRITE_STATUS_FATAL_ERROR;
        }
        written_total += (size_t)written;
    }

    (*env)->DeleteLocalRef(env, byte_buffer);
    return FLAC__STREAM_ENCODER_WRITE_STATUS_OK;
}

/* libFLAC seek callback backed by SeekableByteChannel.position(long). */
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
    if (ignored != NULL)
    {
        (*env)->DeleteLocalRef(env, ignored);
    }
    return (*env)->ExceptionCheck(env) ? FLAC__STREAM_ENCODER_SEEK_STATUS_ERROR : FLAC__STREAM_ENCODER_SEEK_STATUS_OK;
}

/* libFLAC tell callback backed by SeekableByteChannel.position(). */
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

/* libFLAC write callback backed by java.io.OutputStream. */
static FLAC__StreamEncoderWriteStatus encode_write_callback(const FLAC__StreamEncoder *encoder,
                                                            const FLAC__byte buffer[], size_t bytes,
                                                            uint32_t samples, uint32_t current_frame,
                                                            void *client_data)
{
    EncodeContext *context = (EncodeContext *)client_data;
    if (context != NULL && context->output_channel != NULL)
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
    if (!(*env)->ExceptionCheck(env))
    {
        (*env)->CallVoidMethod(env, context->output_stream, context->output_write, chunk, (jint)0, (jint)bytes);
    }
    (*env)->DeleteLocalRef(env, chunk);

    return (*env)->ExceptionCheck(env) ? FLAC__STREAM_ENCODER_WRITE_STATUS_FATAL_ERROR
                                      : FLAC__STREAM_ENCODER_WRITE_STATUS_OK;
}

/* Opens and configures a file, OutputStream, or SeekableByteChannel encoder. */
static jlong open_encoder_internal(JNIEnv *env, char *utf8_path, jobject output_stream, jobject output_channel,
                                   jobject request)
{
    if ((utf8_path == NULL && output_stream == NULL && output_channel == NULL) || request == NULL)
    {
        free(utf8_path);
        throw_illegal_argument_exception(env, "Encoder output and request must not be null.");
        return 0;
    }

    jclass request_class = (*env)->GetObjectClass(env, request);
    jmethodID get_sample_rate = (*env)->GetMethodID(env, request_class, "getSampleRate", "()I");
    jmethodID get_channels = (*env)->GetMethodID(env, request_class, "getChannels", "()I");
    jmethodID get_bits_per_sample = (*env)->GetMethodID(env, request_class, "getBitsPerSample", "()I");
    jmethodID get_total_samples_estimate =
        (*env)->GetMethodID(env, request_class, "getTotalSamplesEstimate", "()Ljava/lang/Long;");
    jmethodID get_compression_level = (*env)->GetMethodID(env, request_class, "getCompressionLevel", "()I");
    jmethodID is_verify = (*env)->GetMethodID(env, request_class, "isVerify", "()Z");
    jmethodID is_streamable_subset = (*env)->GetMethodID(env, request_class, "isStreamableSubset", "()Z");
    jmethodID get_block_size = (*env)->GetMethodID(env, request_class, "getBlockSize", "()Ljava/lang/Integer;");
    jmethodID get_comment_entries =
        (*env)->GetMethodID(env, request_class, "getCommentEntries", "()[Ljava/lang/String;");
    jmethodID get_pictures =
        (*env)->GetMethodID(env, request_class, "getPictures", "()[Lorg/zzvsjs/jflac/FlacPicture;");
    jmethodID get_application_blocks =
        (*env)->GetMethodID(env, request_class, "getApplicationBlocks", "()[Lorg/zzvsjs/jflac/FlacApplicationBlock;");
    jmethodID get_seek_tables =
        (*env)->GetMethodID(env, request_class, "getSeekTables", "()[Lorg/zzvsjs/jflac/FlacSeekTable;");
    jmethodID get_cue_sheets =
        (*env)->GetMethodID(env, request_class, "getCueSheets", "()[Lorg/zzvsjs/jflac/FlacCueSheet;");
    jmethodID get_padding_blocks =
        (*env)->GetMethodID(env, request_class, "getPaddingBlocks", "()[Lorg/zzvsjs/jflac/FlacPaddingBlock;");
    jmethodID get_unknown_blocks =
        (*env)->GetMethodID(env, request_class, "getUnknownBlocks", "()[Lorg/zzvsjs/jflac/FlacUnknownMetadataBlock;");
    jmethodID get_metadata_block_types = (*env)->GetMethodID(env, request_class, "getMetadataBlockTypes", "()[I");
    jmethodID get_metadata_block_values =
        (*env)->GetMethodID(env, request_class, "getMetadataBlockValues", "()[Ljava/lang/Object;");
    if (get_sample_rate == NULL || get_channels == NULL || get_bits_per_sample == NULL ||
        get_total_samples_estimate == NULL || get_compression_level == NULL || is_verify == NULL ||
        is_streamable_subset == NULL || get_block_size == NULL || get_comment_entries == NULL || get_pictures == NULL ||
        get_application_blocks == NULL || get_seek_tables == NULL || get_cue_sheets == NULL ||
        get_padding_blocks == NULL || get_unknown_blocks == NULL || get_metadata_block_types == NULL ||
        get_metadata_block_values == NULL)
    {
        free(utf8_path);
        return 0;
    }

    jint sample_rate = (*env)->CallIntMethod(env, request, get_sample_rate);
    jint channels = (*env)->CallIntMethod(env, request, get_channels);
    jint bits_per_sample = (*env)->CallIntMethod(env, request, get_bits_per_sample);
    jint compression_level = (*env)->CallIntMethod(env, request, get_compression_level);
    jboolean verify = (*env)->CallBooleanMethod(env, request, is_verify);
    jboolean streamable_subset = (*env)->CallBooleanMethod(env, request, is_streamable_subset);
    jobject total_samples_object = (*env)->CallObjectMethod(env, request, get_total_samples_estimate);
    jobject block_size_object = (*env)->CallObjectMethod(env, request, get_block_size);
    jobjectArray comment_entries = (jobjectArray)(*env)->CallObjectMethod(env, request, get_comment_entries);
    jobjectArray pictures = (jobjectArray)(*env)->CallObjectMethod(env, request, get_pictures);
    jobjectArray application_blocks = (jobjectArray)(*env)->CallObjectMethod(env, request, get_application_blocks);
    jobjectArray seek_tables = (jobjectArray)(*env)->CallObjectMethod(env, request, get_seek_tables);
    jobjectArray cue_sheets = (jobjectArray)(*env)->CallObjectMethod(env, request, get_cue_sheets);
    jobjectArray padding_blocks = (jobjectArray)(*env)->CallObjectMethod(env, request, get_padding_blocks);
    jobjectArray unknown_blocks = (jobjectArray)(*env)->CallObjectMethod(env, request, get_unknown_blocks);
    jintArray metadata_block_types = (jintArray)(*env)->CallObjectMethod(env, request, get_metadata_block_types);
    jobjectArray metadata_block_values =
        (jobjectArray)(*env)->CallObjectMethod(env, request, get_metadata_block_values);
    if ((*env)->ExceptionCheck(env))
    {
        free(utf8_path);
        return 0;
    }

    jclass long_class = (*env)->FindClass(env, "java/lang/Long");
    jclass integer_class = (*env)->FindClass(env, "java/lang/Integer");
    jmethodID long_value = long_class != NULL ? (*env)->GetMethodID(env, long_class, "longValue", "()J") : NULL;
    jmethodID int_value = integer_class != NULL ? (*env)->GetMethodID(env, integer_class, "intValue", "()I") : NULL;
    if ((total_samples_object != NULL && long_value == NULL) || (block_size_object != NULL && int_value == NULL))
    {
        free(utf8_path);
        return 0;
    }

    FLAC__uint64 total_samples_estimate = 0;
    uint32_t block_size = 0;
    FLAC__bool has_total_samples_estimate = total_samples_object != NULL;
    FLAC__bool has_block_size = block_size_object != NULL;
    if (has_total_samples_estimate)
    {
        total_samples_estimate = (FLAC__uint64)(*env)->CallLongMethod(env, total_samples_object, long_value);
    }
    if (has_block_size)
    {
        block_size = (uint32_t)(*env)->CallIntMethod(env, block_size_object, int_value);
    }
    if ((*env)->ExceptionCheck(env))
    {
        free(utf8_path);
        return 0;
    }

    EncodeContext *context = (EncodeContext *)calloc(1u, sizeof(EncodeContext));
    if (context == NULL)
    {
        free(utf8_path);
        throw_encode_exception(env, "Failed to allocate encoder context.");
        return 0;
    }

    context->channels = (uint32_t)channels;
    if (output_stream != NULL && !prepare_encode_output_stream(env, context, output_stream))
    {
        destroy_encode_context(context);
        free(utf8_path);
        return 0;
    }
    if (output_channel != NULL && !prepare_encode_output_channel(env, context, output_channel))
    {
        destroy_encode_context(context);
        free(utf8_path);
        return 0;
    }

    context->encoder = g_flac_api.stream_encoder_new();
    if (context->encoder == NULL)
    {
        destroy_encode_context(context);
        free(utf8_path);
        throw_encode_exception(env, "Failed to allocate FLAC encoder.");
        return 0;
    }

    if (!g_flac_api.stream_encoder_set_verify(context->encoder, verify ? true : false) ||
        !g_flac_api.stream_encoder_set_streamable_subset(context->encoder, streamable_subset ? true : false) ||
        !g_flac_api.stream_encoder_set_channels(context->encoder, (uint32_t)channels) ||
        !g_flac_api.stream_encoder_set_bits_per_sample(context->encoder, (uint32_t)bits_per_sample) ||
        !g_flac_api.stream_encoder_set_sample_rate(context->encoder, (uint32_t)sample_rate) ||
        !g_flac_api.stream_encoder_set_compression_level(context->encoder, (uint32_t)compression_level) ||
        (has_block_size && !g_flac_api.stream_encoder_set_blocksize(context->encoder, block_size)) ||
        (has_total_samples_estimate &&
         !g_flac_api.stream_encoder_set_total_samples_estimate(context->encoder, total_samples_estimate)))
    {
        char buffer[JFLAC_MESSAGE_BUFFER_SIZE];
        snprintf(buffer, sizeof(buffer), "Failed to configure FLAC encoder: %s.",
                 encoder_state_or_unknown(context->encoder));
        destroy_encode_context(context);
        free(utf8_path);
        throw_encode_exception(env, buffer);
        return 0;
    }

    jsize ordered_metadata_count =
        metadata_block_types != NULL ? (*env)->GetArrayLength(env, metadata_block_types) : (jsize)0;
    FLAC__bool metadata_success =
        ordered_metadata_count > 0
            ? build_ordered_metadata_blocks(env, metadata_block_types, metadata_block_values, context)
            : build_metadata_blocks(env, comment_entries, pictures, application_blocks, seek_tables, cue_sheets,
                                    padding_blocks, unknown_blocks, context);
    if (!metadata_success)
    {
        destroy_encode_context(context);
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
        destroy_encode_context(context);
        free(utf8_path);
        throw_encode_exception(env, buffer);
        return 0;
    }

    FLAC__StreamEncoderInitStatus init_status;
    if (output_stream != NULL)
    {
        init_status =
            g_flac_api.stream_encoder_init_stream(context->encoder, encode_write_callback, NULL, NULL, NULL, context);
    }
    else if (output_channel != NULL)
    {
        init_status = g_flac_api.stream_encoder_init_stream(context->encoder, encode_write_callback,
                                                            encode_channel_seek_callback,
                                                            encode_channel_tell_callback, NULL, context);
    }
    else
    {
        init_status = g_flac_api.stream_encoder_init_file(context->encoder, utf8_path, NULL, NULL);
    }
    free(utf8_path);
    if (init_status != FLAC__STREAM_ENCODER_INIT_STATUS_OK)
    {
        char buffer[JFLAC_MESSAGE_BUFFER_SIZE];
        snprintf(buffer, sizeof(buffer), "Failed to initialize FLAC encoder output%s%s.",
                 encoder_state_or_unknown(context->encoder) != NULL ? ": " : "",
                 encoder_state_or_unknown(context->encoder));
        destroy_encode_context(context);
        if (!(*env)->ExceptionCheck(env))
        {
            throw_encode_exception(env, buffer);
        }
        return 0;
    }

    return (jlong)(intptr_t)context;
}

/* Opens and configures a file encoder, returning an opaque native handle. */
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

/* Opens and configures a sequential OutputStream encoder. */
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

/* Opens and configures a seekable channel encoder. */
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

/* Writes one interleaved PCM chunk into an active encoder handle. */
JNIEXPORT void JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_writeEncoderInterleaved(JNIEnv *env, jclass clazz,
                                                                                             jlong handle,
                                                                                             jintArray samples,
                                                                                             jint frames)
{
    (void)clazz;
    EncodeContext *context = (EncodeContext *)(intptr_t)handle;
    if (context == NULL || context->encoder == NULL)
    {
        throw_illegal_state_exception(env, "The native FLAC encoder handle is not active.");
        return;
    }

    if (frames < 0)
    {
        throw_illegal_argument_exception(env, "Frame count must be non-negative.");
        return;
    }

    if (frames == 0)
    {
        return;
    }

    if (samples == NULL)
    {
        throw_illegal_argument_exception(env, "PCM sample buffer must not be null.");
        return;
    }

    jsize sample_count = (*env)->GetArrayLength(env, samples);
    if ((uint64_t)sample_count != ((uint64_t)frames * (uint64_t)context->channels))
    {
        throw_illegal_argument_exception(env, "Sample array length must equal frames * channels.");
        return;
    }

    jint *elements = (*env)->GetIntArrayElements(env, samples, NULL);
    if (elements == NULL)
    {
        throw_encode_exception(env, "Failed to access PCM samples for encoding.");
        return;
    }

    FLAC__bool success = g_flac_api.stream_encoder_process_interleaved(context->encoder, (const FLAC__int32 *)elements,
                                                                       (uint32_t)frames);
    (*env)->ReleaseIntArrayElements(env, samples, elements, JNI_ABORT);

    if (!success && !(*env)->ExceptionCheck(env))
    {
        char buffer[JFLAC_MESSAGE_BUFFER_SIZE];
        snprintf(buffer, sizeof(buffer), "FLAC encoding failed while processing PCM: %s.",
                 encoder_state_or_unknown(context->encoder));
        throw_encode_exception(env, buffer);
    }
}

/* Finalises an encoder handle and releases all owned native state. */
JNIEXPORT void JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_finishEncoder(JNIEnv *env, jclass clazz,
                                                                                   jlong handle)
{
    (void)clazz;
    EncodeContext *context = (EncodeContext *)(intptr_t)handle;
    if (context == NULL || context->encoder == NULL)
    {
        throw_illegal_state_exception(env, "The native FLAC encoder handle is not active.");
        return;
    }

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
    }

    destroy_encode_context(context);
    if ((*env)->ExceptionCheck(env))
    {
        return;
    }
    if (!success)
    {
        throw_encode_exception(env, buffer);
    }
}

/* Releases an encoder handle without finalising; used after failure paths. */
JNIEXPORT void JNICALL Java_org_zzvsjs_jflac_internal_NativeBindings_releaseEncoder(JNIEnv *env, jclass clazz,
                                                                                    jlong handle)
{
    (void)env;
    (void)clazz;
    EncodeContext *context = (EncodeContext *)(intptr_t)handle;
    destroy_encode_context(context);
}
