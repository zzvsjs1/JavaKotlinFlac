#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include "jflac_platform.h"

#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* A data address avoids non-standard function-pointer conversion in dladdr(). */
static const char g_jflac_module_anchor = 0;

void jflac_mutex_lock(JflacMutex *mutex)
{
    (void)pthread_mutex_lock(&mutex->native);
}

void jflac_mutex_unlock(JflacMutex *mutex)
{
    (void)pthread_mutex_unlock(&mutex->native);
}

void jflac_mutex_destroy(JflacMutex *mutex)
{
    if (mutex != NULL)
    {
        (void)pthread_mutex_destroy(&mutex->native);
    }
}

void jflac_call_once(JflacOnce *once, JflacOnceInitialiser initialiser)
{
    jflac_mutex_lock(&once->mutex);
    if (!once->complete)
    {
        initialiser();
        once->complete = 1;
    }

    jflac_mutex_unlock(&once->mutex);
}

int jflac_open_sibling_library(const char *library_filename, JflacModule *module, char *error, size_t error_size)
{
    if (module == NULL)
    {
        snprintf(error, error_size, "The destination module must not be null.");
        return 0;
    }

    if (module->handle != NULL || module->owns_handle)
    {
        snprintf(error, error_size, "The destination module already contains a library handle.");
        return 0;
    }

    if (library_filename == NULL)
    {
        snprintf(error, error_size, "The bundled library filename must not be null.");
        return 0;
    }

    Dl_info module_info;
    if (dladdr((const void *)&g_jflac_module_anchor, &module_info) == 0 || module_info.dli_fname == NULL)
    {
        snprintf(error, error_size, "Unable to locate the loaded JNI shim.");
        return 0;
    }

    const char *separator = strrchr(module_info.dli_fname, '/');
    if (separator == NULL)
    {
        snprintf(error, error_size, "The JNI shim path does not contain a parent directory.");
        return 0;
    }

    size_t directory_length = (size_t)(separator - module_info.dli_fname) + 1u;
    size_t filename_length = strlen(library_filename);
    char *sibling_path = (char *)malloc(directory_length + filename_length + 1u);
    if (sibling_path == NULL)
    {
        snprintf(error, error_size, "Unable to allocate the bundled library path.");
        return 0;
    }

    memcpy(sibling_path, module_info.dli_fname, directory_length);
    memcpy(sibling_path + directory_length, library_filename, filename_length + 1u);

    dlerror();
    void *loaded = dlopen(sibling_path, RTLD_NOW | RTLD_LOCAL);
    const char *load_error = dlerror();
    free(sibling_path);
    if (loaded == NULL)
    {
        snprintf(error, error_size, "Unable to load the bundled libFLAC runtime beside the JNI shim: %s",
                 load_error != NULL ? load_error : "unknown dynamic-loader error");
        return 0;
    }

    module->handle = loaded;
    module->owns_handle = 1;
    return 1;
}

int jflac_close_library(JflacModule *module)
{
    if (module == NULL)
    {
        return 1;
    }

    if (module->handle != NULL && module->owns_handle)
    {
        if (dlclose(module->handle) != 0)
        {
            /*
             * Keep the owned handle visible when the loader rejects the
             * unload. Clearing it here would lose the only retryable
             * reference and conceal a loader-reference leak.
             */
            return 0;
        }
    }

    module->handle = NULL;
    module->owns_handle = 0;
    return 1;
}

int jflac_resolve_symbol(const JflacModule *module, const char *symbol_name, void *target, size_t target_size,
                         char *error, size_t error_size)
{
    dlerror();
    void *address = dlsym(module->handle, symbol_name);
    const char *lookup_error = dlerror();
    if (lookup_error != NULL)
    {
        snprintf(error, error_size, "Missing symbol in the bundled libFLAC runtime: %s (%s)", symbol_name,
                 lookup_error);
        return 0;
    }

    if (target_size != sizeof(address))
    {
        snprintf(error, error_size, "Cannot represent the libFLAC symbol pointer for %s on this platform.",
                 symbol_name);
        return 0;
    }

    memcpy(target, &address, sizeof(address));
    return 1;
}
