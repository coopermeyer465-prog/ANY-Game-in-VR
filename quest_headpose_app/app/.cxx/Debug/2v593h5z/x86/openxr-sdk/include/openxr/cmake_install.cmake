# Install script for directory: /Users/marissameyer/Desktop/Games In VR/third_party/openxr-sdk-source/include/openxr

# Set the install prefix
if(NOT DEFINED CMAKE_INSTALL_PREFIX)
  set(CMAKE_INSTALL_PREFIX "/usr/local")
endif()
string(REGEX REPLACE "/$" "" CMAKE_INSTALL_PREFIX "${CMAKE_INSTALL_PREFIX}")

# Set the install configuration name.
if(NOT DEFINED CMAKE_INSTALL_CONFIG_NAME)
  if(BUILD_TYPE)
    string(REGEX REPLACE "^[^A-Za-z0-9_]+" ""
           CMAKE_INSTALL_CONFIG_NAME "${BUILD_TYPE}")
  else()
    set(CMAKE_INSTALL_CONFIG_NAME "Debug")
  endif()
  message(STATUS "Install configuration: \"${CMAKE_INSTALL_CONFIG_NAME}\"")
endif()

# Set the component getting installed.
if(NOT CMAKE_INSTALL_COMPONENT)
  if(COMPONENT)
    message(STATUS "Install component: \"${COMPONENT}\"")
    set(CMAKE_INSTALL_COMPONENT "${COMPONENT}")
  else()
    set(CMAKE_INSTALL_COMPONENT)
  endif()
endif()

# Install shared libraries without execute permission?
if(NOT DEFINED CMAKE_INSTALL_SO_NO_EXE)
  set(CMAKE_INSTALL_SO_NO_EXE "0")
endif()

# Is this installation the result of a crosscompile?
if(NOT DEFINED CMAKE_CROSSCOMPILING)
  set(CMAKE_CROSSCOMPILING "TRUE")
endif()

# Set default install directory permissions.
if(NOT DEFINED CMAKE_OBJDUMP)
  set(CMAKE_OBJDUMP "/opt/homebrew/share/android-commandlinetools/ndk/26.3.11579264/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-objdump")
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xHeadersx" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/include/openxr" TYPE FILE FILES
    "/Users/marissameyer/Desktop/Games In VR/quest_headpose_app/app/.cxx/Debug/2v593h5z/x86/openxr-sdk/include/openxr/openxr_platform_defines.h"
    "/Users/marissameyer/Desktop/Games In VR/quest_headpose_app/app/.cxx/Debug/2v593h5z/x86/openxr-sdk/include/openxr/openxr.h"
    "/Users/marissameyer/Desktop/Games In VR/quest_headpose_app/app/.cxx/Debug/2v593h5z/x86/openxr-sdk/include/openxr/openxr_loader_negotiation.h"
    "/Users/marissameyer/Desktop/Games In VR/quest_headpose_app/app/.cxx/Debug/2v593h5z/x86/openxr-sdk/include/openxr/openxr_platform.h"
    "/Users/marissameyer/Desktop/Games In VR/quest_headpose_app/app/.cxx/Debug/2v593h5z/x86/openxr-sdk/include/openxr/openxr_reflection.h"
    "/Users/marissameyer/Desktop/Games In VR/quest_headpose_app/app/.cxx/Debug/2v593h5z/x86/openxr-sdk/include/openxr/openxr_reflection_structs.h"
    "/Users/marissameyer/Desktop/Games In VR/quest_headpose_app/app/.cxx/Debug/2v593h5z/x86/openxr-sdk/include/openxr/openxr_reflection_parent_structs.h"
    )
endif()

