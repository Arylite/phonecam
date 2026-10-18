cmake_minimum_required(VERSION 3.24)
project(PhoneCam LANGUAGES CXX RC)

# CI passes the release tag (v1.2.3 -> 1.2.3); it lands in the executable's version resource.
set(PHONECAM_VERSION "0.1.0" CACHE STRING "Version shown in the file properties")
if(NOT PHONECAM_VERSION MATCHES "^([0-9]+)[.]([0-9]+)[.]([0-9]+)$")
    message(FATAL_ERROR "PHONECAM_VERSION must look like 1.2.3, got '${PHONECAM_VERSION}'")
endif()
# Copied right away: later commands may overwrite CMAKE_MATCH_*.
set(PHONECAM_VERSION_MAJOR ${CMAKE_MATCH_1})
set(PHONECAM_VERSION_MINOR ${CMAKE_MATCH_2})
set(PHONECAM_VERSION_PATCH ${CMAKE_MATCH_3})

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded")  # static CRT: nothing to install besides our two files
add_compile_definitions(UNICODE _UNICODE WIN32_LEAN_AND_MEAN NOMINMAX)
add_compile_options("$<$<COMPILE_LANGUAGE:CXX>:/W4;/WX;/permissive-;/utf-8>")

# Virtual camera, loaded by the Windows Camera Frame Server.
add_library(PhoneCamSource SHARED camera/source.cpp camera/source.def)
target_link_libraries(PhoneCamSource PRIVATE mfplat mfuuid mfsensorgroup advapi32 ole32 shell32 runtimeobject)
target_compile_options(PhoneCamSource PRIVATE /wd4324)  # padding warning from the SDK's WRL headers

# Client: receives the phone stream and feeds the camera.
add_executable(PhoneCam WIN32 client/main.cpp client/phone.cpp client/adb.cpp client/install.cpp client/decoder.cpp client/app.rc)
target_link_libraries(PhoneCam PRIVATE mfplat mfuuid ws2_32 comctl32 shell32 ole32 advapi32 wmcodecdspuuid)
configure_file(client/version.h.in ${CMAKE_BINARY_DIR}/version.h @ONLY)
target_include_directories(PhoneCam PRIVATE ${CMAKE_BINARY_DIR})

# Both files side by side, as shipped.
set_target_properties(PhoneCamSource PhoneCam PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/bin
    RUNTIME_OUTPUT_DIRECTORY_RELEASE ${CMAKE_BINARY_DIR}/bin)
