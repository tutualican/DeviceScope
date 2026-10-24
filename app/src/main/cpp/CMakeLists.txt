cmake_minimum_required(VERSION 3.22.1)
project(dsbench CXX)

# ---------------------------------------------------------------------------
# Shader toolchain: the NDK ships glslc, so shaders are compiled to SPIR-V at
# build time and embedded into the library as byte arrays. No runtime files,
# no assets, and the SPIR-V is guaranteed to match the source we ship.
# ---------------------------------------------------------------------------
if (CMAKE_HOST_WIN32)
    set(GLSLC_HOST_TAG "windows-x86_64")
elseif (CMAKE_HOST_APPLE)
    set(GLSLC_HOST_TAG "darwin-x86_64")
else()
    set(GLSLC_HOST_TAG "linux-x86_64")
endif()

set(GLSLC "${ANDROID_NDK}/shader-tools/${GLSLC_HOST_TAG}/glslc")
if (CMAKE_HOST_WIN32)
    set(GLSLC "${GLSLC}.exe")
endif()

if (NOT EXISTS "${GLSLC}")
    message(FATAL_ERROR "glslc not found at ${GLSLC}; Vulkan shaders cannot be built")
endif()

function(add_spirv_header SOURCE NAME OUT_VAR)
    set(spv "${CMAKE_CURRENT_BINARY_DIR}/${NAME}.spv")
    set(hdr "${CMAKE_CURRENT_BINARY_DIR}/${NAME}_spv.h")

    add_custom_command(
            OUTPUT "${spv}"
            COMMAND "${GLSLC}" --target-env=vulkan1.0 -O -o "${spv}" "${SOURCE}"
            DEPENDS "${SOURCE}"
            COMMENT "glslc ${NAME}")

    add_custom_command(
            OUTPUT "${hdr}"
            COMMAND "${CMAKE_COMMAND}"
                    -DSPV=${spv} -DOUT=${hdr} -DNAME=${NAME}
                    -P "${CMAKE_CURRENT_SOURCE_DIR}/SpvToHeader.cmake"
            DEPENDS "${spv}" "${CMAKE_CURRENT_SOURCE_DIR}/SpvToHeader.cmake"
            COMMENT "embedding ${NAME}.spv")

    set(${OUT_VAR} "${hdr}" PARENT_SCOPE)
endfunction()

add_spirv_header("${CMAKE_CURRENT_SOURCE_DIR}/gpu/vk_compute.comp" vk_compute COMPUTE_HDR)
add_spirv_header("${CMAKE_CURRENT_SOURCE_DIR}/gpu/vk_scene.vert" vk_scene_vert SCENE_VERT_HDR)
add_spirv_header("${CMAKE_CURRENT_SOURCE_DIR}/gpu/vk_scene.frag" vk_scene_frag SCENE_FRAG_HDR)

add_library(dsbench SHARED
        bench.cpp
        validate.cpp
        kernel_o2.cpp
        kernel_o3.cpp
        gpu_vk.cpp
        ${COMPUTE_HDR}
        ${SCENE_VERT_HDR}
        ${SCENE_FRAG_HDR})

# The two kernel twins must be built at different optimisation levels: the
# validator compares their timing to detect compiler elision of real work.
set_source_files_properties(kernel_o2.cpp PROPERTIES COMPILE_OPTIONS "-O2")
set_source_files_properties(kernel_o3.cpp PROPERTIES COMPILE_OPTIONS "-O3")

target_include_directories(dsbench PRIVATE "${CMAKE_CURRENT_BINARY_DIR}")

target_compile_options(dsbench PRIVATE
        -O3
        -ffast-math
        -funroll-loops
        -fno-exceptions
        -fno-rtti
        -Wall)

target_link_libraries(dsbench PRIVATE vulkan log)

set_target_properties(dsbench PROPERTIES
        CXX_STANDARD 17
        CXX_STANDARD_REQUIRED ON)
