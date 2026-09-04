#ifndef JFLAC_FUZZ_TRANSPORT_H
#define JFLAC_FUZZ_TRANSPORT_H

#include <stddef.h>
#include <stdint.h>

/* Keep one mutation bounded even when a fuzzer is invoked without -max_len. */
#define JFLAC_FUZZ_MAX_INPUT_SIZE (1024u * 1024u)

typedef struct JflacFuzzTransport
{
    const uint8_t *data;
    size_t size;
    size_t position;
    size_t chunk_limit;
} JflacFuzzTransport;

void jflac_fuzz_transport_init(JflacFuzzTransport *transport, const uint8_t *data, size_t size);

size_t jflac_fuzz_transport_read(JflacFuzzTransport *transport, void *destination, size_t requested_bytes);

size_t jflac_fuzz_transport_read_records(
    JflacFuzzTransport *transport,
    void *destination,
    size_t record_size,
    size_t requested_records);

int jflac_fuzz_transport_seek_absolute(JflacFuzzTransport *transport, uint64_t absolute_offset);

int jflac_fuzz_transport_seek(JflacFuzzTransport *transport, int64_t offset, int whence);

uint64_t jflac_fuzz_transport_tell(const JflacFuzzTransport *transport);

uint64_t jflac_fuzz_transport_length(const JflacFuzzTransport *transport);

int jflac_fuzz_transport_eof(const JflacFuzzTransport *transport);

#endif
