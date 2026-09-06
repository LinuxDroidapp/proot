# LinuxDroid PRoot

LinuxDroid PRoot is a rootless `chroot`, `mount --bind`, and `binfmt_misc` userspace engine for Android and Linux. It is based on the production-proven Termux PRoot kernel/syscall emulation core, modernized with full Android 15 compliance, 16KB ELF page alignment, and hermetic Gradle & CMake build systems.

---

## Key Features & Production Capabilities

* **Modern Android Toolchains**: Built using Android NDK 30 (`30.0.16138531`), Clang 21 compiler compatibility, and Gradle 9.7.1 wrapper with Android Gradle Plugin 9.3.2.
* **16KB ELF Page Alignment**: Full compliance with Android 15 and Google Play requirements (`-Wl,-z,max-page-size=16384`) across all 64-bit binaries.
* **Android W^X Bypass (Carrier Libraries)**: Bypasses Android 10+ restrictions against executing files in app data directories (`/data/data/<pkg>/files`). Executables are packaged as JNI shared libraries (`libproot.so`, `libproot_loader.so`, `libprootloader.so`) with `useLegacyPackaging = true` so Android extracts them into `nativeLibraryDir` with execute permissions (`r-xr-xr-x`).
* **Multi-Architecture Support**: Full hermetic compilation for all four Android ABIs:
  * `arm64-v8a` (64-bit ARM)
  * `armeabi-v7a` (32-bit ARM)
  * `x86_64` (64-bit Intel/AMD)
  * `x86` (32-bit Intel/AMD)
* **Hermetic Vendored Dependencies**:
  * Samba `talloc` v2.5.0 (`talloc.c`, `talloc.h`) vendored under `third_party/talloc/`.
  * `libandroid-shmem` (`shmem.c`, `shm.h`) vendored under `third_party/libandroid-shmem/` for SysV IPC shared memory over Android ashmem/memfd.
* **Process Group Lifecycle Management**: Native JNI helper ([src/native/native_spawn.c](src/native/native_spawn.c)) providing process group isolation (`setpgid(0, 0)`), child tracing permission (`prctl(PR_SET_DUMPABLE, 1)`), non-blocking status tracking, and group signal termination (`kill(-pid, sig)`). Dual JNI bindings for `com.linuxdroid` and `com.jarves.mh`.
* **Deterministic Distribution & Manifest**: Automated task generating `dist/android/<abi>/` with SHA-256 `MANIFEST.txt` checksums consumable by LinuxDroid's `RuntimeAssetsManager`.

---

## Building

### Prerequisites

* Android NDK 30 (`30.0.16138531`)
* Android SDK (API 28+ / compileSdk 36)
* JDK 17 or JDK 21
* CMake 3.22.1+

### Build via Gradle (Recommended)

1. Ensure `ANDROID_HOME` or `ANDROID_SDK_ROOT` is set in your environment:
   ```bash
   export ANDROID_HOME=/path/to/Android/Sdk
   export ANDROID_NDK_ROOT=$ANDROID_HOME/ndk/30.0.16138531
   ```

2. Build the Android release AAR and native `.so` carrier libraries for all four ABIs:
   ```bash
   ./gradlew assembleRelease
   ```

3. Assemble the complete distribution tree with standalone binaries, carrier libraries, and SHA-256 `MANIFEST.txt`:
   ```bash
   ./gradlew assembleAndroidDist
   ```
   Outputs will be organized under `dist/android/`:
   ```text
   dist/android/
   ├── arm64-v8a/
   │   ├── proot
   │   ├── loader
   │   ├── libproot.so
   │   ├── libproot_loader.so
   │   ├── libprootloader.so
   │   ├── libtalloc.so
   │   ├── libandroid-shmem.so
   │   ├── liblinuxdroidspawn.so
   │   ├── libpocketspawn.so
   │   └── MANIFEST.txt
   ├── armeabi-v7a/
   ├── x86/
   └── x86_64/
   ```

### Build via CMake (Direct Toolchain)

To cross-compile for a specific Android ABI directly using CMake:

```bash
cmake -B build-arm64 \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-28 \
  -DCMAKE_BUILD_TYPE=Release

cmake --build build-arm64 -j$(nproc)
```

### Build on Host Linux

To build PRoot for the local host using host GCC / Clang:

```bash
# Using CMake
cmake -B build-host -DCMAKE_BUILD_TYPE=Release
cmake --build build-host -j$(nproc)

# Or using traditional GNUmakefile
make -C src proot
```

---

## Production Runtime Launch Guide for Android Apps

When executing PRoot inside an Android application (such as LinuxDroid), follow these production integration requirements:

### 1. Required Environment Variables

Modern Android environments lack standard paths like `/tmp` and require specific glibc workarounds:

```kotlin
val nativeLibDir = context.applicationInfo.nativeLibraryDir
val prootTmpDir = File(context.cacheDir, "proot-tmp").apply { mkdirs() }

val environment = mapOf(
    // Path to the freestanding static loader:
    "PROOT_LOADER" to File(nativeLibDir, "libproot_loader.so").absolutePath,
    // Android has no /tmp; provide an app-writable directory:
    "PROOT_TMP_DIR" to prootTmpDir.absolutePath,
    // Ensure talloc and android-shmem shared libraries resolve:
    "LD_LIBRARY_PATH" to nativeLibDir,
    // Disable rseq on glibc 2.35+ (Ubuntu 22.04/24.04, Debian 12) to prevent ptrace crashes:
    "GLIBC_TUNABLES" to "glibc.pthread.rseq=0",
    // Avoid kernel ptrace lockups on heavily customized vendor kernels (MIUI, EMUI):
    "PROOT_NO_SECCOMP" to "1",
    "LANG" to "C.UTF-8",
    "TERM" to "xterm-256color"
)
```

### 2. Recommended Command Line Arguments

```kotlin
val prootBinary = File(nativeLibDir, "libproot.so")

val command = listOf(
    prootBinary.absolutePath,
    "--link2symlink",      // Emulate hard links via symlinks (FAT/F2FS compatibility)
    "-0",                  // Emulate root user (fake_id0) for package managers (apt, dpkg, apk)
    "-r", rootfsPath,      // Guest rootfs directory
    "-b", "/dev",          // Bind device filesystem
    "-b", "/proc",         // Bind process filesystem
    "-b", "/sys",          // Bind system filesystem
    // Bind host dynamic linker and APEX libraries on Android 10+:
    "-b", "/system",
    "-b", "/apex",
    "-b", "/vendor",
    "-b", "/product",
    "-w", "/root",         // Working directory
    "/bin/bash"            // Target guest executable
)
```

### 3. Background Services & Phantom Process Killer (PPK)

* **Foreground Service**: Android 11+ enforces a strict process limit (32 child processes) and aggressively terminates background processes. Always launch PRoot within an Android **Foreground Service** (`startForeground()` displaying an ongoing notification with type `specialUse` or `dataSync`).
* **Concurrency Limits**: Advise users or automate guest build tools (e.g. `make -j4`, `ninja -j4`) to prevent spawning excessive concurrent sub-processes.
* **Process Termination**: Use `liblinuxdroidspawn.so` to spawn the process group (`setpgid(0, 0)`). To terminate PRoot and all guest descendant processes cleanly without leaving orphaned processes, send signals to the process group (`kill(-pid, signal)`).

---

## Upstream & Acknowledgements

* [PRoot Project](https://github.com/proot-me/PRoot/): Original ptrace/userspace chroot implementation.
* [Termux PRoot](https://github.com/termux/proot): Android port, seccomp filtering, and extension patches.
* [Mobile-Harness](https://github.com/techjarves/Mobile-Harness): CMake native integration and carrier packaging model.
* [Samba talloc](https://www.samba.org/ftp/talloc/): Hierarchical memory allocation library.
