cmake_minimum_required(VERSION 3.22.1)
project("echo-llama")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Aggressive optimization for speed
set(CMAKE_BUILD_TYPE Release)
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG -ffast-math -fno-finite-math-only")
set(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG -ffast-math -fno-finite-math-only")

# Disable unnecessary llama.cpp features for Android
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

# Build llama.cpp static library
add_subdirectory(llama.cpp EXCLUDE_FROM_ALL)

# Create our JNI wrapper library
add_library(echo-llama SHARED
    llama_jni.cpp
)

target_include_directories(echo-llama PRIVATE
    llama.cpp
    llama.cpp/include
    llama.cpp/ggml/include
    llama.cpp/src
)

# Link only required libraries (common library might not build on Android)
target_link_libraries(echo-llama
    llama
    ggml
    android
    log
)
