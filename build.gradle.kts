import java.security.MessageDigest
import java.time.Instant

plugins {
    id("com.android.library") version "9.3.2"
}

android {
    namespace = "com.linuxdroid.proot"
    compileSdk = 36
    ndkVersion = "30.0.16138531"

    defaultConfig {
        minSdk = 28
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments("-DANDROID_PLATFORM=android-28")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

fun sha256(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            md.update(buffer, 0, read)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

tasks.register("assembleAndroidDist") {
    group = "distribution"
    description = "Assembles standalone PRoot binaries, JNI libraries, and MANIFEST for all Android ABIs"
    dependsOn("assembleRelease")

    doLast {
        val distDir = file("dist/android")
        distDir.mkdirs()

        val abis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        val archMap = mapOf(
            "arm64-v8a" to "aarch64",
            "armeabi-v7a" to "arm",
            "x86" to "i386",
            "x86_64" to "x86_64"
        )

        val gitCommit = providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
        }.standardOutput.asText.get().trim()

        val sdkDir = providers.environmentVariable("ANDROID_HOME").orNull
            ?: providers.environmentVariable("ANDROID_SDK_ROOT").orNull
            ?: "/home/codespace/Android/Sdk"
        val ndkPath = providers.environmentVariable("ANDROID_NDK_ROOT").orNull
            ?: "$sdkDir/ndk/30.0.16138531"

        abis.forEach { abi ->
            val abiDist = File(distDir, abi).apply { mkdirs() }
            val intermediatesCmake = file("build/intermediates/cxx/Release")
            val cmakeTargetDir = intermediatesCmake.walkTopDown()
                .filter { it.isDirectory && it.name == abi }
                .firstOrNull() ?: return@forEach

            val prootBin = File(cmakeTargetDir, "proot-bin")
            val prootLoaderBin = File(cmakeTargetDir, "prootloader-bin")
            val libProot = File(cmakeTargetDir, "libproot.so")
            val libProotLoader = File(cmakeTargetDir, "libproot_loader.so")

            if (prootBin.exists()) prootBin.copyTo(File(abiDist, "proot"), overwrite = true)
            if (prootLoaderBin.exists()) prootLoaderBin.copyTo(File(abiDist, "loader"), overwrite = true)
            if (libProot.exists()) libProot.copyTo(File(abiDist, "libproot.so"), overwrite = true)
            if (libProotLoader.exists()) libProotLoader.copyTo(File(abiDist, "libproot_loader.so"), overwrite = true)

            val prootHash = if (File(abiDist, "proot").exists()) sha256(File(abiDist, "proot")) else "unknown"
            val loaderHash = if (File(abiDist, "loader").exists()) sha256(File(abiDist, "loader")) else "unknown"

            val manifest = File(abiDist, "MANIFEST.txt")
            manifest.writeText(
                """
                LinuxDroid-PRoot v5.1.107.92
                commit:  $gitCommit
                ABI:     $abi
                arch:    ${archMap[abi]}
                cc:      $ndkPath/toolchains/llvm/prebuilt/linux-x86_64/bin/clang
                ndk:     $ndkPath
                android: 16+ (API 36)
                sha256:
                  proot:    $prootHash
                  loader:   $loaderHash
                built:   ${Instant.now()}
                """.trimIndent() + "\n"
            )
        }
        println("Android distribution assembled at ${distDir.absolutePath}")
    }
}
