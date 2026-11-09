cmake_minimum_required(VERSION 3.22)
project(mobdroid C CXX)

add_library(mobdroid_uvc SHARED uvc_jni.cpp)
target_link_libraries(mobdroid_uvc android log)
