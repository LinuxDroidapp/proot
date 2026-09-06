cmake_minimum_required(VERSION 3.22.1)
project(linuxdroid_proot C ASM)

set(CMAKE_C_STANDARD 99)

# Architecture and ABI detection
if(ANDROID)
    set(TARGET_ABI "${ANDROID_ABI}")
else()
    set(TARGET_ABI "${CMAKE_SYSTEM_PROCESSOR}")
endif()

message(STATUS "Configuring LinuxDroid PRoot for target: ${TARGET_ABI}")

# Per-architecture loader text addresses matching src/arch.h
set(HAS_POKEDATA_WORKAROUND FALSE)
if(TARGET_ABI STREQUAL "arm64-v8a" OR TARGET_ABI MATCHES "^(aarch64|arm64)")
    set(LOADER_ADDRESS "0x2000000000")
    set(HAS_POKEDATA_WORKAROUND TRUE)
    set(LOADER_EXTRA_LINK_FLAGS "-u,pokedata_workaround")
elseif(TARGET_ABI STREQUAL "armeabi-v7a" OR TARGET_ABI MATCHES "^(armv7|arm)")
    set(LOADER_ADDRESS "0xa0000000")
    set(LOADER_EXTRA_LINK_FLAGS "")
elseif(TARGET_ABI STREQUAL "x86" OR TARGET_ABI MATCHES "^(i.86|x86)$")
    set(LOADER_ADDRESS "0x20000000")
    set(LOADER_EXTRA_LINK_FLAGS "")
elseif(TARGET_ABI STREQUAL "x86_64" OR TARGET_ABI MATCHES "^(x86_64|amd64)")
    set(LOADER_ADDRESS "0x600000000000")
    set(LOADER_EXTRA_LINK_FLAGS "")
else()
    message(WARNING "Unknown ABI '${TARGET_ABI}', using default loader address 0x2000000000")
    set(LOADER_ADDRESS "0x2000000000")
    set(LOADER_EXTRA_LINK_FLAGS "")
endif()

set(PROOT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/src")
set(SHM_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/third_party/libandroid-shmem")
set(TALLOC_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/third_party/talloc")

# 1. Samba talloc library (static for standalone proot)
add_library(talloc STATIC "${TALLOC_ROOT}/talloc.c")
target_include_directories(talloc PUBLIC "${TALLOC_ROOT}")
target_compile_definitions(talloc PRIVATE
    _GNU_SOURCE
    __STDC_WANT_LIB_EXT1__=1
    TALLOC_BUILD_VERSION_MAJOR=2
    TALLOC_BUILD_VERSION_MINOR=5
    TALLOC_BUILD_VERSION_RELEASE=0
)
target_compile_options(talloc PRIVATE
    -fPIC
    -Wno-implicit-function-declaration
    -Wno-error=implicit-function-declaration
    -Wno-incompatible-function-pointer-types
)

# 2. Android Shared Memory emulation (static for standalone proot)
if(ANDROID)
    add_library(android-shmem STATIC "${SHM_ROOT}/shmem.c")
    target_include_directories(android-shmem PUBLIC "${SHM_ROOT}")
    target_compile_definitions(android-shmem PRIVATE _PATH_TMP="/data/data/com.linuxdroid/cache/")
    target_compile_options(android-shmem PRIVATE
        -fPIC
        -Wno-implicit-function-declaration
        -Wno-error=implicit-function-declaration
        -Wno-incompatible-function-pointer-types
    )
    target_link_libraries(android-shmem PUBLIC log android)
endif()

# 3. Native JNI process spawner (Android only)
if(ANDROID)
    add_library(linuxdroidspawn SHARED "${PROOT_ROOT}/native/native_spawn.c")
    target_compile_options(linuxdroidspawn PRIVATE
        -Wall -Wextra -O2
        -Wno-implicit-function-declaration
        -Wno-error=implicit-function-declaration
    )
    set_target_properties(linuxdroidspawn PROPERTIES OUTPUT_NAME linuxdroidspawn)

    add_library(pocketspawn SHARED "${PROOT_ROOT}/native/native_spawn.c")
    target_compile_options(pocketspawn PRIVATE
        -Wall -Wextra -O2
        -Wno-implicit-function-declaration
        -Wno-error=implicit-function-declaration
    )
    set_target_properties(pocketspawn PROPERTIES OUTPUT_NAME pocketspawn)
endif()

# 4. Generate build.h
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/build.h" [=[
#ifndef BUILD_H
#define BUILD_H
#define VERSION "5.1.107.92"
#define HAVE_PROCESS_VM 1
#define HAVE_SECCOMP_FILTER 1
#endif
]=])

# 5. Freestanding static loader executable
add_executable(prootloader
    "${PROOT_ROOT}/loader/loader.c"
    "${PROOT_ROOT}/loader/assembly.S"
)
target_include_directories(prootloader PRIVATE "${PROOT_ROOT}")
target_compile_options(prootloader PRIVATE
    -fPIC -ffreestanding
    -Wno-implicit-function-declaration
    -Wno-error=implicit-function-declaration
    -Wno-incompatible-function-pointer-types
)

if(ANDROID)
    set(LOADER_LINK_FLAGS "-static;-nostdlib;-Wl,--build-id=none,--rosegment,-Ttext=${LOADER_ADDRESS},-z,noexecstack")
else()
    set(LOADER_LINK_FLAGS "-static;-nostdlib;-Wl,--build-id=none,-Ttext=${LOADER_ADDRESS},-z,noexecstack")
endif()
if(LOADER_EXTRA_LINK_FLAGS)
    list(APPEND LOADER_LINK_FLAGS "-Wl,${LOADER_EXTRA_LINK_FLAGS}")
endif()
target_link_options(prootloader PRIVATE ${LOADER_LINK_FLAGS})
set_target_properties(prootloader PROPERTIES OUTPUT_NAME "prootloader-bin" SUFFIX "")

# 6. Loader info extraction (pokedata workaround)
if(NOT CMAKE_READELF)
    find_program(CMAKE_READELF NAMES llvm-readelf readelf)
endif()

add_custom_command(
    OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/loader-info.c"
    COMMAND "${CMAKE_COMMAND}"
        "-DREADELF=${CMAKE_READELF}"
        "-DLOADER=$<TARGET_FILE:prootloader>"
        "-DOUTPUT=${CMAKE_CURRENT_BINARY_DIR}/loader-info.c"
        -P "${CMAKE_CURRENT_SOURCE_DIR}/cmake/GenerateLoaderInfo.cmake"
    DEPENDS prootloader "${CMAKE_CURRENT_SOURCE_DIR}/cmake/GenerateLoaderInfo.cmake"
    VERBATIM
)

# 7. PRoot Core executable
set(PROOT_SOURCES
    cli/cli.c cli/proot.c cli/note.c
    execve/enter.c execve/exit.c execve/shebang.c execve/elf.c
    execve/ldso.c execve/auxv.c execve/aoxp.c
    path/binding.c path/glue.c path/canon.c path/f2fs-bug.c
    path/path.c path/proc.c path/temp.c
    syscall/seccomp.c syscall/syscall.c syscall/chain.c syscall/enter.c
    syscall/exit.c syscall/sysnum.c syscall/socket.c syscall/heap.c
    syscall/rlimit.c syscall/pipe_shadow.c
    tracee/tracee.c tracee/mem.c tracee/reg.c tracee/event.c
    tracee/seccomp.c tracee/statx.c
    ptrace/ptrace.c ptrace/user.c ptrace/wait.c
    extension/extension.c extension/ashmem_memfd/ashmem_memfd.c
    extension/kompat/kompat.c
    extension/fake_id0/chown.c extension/fake_id0/chroot.c
    extension/fake_id0/getsockopt.c extension/fake_id0/sendmsg.c
    extension/fake_id0/socket.c extension/fake_id0/open.c
    extension/fake_id0/unlink.c extension/fake_id0/rename.c
    extension/fake_id0/chmod.c extension/fake_id0/utimensat.c
    extension/fake_id0/access.c extension/fake_id0/exec.c
    extension/fake_id0/link.c extension/fake_id0/symlink.c
    extension/fake_id0/mk.c extension/fake_id0/stat.c
    extension/fake_id0/helper_functions.c extension/fake_id0/fake_id0.c
    extension/hidden_files/hidden_files.c extension/mountinfo/mountinfo.c
    extension/port_switch/port_switch.c extension/sysvipc/sysvipc.c
    extension/sysvipc/sysvipc_msg.c extension/sysvipc/sysvipc_sem.c
    extension/sysvipc/sysvipc_shm.c extension/link2symlink/link2symlink.c
    extension/fix_symlink_size/fix_symlink_size.c
)
list(TRANSFORM PROOT_SOURCES PREPEND "${PROOT_ROOT}/")

add_executable(proot ${PROOT_SOURCES} "${CMAKE_CURRENT_BINARY_DIR}/loader-info.c")
target_include_directories(proot PRIVATE
    "${PROOT_ROOT}"
    "${CMAKE_CURRENT_BINARY_DIR}"
    "${TALLOC_ROOT}"
)

set(PROOT_DEFINITIONS
    _FILE_OFFSET_BITS=64
    _GNU_SOURCE
    ARG_MAX=131072
    PROOT_UNBUNDLE_LOADER="."
)
if(HAS_POKEDATA_WORKAROUND)
    list(APPEND PROOT_DEFINITIONS HAS_POKEDATA_WORKAROUND=1)
endif()

if(ANDROID)
    list(APPEND PROOT_DEFINITIONS WITH_LIBANDROID_SHMEM)
    target_include_directories(proot PRIVATE "${SHM_ROOT}")
    target_compile_options(proot PRIVATE
        "SHELL:-include string.h"
        "SHELL:-include ${SHM_ROOT}/shm.h"
    )
    target_link_libraries(proot PRIVATE talloc android-shmem log android)
else()
    target_link_libraries(proot PRIVATE talloc)
endif()

target_compile_definitions(proot PRIVATE ${PROOT_DEFINITIONS})
target_compile_options(proot PRIVATE
    -Wall -Wextra -O2
    -Wno-implicit-function-declaration
    -Wno-error=implicit-function-declaration
    -Wno-incompatible-function-pointer-types
)
target_link_options(proot PRIVATE "-Wl,-z,noexecstack")
set_target_properties(proot PROPERTIES OUTPUT_NAME "proot-bin" SUFFIX "")

# 8. AGP Carriers: package executables as lib*.so for APK bundling
add_library(proot-carrier SHARED "${CMAKE_CURRENT_SOURCE_DIR}/cmake/executable_carrier.c")
set_target_properties(proot-carrier PROPERTIES OUTPUT_NAME proot)
add_dependencies(proot-carrier proot)
add_custom_command(TARGET proot-carrier POST_BUILD
    COMMAND "${CMAKE_COMMAND}" -E copy "$<TARGET_FILE:proot>" "$<TARGET_FILE:proot-carrier>"
)

add_library(prootloader-carrier SHARED "${CMAKE_CURRENT_SOURCE_DIR}/cmake/executable_carrier.c")
set_target_properties(prootloader-carrier PROPERTIES OUTPUT_NAME prootloader)
add_dependencies(prootloader-carrier prootloader)
add_custom_command(TARGET prootloader-carrier POST_BUILD
    COMMAND "${CMAKE_COMMAND}" -E copy "$<TARGET_FILE:prootloader>" "$<TARGET_FILE:prootloader-carrier>"
)

add_library(proot_loader-carrier SHARED "${CMAKE_CURRENT_SOURCE_DIR}/cmake/executable_carrier.c")
set_target_properties(proot_loader-carrier PROPERTIES OUTPUT_NAME proot_loader)
add_dependencies(proot_loader-carrier prootloader)
add_custom_command(TARGET proot_loader-carrier POST_BUILD
    COMMAND "${CMAKE_COMMAND}" -E copy "$<TARGET_FILE:prootloader>" "$<TARGET_FILE:proot_loader-carrier>"
)

# 9. 16KB ELF page size alignment for Android 15 / modern devices
if(ANDROID)
    set(ALL_TARGETS
        linuxdroidspawn
        pocketspawn
        proot
        prootloader
        proot-carrier
        prootloader-carrier
        proot_loader-carrier
    )
    foreach(target ${ALL_TARGETS})
        target_link_options(${target} PRIVATE "-Wl,-z,max-page-size=16384")
    endforeach()
endif()
