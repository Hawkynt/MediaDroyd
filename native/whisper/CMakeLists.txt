# whisper.cpp (MIT, the ggml authors) for subtitles made from the speech (#529): OpenAI's Whisper (MIT) on ggml's CPU
# backend, inside MediaDroyd's own library. WHISPER_SOURCES holds its release tree as native/build.gradle.kts
# (whisperSource) fetches and checks it; its notice is app/THIRD-PARTY-NOTICE.whisper.cpp.txt.
#
# Built for whatever machine runs it: no -march=native (GGML_NATIVE off), so a package built on one desktop runs on
# another; on x86-64 desktops AVX2 and FMA are taken as given (every x86-64 processor since 2013–2015), Android's arm64
# has NEON throughout.
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
if(NOT ANDROID AND CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64|AMD64|amd64")
    set(GGML_AVX ON CACHE BOOL "" FORCE)
    set(GGML_AVX2 ON CACHE BOOL "" FORCE)
    set(GGML_FMA ON CACHE BOOL "" FORCE)
    set(GGML_F16C ON CACHE BOOL "" FORCE)
endif()
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_subdirectory(${WHISPER_SOURCES} whisper.cpp EXCLUDE_FROM_ALL)
