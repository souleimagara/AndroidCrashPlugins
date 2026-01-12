cmake_minimum_required(VERSION 3.18.1)

project("crashreporter-native")

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Create the native library
add_library(
    crashreporter-native
    SHARED
    native_crash_handler.cpp
)

# Find and link required libraries
find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(
    crashreporter-native
    ${log-lib}
    ${android-lib}
)

# Set compiler flags
target_compile_options(
    crashreporter-native
    PRIVATE
    -Wall
    -Wextra
    -funwind-tables
    -fno-omit-frame-pointer
)
