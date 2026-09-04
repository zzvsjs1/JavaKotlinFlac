#include "jflac_fuzz_transport.h"

#include <limits.h>
#include <stdio.h>
#include <string.h>

void jflac_fuzz_transport_init(JflacFuzzTransport *transport, const uint8_t *data, size_t size)
{
    transport->data = data;
    transport->size = size;
    transport->position = 0u;

    /*
     * Vary short-read boundaries without removing a control byte from the
     * FLAC payload. Valid corpus files therefore remain valid inputs while a
     * mutation still explores the wrapper's chunked transport behaviour.
     */
    transport->chunk_limit = size == 0u ? 1u : 1u + ((size_t)data[size - 1u] % 4096u);
}

size_t jflac_fuzz_transport_read(JflacFuzzTransport *transport, void *destination, size_t requested_bytes)
{
    if (transport == NULL || destination == NULL || requested_bytes == 0u ||
        transport->position >= transport->size)
    {
        return 0u;
    }

    size_t remaining = transport->size - transport->position;
    size_t copy_size = requested_bytes < remaining ? requested_bytes : remaining;
    if (copy_size > transport->chunk_limit)
    {
        copy_size = transport->chunk_limit;
    }

    memcpy(destination, transport->data + transport->position, copy_size);
    transport->position += copy_size;
    return copy_size;
}

size_t jflac_fuzz_transport_read_records(
    JflacFuzzTransport *transport,
    void *destination,
    size_t record_size,
    size_t requested_records)
{
    if (transport == NULL || destination == NULL || record_size == 0u || requested_records == 0u ||
        transport->position >= transport->size)
    {
        return 0u;
    }

    size_t remaining_records = (transport->size - transport->position) / record_size;
    size_t copy_records = requested_records < remaining_records ? requested_records : remaining_records;
    size_t chunk_records = transport->chunk_limit / record_size;

    /* fread-style callbacks must never consume an incomplete record. */
    if (chunk_records == 0u)
    {
        chunk_records = 1u;
    }

    if (copy_records > chunk_records)
    {
        copy_records = chunk_records;
    }

    size_t copy_size = copy_records * record_size;
    memcpy(destination, transport->data + transport->position, copy_size);
    transport->position += copy_size;
    return copy_records;
}

int jflac_fuzz_transport_seek_absolute(JflacFuzzTransport *transport, uint64_t absolute_offset)
{
    if (transport == NULL || absolute_offset > (uint64_t)transport->size)
    {
        return -1;
    }

    transport->position = (size_t)absolute_offset;
    return 0;
}

int jflac_fuzz_transport_seek(JflacFuzzTransport *transport, int64_t offset, int whence)
{
    if (transport == NULL || transport->size > (size_t)INT64_MAX)
    {
        return -1;
    }

    uint64_t base;
    switch (whence)
    {
    case SEEK_SET:
        base = 0u;
        break;
    case SEEK_CUR:
        base = (uint64_t)transport->position;
        break;
    case SEEK_END:
        base = (uint64_t)transport->size;
        break;
    default:
        return -1;
    }

    uint64_t new_position;
    if (offset >= 0)
    {
        uint64_t positive_offset = (uint64_t)offset;
        if (positive_offset > (uint64_t)transport->size - base)
        {
            return -1;
        }

        new_position = base + positive_offset;
    }
    else
    {
        /* Avoid negating INT64_MIN while converting the distance to unsigned. */
        uint64_t negative_distance = (uint64_t)(-(offset + 1)) + 1u;
        if (negative_distance > base)
        {
            return -1;
        }

        new_position = base - negative_distance;
    }

    transport->position = (size_t)new_position;
    return 0;
}

uint64_t jflac_fuzz_transport_tell(const JflacFuzzTransport *transport)
{
    return transport == NULL ? 0u : (uint64_t)transport->position;
}

uint64_t jflac_fuzz_transport_length(const JflacFuzzTransport *transport)
{
    return transport == NULL ? 0u : (uint64_t)transport->size;
}

int jflac_fuzz_transport_eof(const JflacFuzzTransport *transport)
{
    return transport == NULL || transport->position >= transport->size;
}
