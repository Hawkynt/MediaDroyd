# espeak-ng (GPL-3.0-or-later) for the spoken subtitles' Piper voices (#531): text into the IPA phonemes Piper's voices
# were trained on. Only in builds for the household's own devices (-Pespeak), never in a release: it is a library of
# its own, mediadroyd_espeak, which MediaDroyd's LGPL library does not link and the app loads only where it is there.
# ESPEAK_SOURCES holds espeak-ng's release tree as native/build.gradle.kts (espeakSource) fetches and checks it; its
# notice is app/THIRD-PARTY-NOTICE.espeak-ng.txt.
#
# espeak-ng's own CMake builds its library; on the desktop also its phoneme tables and dictionaries (the `data` target,
# which runs espeak-ng itself — so not where it is cross-compiled for Android, whose data comes from a desktop build).

set(USE_MBROLA OFF CACHE BOOL "" FORCE)
set(USE_LIBSONIC OFF CACHE BOOL "" FORCE)
set(USE_LIBPCAUDIO OFF CACHE BOOL "" FORCE)
set(USE_ASYNC OFF CACHE BOOL "" FORCE)
set(USE_KLATT OFF CACHE BOOL "" FORCE)
set(USE_SPEECHPLAYER OFF CACHE BOOL "" FORCE)
set(ESPEAK_BUILD_MANPAGES OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_subdirectory(${ESPEAK_SOURCES} espeak-ng EXCLUDE_FROM_ALL)

add_library(mediadroyd_espeak SHARED espeak_jni.cpp)
target_link_libraries(mediadroyd_espeak PRIVATE espeak-ng)
# Its public header pulls in its compat wchar.h, which needs the Unicode tables' header it bundles.
target_include_directories(mediadroyd_espeak PRIVATE ${ESPEAK_SOURCES}/src/ucd-tools/src/include)
if(ANDROID)
    target_link_options(mediadroyd_espeak PRIVATE "-Wl,-z,max-page-size=16384")
else()
    find_package(JNI REQUIRED)
    target_include_directories(mediadroyd_espeak PRIVATE ${JNI_INCLUDE_DIRS})
    add_dependencies(mediadroyd_espeak data)
    if(CMAKE_CXX_COMPILER_ID STREQUAL "GNU" OR CMAKE_CXX_COMPILER_ID STREQUAL "Clang")
        target_link_options(mediadroyd_espeak PRIVATE -static-libstdc++ -static-libgcc "-Wl,--exclude-libs,ALL")
    endif()
    if(MEDIADROYD_OUTPUT)
        set_target_properties(mediadroyd_espeak PROPERTIES
            LIBRARY_OUTPUT_DIRECTORY "$<1:${MEDIADROYD_OUTPUT}>"
            RUNTIME_OUTPUT_DIRECTORY "$<1:${MEDIADROYD_OUTPUT}>")
        # Its phoneme tables and dictionaries beside it, as the app looks for them.
        add_custom_command(TARGET mediadroyd_espeak POST_BUILD
            COMMAND ${CMAKE_COMMAND} -E copy_directory
                "${CMAKE_CURRENT_BINARY_DIR}/espeak-ng/espeak-ng-data" "${MEDIADROYD_OUTPUT}/espeak-ng-data")
    endif()
endif()
