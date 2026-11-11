cmake_minimum_required(VERSION 3.22)
project(mobdroid_uvc C)

if(POLICY CMP0169)
  cmake_policy(SET CMP0169 OLD)
endif()

include(FetchContent)
include(ExternalProject)

FetchContent_Declare(libusb
  URL https://github.com/libusb/libusb/releases/download/v1.0.30/libusb-1.0.30.tar.bz2
  URL_HASH SHA256=fea36f34f9156400209595e300840767ab1a385ede1dc7ee893015aea9c6dbaf
)
FetchContent_Declare(libuvc
  URL https://github.com/libuvc/libuvc/archive/refs/tags/v0.0.9.tar.gz
  URL_HASH SHA256=825775b21b8558ac2afcbc2561ab1e5081898c9b0296673fcaa7cabb6e94078e
)
FetchContent_Declare(libjpeg_turbo
  URL https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/3.2.1/libjpeg-turbo-3.2.1.tar.gz
  URL_HASH SHA256=c27ad72a68da151177929aa969ae773a2da5ac2af9ce946079314fdac8cece7f
)

foreach(dependency libusb libuvc libjpeg_turbo)
  FetchContent_GetProperties(${dependency})
  if(NOT ${dependency}_POPULATED)
    FetchContent_Populate(${dependency})
  endif()
endforeach()

add_compile_options(-O2 -fvisibility=hidden)

add_library(usb STATIC
  ${libusb_SOURCE_DIR}/libusb/core.c
  ${libusb_SOURCE_DIR}/libusb/descriptor.c
  ${libusb_SOURCE_DIR}/libusb/hotplug.c
  ${libusb_SOURCE_DIR}/libusb/io.c
  ${libusb_SOURCE_DIR}/libusb/strerror.c
  ${libusb_SOURCE_DIR}/libusb/sync.c
  ${libusb_SOURCE_DIR}/libusb/os/events_posix.c
  ${libusb_SOURCE_DIR}/libusb/os/linux_netlink.c
  ${libusb_SOURCE_DIR}/libusb/os/linux_usbfs.c
  ${libusb_SOURCE_DIR}/libusb/os/threads_posix.c
)
target_include_directories(usb
  PUBLIC ${libusb_SOURCE_DIR}/libusb
  PRIVATE ${libusb_SOURCE_DIR}/android ${libusb_SOURCE_DIR}/libusb/os
)
target_compile_options(usb PRIVATE -w)

set(libuvc_VERSION_MAJOR 0)
set(libuvc_VERSION_MINOR 0)
set(libuvc_VERSION_PATCH 9)
set(libuvc_VERSION 0.0.9)
configure_file(
  ${libuvc_SOURCE_DIR}/include/libuvc/libuvc_config.h.in
  ${CMAKE_CURRENT_BINARY_DIR}/libuvc-config/libuvc/libuvc_config.h
  @ONLY
)
add_library(uvc STATIC
  ${libuvc_SOURCE_DIR}/src/ctrl.c
  ${libuvc_SOURCE_DIR}/src/ctrl-gen.c
  ${libuvc_SOURCE_DIR}/src/device.c
  ${libuvc_SOURCE_DIR}/src/diag.c
  ${libuvc_SOURCE_DIR}/src/frame.c
  ${libuvc_SOURCE_DIR}/src/init.c
  ${libuvc_SOURCE_DIR}/src/misc.c
  ${libuvc_SOURCE_DIR}/src/stream.c
)
target_include_directories(uvc PUBLIC
  ${libuvc_SOURCE_DIR}/include
  ${CMAKE_CURRENT_BINARY_DIR}/libuvc-config
)
target_compile_definitions(uvc PUBLIC
  LIBUVC_NUM_TRANSFER_BUFS=128
  LIBUVC_PACKETS_PER_TRANSFER_MAX=8
)
target_compile_options(uvc PRIVATE -w)
target_link_libraries(uvc PUBLIC usb)

set(turbojpeg_binary_dir ${CMAKE_CURRENT_BINARY_DIR}/libjpeg-turbo)
ExternalProject_Add(libjpeg_turbo_build
  SOURCE_DIR ${libjpeg_turbo_SOURCE_DIR}
  BINARY_DIR ${turbojpeg_binary_dir}
  DOWNLOAD_COMMAND ""
  CMAKE_ARGS
    -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
    -DANDROID_ABI=${ANDROID_ABI}
    -DANDROID_PLATFORM=${ANDROID_PLATFORM}
    -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
    -DCMAKE_BUILD_TYPE=Release
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON
    -DENABLE_SHARED=OFF
    -DENABLE_STATIC=ON
    -DWITH_TURBOJPEG=ON
    -DWITH_TOOLS=OFF
  BUILD_COMMAND ${CMAKE_COMMAND} --build <BINARY_DIR> --target turbojpeg-static
  INSTALL_COMMAND ""
  BUILD_BYPRODUCTS ${turbojpeg_binary_dir}/libturbojpeg.a
)
add_library(turbojpeg STATIC IMPORTED)
set_target_properties(turbojpeg PROPERTIES
  IMPORTED_LOCATION ${turbojpeg_binary_dir}/libturbojpeg.a
  INTERFACE_INCLUDE_DIRECTORIES ${libjpeg_turbo_SOURCE_DIR}/src
)
add_dependencies(turbojpeg libjpeg_turbo_build)

add_library(mobdroid_uvc SHARED uvc_jni.c)
target_compile_options(mobdroid_uvc PRIVATE -O3 -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(mobdroid_uvc PRIVATE uvc usb turbojpeg android log)
target_link_options(mobdroid_uvc PRIVATE -Wl,--exclude-libs,ALL -Wl,--gc-sections -Wl,-z,max-page-size=16384)
