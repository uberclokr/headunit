cmake_minimum_required(VERSION 3.22)
project(helmrtl C)

set(TP ${CMAKE_CURRENT_SOURCE_DIR}/third_party)

# libusb has no official CMake build; this is the standard Android file list.
# android/config.h supplies the platform config the autotools build would.
add_library(usb STATIC
    ${TP}/libusb/libusb/core.c
    ${TP}/libusb/libusb/descriptor.c
    ${TP}/libusb/libusb/hotplug.c
    ${TP}/libusb/libusb/io.c
    ${TP}/libusb/libusb/strerror.c
    ${TP}/libusb/libusb/sync.c
    ${TP}/libusb/libusb/os/linux_usbfs.c
    ${TP}/libusb/libusb/os/linux_netlink.c
    ${TP}/libusb/libusb/os/events_posix.c
    ${TP}/libusb/libusb/os/threads_posix.c)
target_include_directories(usb
    PUBLIC ${TP}/libusb/libusb
    PRIVATE ${TP}/libusb/android)

# librtlsdr core + tuner drivers only — the rtl_* CLI tools are not built.
# librtlsdr.c carries the Helm fd-wrap patch for Android USB permissions.
add_library(rtlsdr STATIC
    ${TP}/rtl-sdr/src/librtlsdr.c
    ${TP}/rtl-sdr/src/tuner_e4k.c
    ${TP}/rtl-sdr/src/tuner_fc0012.c
    ${TP}/rtl-sdr/src/tuner_fc0013.c
    ${TP}/rtl-sdr/src/tuner_fc2580.c
    ${TP}/rtl-sdr/src/tuner_r82xx.c)
target_include_directories(rtlsdr PUBLIC ${TP}/rtl-sdr/include)
target_link_libraries(rtlsdr usb)

add_library(helmrtl SHARED rtl_jni.c)
target_link_libraries(helmrtl rtlsdr log)
