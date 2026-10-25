cmake_minimum_required(VERSION 3.22.1)
project(neuz3d_native LANGUAGES CXX)

add_library(neuz3d SHARED native_engine.cpp)
target_compile_features(neuz3d PRIVATE cxx_std_20)

find_library(ANDROID_LOG_LIB log)
find_library(ANDROID_VULKAN_LIB vulkan)
target_link_libraries(neuz3d PRIVATE ${ANDROID_LOG_LIB} ${ANDROID_VULKAN_LIB} dl)
