cmake_minimum_required(VERSION 3.22.1)
project(laserfusion CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(laserfusion SHARED fusion.cpp jni_bridge.cpp)
target_compile_options(laserfusion PRIVATE -O3 -fno-exceptions -fvisibility=hidden -Wall)
# 16 KB page alignment (required for Android 15+ devices with 16 KB pages).
target_link_options(laserfusion PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(laserfusion log)
