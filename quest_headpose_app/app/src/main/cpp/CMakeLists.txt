cmake_minimum_required(VERSION 3.22.1)
project(QuestHeadposeNative LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(OPENXR_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/openxr-sdk-source")
add_subdirectory("${OPENXR_ROOT}" "${CMAKE_BINARY_DIR}/openxr-sdk")

set(QUEST_XR_DIR "${CMAKE_CURRENT_LIST_DIR}/quest_headpose_xr")

add_library(
    quest_headpose_xr
    MODULE
    "${QUEST_XR_DIR}/logger.cpp"
    "${QUEST_XR_DIR}/main.cpp"
    "${QUEST_XR_DIR}/openxr_program.cpp"
    "${QUEST_XR_DIR}/pch.cpp"
    "${QUEST_XR_DIR}/platformplugin_android.cpp"
    "${QUEST_XR_DIR}/platformplugin_factory.cpp"
    "${QUEST_XR_DIR}/swapchain_image_data.cpp"
    "${QUEST_XR_DIR}/graphicsplugin_factory.cpp"
    "${QUEST_XR_DIR}/graphicsplugin_opengles.cpp"
    $<TARGET_OBJECTS:android_native_app_glue>
)

target_compile_definitions(
    quest_headpose_xr
    PRIVATE
    XR_USE_PLATFORM_ANDROID
    XR_USE_PLATFORM_EGL
    XR_USE_GRAPHICS_API_OPENGL_ES
    DEFAULT_GRAPHICS_PLUGIN_OPENGLES
)

target_include_directories(
    quest_headpose_xr
    PRIVATE
    "${QUEST_XR_DIR}"
    "${OPENXR_ROOT}/src"
    "${OPENXR_ROOT}/src/common"
    "${OPENXR_ROOT}/src/external/span-lite/include"
)

target_link_libraries(
    quest_headpose_xr
    PRIVATE
    ${ANDROID_LIBRARY}
    ${ANDROID_LOG_LIBRARY}
    OpenXR::openxr_loader
    openxr-gfxwrapper
)
