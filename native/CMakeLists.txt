cmake_minimum_required(VERSION 3.20)
project(jflac_jni C)

if(NOT WIN32)
    message(FATAL_ERROR "The JNI shim currently supports Windows only. The platform resource layout can be extended for Linux and macOS later.")
endif()

find_package(JNI REQUIRED)

set(JFLAC_FLAC_INCLUDE_DIR "" CACHE PATH "Path to the FLAC public include directory")
if(NOT JFLAC_FLAC_INCLUDE_DIR)
    set(JFLAC_FLAC_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../flac-1.5.0/include")
endif()

add_library(jflac-jni SHARED src/jflac_jni.c)

set_target_properties(jflac-jni PROPERTIES
    C_STANDARD 11
    C_STANDARD_REQUIRED YES
    C_EXTENSIONS NO
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin"
    ARCHIVE_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/lib"
)

target_include_directories(jflac-jni PRIVATE
    ${JNI_INCLUDE_DIRS}
    "${JFLAC_FLAC_INCLUDE_DIR}"
)

target_compile_definitions(jflac-jni PRIVATE
    NOMINMAX
    WIN32_LEAN_AND_MEAN
)

if(MSVC)
    target_compile_options(jflac-jni PRIVATE /W4 /utf-8)
endif()

target_link_libraries(jflac-jni PRIVATE kernel32)
