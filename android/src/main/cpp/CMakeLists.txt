# Native blur engine build config.
#
# Pure C, no C++/STL dependency (ANDROID_STL=none set in build.gradle) —
# links only against jnigraphics (for AndroidBitmap_* functions).
#
# This library is only ever loaded on Android API 21-30 (see
# BlurThreadPool.kt / LegacyBlurController.kt) — API 31+ uses the GPU
# RenderEffect path and never touches this at all.

cmake_minimum_required(VERSION 3.10.2)
project(blurvibe_native C)

add_library(
  blurvibe_native
  SHARED
  blur_engine.c
)

find_library(jnigraphics-lib jnigraphics)
find_library(log-lib log)

target_link_libraries(
  blurvibe_native
  ${jnigraphics-lib}
  ${log-lib}
)

# Reasonable, conservative optimization level. -O2 is a well-established,
# safe optimization tier (unlike -O3, which can occasionally expose
# undefined-behavior-sensitive code more aggressively) — appropriate given
# this is hand-written pointer-arithmetic code that hasn't been tested on
# a compiler.
target_compile_options(blurvibe_native PRIVATE -O2 -Wall)
