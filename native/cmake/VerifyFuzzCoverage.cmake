# Inspect the actual dependency archives: sanitizer-only objects link successfully
# into a fuzzer but leave almost all parser branches invisible to libFuzzer.
if(NOT CMAKE_NM)
    find_program(CMAKE_NM NAMES llvm-nm nm REQUIRED)
endif()

set(missing_coverage_archives "")
foreach(archive IN ITEMS "${JFLAC_FLAC_STATIC_LIBRARY}" "${JFLAC_OGG_STATIC_LIBRARY}")
    if(NOT EXISTS "${archive}")
        message(FATAL_ERROR "Fuzzer coverage verification requires an existing archive: ${archive}")
    endif()

    execute_process(
        COMMAND "${CMAKE_NM}" -u "${archive}"
        RESULT_VARIABLE symbol_result
        OUTPUT_VARIABLE archive_symbols
        ERROR_VARIABLE symbol_error
    )
    if(NOT symbol_result EQUAL 0)
        message(FATAL_ERROR "Cannot inspect fuzzer archive ${archive}: ${symbol_error}")
    endif()

    if(NOT archive_symbols MATCHES "__sanitizer_cov_[A-Za-z0-9_]+")
        list(APPEND missing_coverage_archives "${archive}")
    else()
        message(STATUS "Verified libFuzzer coverage symbols: ${archive}")
    endif()
endforeach()

if(missing_coverage_archives)
    list(JOIN missing_coverage_archives "\n  " missing_coverage_listing)
    message(FATAL_ERROR
        "Fuzzer dependency archives lack __sanitizer_cov_ instrumentation:\n  ${missing_coverage_listing}\n"
        "Rebuild the fuzz-only libFLAC and libogg with -fsanitize=fuzzer-no-link,address,undefined."
    )
endif()
