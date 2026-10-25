cmake_minimum_required(VERSION 3.22.1)
project(neuz3d_native LANGUAGES CXX)

add_library(neuz3d SHARED native_engine.cpp)
target_compile_features(neuz3d PRIVATE cxx_std_20)

find_library(log-lib log)
find_library(vulkan-lib vulkan)
target_link_libraries(neuz3d PRIVATE ${log-lib} ${vulkan-lib} dl)
