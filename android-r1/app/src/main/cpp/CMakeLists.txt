cmake_minimum_required(VERSION 3.10.2)
project(voice_sat_native)
set(CMAKE_C_STANDARD 99)
set(CMAKE_CXX_STANDARD 11)

set(VENDOR ${CMAKE_CURRENT_SOURCE_DIR}/vendor)
if(NOT EXISTS ${VENDOR}/opus/CMakeLists.txt OR NOT EXISTS ${VENDOR}/libfvad/include/fvad.h)
  message(FATAL_ERROR "Native deps missing. Run android-r1/scripts/bootstrap-native.sh first")
endif()

set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_CUSTOM_MODES OFF CACHE BOOL "" FORCE)
set(OPUS_USE_NEON OFF CACHE BOOL "" FORCE)
add_subdirectory(${VENDOR}/opus opus-build EXCLUDE_FROM_ALL)

set(FV ${VENDOR}/libfvad)
add_library(fvad STATIC
  ${FV}/src/fvad.c
  ${FV}/src/vad/vad_core.c
  ${FV}/src/vad/vad_filterbank.c
  ${FV}/src/vad/vad_gmm.c
  ${FV}/src/vad/vad_sp.c
  ${FV}/src/signal_processing/division_operations.c
  ${FV}/src/signal_processing/energy.c
  ${FV}/src/signal_processing/resample_48khz.c)
target_include_directories(fvad PUBLIC ${FV}/include ${FV}/src)

add_library(voice_sat_native SHARED voice_sat_native.cpp)
target_include_directories(voice_sat_native PRIVATE ${VENDOR}/opus/include ${FV}/include)
target_link_libraries(voice_sat_native opus fvad log)
set_target_properties(voice_sat_native PROPERTIES LINK_FLAGS "-Wl,--hash-style=both")
