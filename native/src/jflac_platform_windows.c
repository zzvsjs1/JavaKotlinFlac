#include "jflac_platform.h"

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

void jflac_mutex_lock(JflacMutex *mutex)
{
    AcquireSRWLockExclusive(&mutex->native);
}

void jflac_mutex_unlock(JflacMutex *mutex)
{
    ReleaseSRWLockExclusive(&mutex->native);
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

static void set_windows_error(char *error, size_t error_size, const char *message, DWORD code)
{
    snprintf(error, error_size, "%s (Windows error %lu).", message, (unsigned long)code);
}

static wchar_t *utf8_filename_to_wide(const char *filename, char *error, size_t error_size)
{
    int required = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, filename, -1, NULL, 0);
    if (required <= 0)
    {
        set_windows_error(error, error_size, "The bundled library filename is not valid UTF-8", GetLastError());
        return NULL;
    }

    wchar_t *wide = (wchar_t *)calloc((size_t)required, sizeof(wchar_t));
    if (wide == NULL)
    {
        snprintf(error, error_size, "Unable to allocate the bundled library filename.");
        return NULL;
    }

    if (MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, filename, -1, wide, required) <= 0)
    {
        set_windows_error(error, error_size, "Unable to convert the bundled library filename", GetLastError());
        free(wide);
        return NULL;
    }

    return wide;
}

static wchar_t *loaded_module_path(HMODULE module, char *error, size_t error_size)
{
    DWORD capacity = 512u;
    while (capacity <= 32768u)
    {
        wchar_t *path = (wchar_t *)calloc((size_t)capacity, sizeof(wchar_t));
        if (path == NULL)
        {
            snprintf(error, error_size, "Unable to allocate the JNI shim path.");
            return NULL;
        }

        DWORD length = GetModuleFileNameW(module, path, capacity);
        if (length == 0u)
        {
            set_windows_error(error, error_size, "Unable to resolve the JNI shim path", GetLastError());
            free(path);
            return NULL;
        }

        if (length < capacity - 1u)
        {
            return path;
        }

        free(path);
        capacity *= 2u;
    }

    snprintf(error, error_size, "The JNI shim path exceeds the Windows path limit.");
    return NULL;
}

int jflac_open_sibling_library(const char *library_filename, JflacModule *module, char *error, size_t error_size)
{
    HMODULE shim_module = NULL;
    if (!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                            (LPCWSTR)(uintptr_t)&jflac_open_sibling_library, &shim_module))
    {
        set_windows_error(error, error_size, "Unable to locate the loaded JNI shim", GetLastError());
        return 0;
    }

    wchar_t *module_path = loaded_module_path(shim_module, error, error_size);
    wchar_t *wide_filename = utf8_filename_to_wide(library_filename, error, error_size);
    if (module_path == NULL || wide_filename == NULL)
    {
        free(module_path);
        free(wide_filename);
        return 0;
    }

    wchar_t *separator = wcsrchr(module_path, L'\\');
    wchar_t *forward_separator = wcsrchr(module_path, L'/');
    if (forward_separator != NULL && (separator == NULL || forward_separator > separator))
    {
        separator = forward_separator;
    }

    if (separator == NULL)
    {
        snprintf(error, error_size, "The JNI shim path does not contain a parent directory.");
        free(module_path);
        free(wide_filename);
        return 0;
    }

    size_t directory_length = (size_t)(separator - module_path) + 1u;
    size_t filename_length = wcslen(wide_filename) + 1u;
    wchar_t *sibling_path = (wchar_t *)realloc(module_path, (directory_length + filename_length) * sizeof(wchar_t));
    if (sibling_path == NULL)
    {
        snprintf(error, error_size, "Unable to allocate the bundled library path.");
        free(module_path);
        free(wide_filename);
        return 0;
    }

    memcpy(sibling_path + directory_length, wide_filename, filename_length * sizeof(wchar_t));
    free(wide_filename);

    HMODULE loaded = GetModuleHandleW(sibling_path);
    if (loaded == NULL)
    {
        /* The absolute path prevents fallback to the current directory or PATH. */
        loaded = LoadLibraryW(sibling_path);
    }

    free(sibling_path);
    if (loaded == NULL)
    {
        set_windows_error(error, error_size, "Unable to load the bundled libFLAC runtime beside the JNI shim",
                          GetLastError());
        return 0;
    }

    module->handle = loaded;
    return 1;
}

int jflac_resolve_symbol(const JflacModule *module, const char *symbol_name, void *target, size_t target_size,
                         char *error, size_t error_size)
{
    FARPROC address = GetProcAddress((HMODULE)module->handle, symbol_name);
    if (address == NULL)
    {
        snprintf(error, error_size, "Missing symbol in the bundled libFLAC runtime: %s", symbol_name);
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
