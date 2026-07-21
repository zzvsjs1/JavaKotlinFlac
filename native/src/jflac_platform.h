#ifndef JFLAC_PLATFORM_H
#define JFLAC_PLATFORM_H

#include <stddef.h>

#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>

typedef struct JflacMutex
{
    SRWLOCK native;
} JflacMutex;
#define JFLAC_MUTEX_INITIALIZER                                                                                       \
    {                                                                                                                  \
        SRWLOCK_INIT                                                                                                   \
    }
#else
#include <pthread.h>

typedef struct JflacMutex
{
    pthread_mutex_t native;
} JflacMutex;
#define JFLAC_MUTEX_INITIALIZER                                                                                       \
    {                                                                                                                  \
        PTHREAD_MUTEX_INITIALIZER                                                                                      \
    }
#endif

/*
 * A small platform-neutral once primitive is sufficient here. Initialisers do
 * not recurse into the same guard, and the lock also supplies the required
 * publication barrier for all threads that later read the initialised state.
 */
typedef struct JflacOnce
{
    JflacMutex mutex;
    int complete;
} JflacOnce;

#define JFLAC_ONCE_INITIALIZER                                                                                        \
    {                                                                                                                  \
        JFLAC_MUTEX_INITIALIZER, 0                                                                                     \
    }

typedef void (*JflacOnceInitialiser)(void);

typedef struct JflacModule
{
    void *handle;
    /* Non-zero when this module carries a loader reference to release. */
    int owns_handle;
} JflacModule;

void jflac_mutex_lock(JflacMutex *mutex);
void jflac_mutex_unlock(JflacMutex *mutex);
void jflac_call_once(JflacOnce *once, JflacOnceInitialiser initialiser);

/* Opens one dynamic library by its absolute path beside the loaded JNI shim. */
int jflac_open_sibling_library(const char *library_filename, JflacModule *module, char *error, size_t error_size);

/*
 * Releases an owned loader reference and clears the module. The ownership
 * flag prevents any deliberately borrowed platform handle from being
 * unloaded, and makes this safe for partially initialised cleanup paths.
 */
void jflac_close_library(JflacModule *module);

/* Copies a function or data symbol address into the caller's typed pointer slot. */
int jflac_resolve_symbol(const JflacModule *module, const char *symbol_name, void *target, size_t target_size,
                         char *error, size_t error_size);

#endif
