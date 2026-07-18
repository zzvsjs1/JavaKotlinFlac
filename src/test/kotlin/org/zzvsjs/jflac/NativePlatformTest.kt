package org.zzvsjs.jflac

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativePlatformTest {
    @Test
    fun windowsX64UsesBundledResourceDirectory() {
        val platform = NativePlatform.detect("Windows 11", "amd64")

        assertEquals("windows-x86_64", platform.id)
        assertEquals("META-INF/native/windows-x86_64", platform.resourceRoot)
        assertEquals(listOf("FLAC.dll", "jflac-jni.dll"), platform.libraries.map { it.fileName })
    }

    @Test
    fun linuxX64UsesElfLibraryNames() {
        val platform = NativePlatform.detect("Linux", "x64")

        assertEquals("linux-x86_64", platform.id)
        assertEquals("META-INF/native/linux-x86_64", platform.resourceRoot)
        assertEquals(listOf("libFLAC.so.14", "libjflac-jni.so"), platform.libraries.map { it.fileName })
    }

    @Test
    fun macOsSupportsIntelAndAppleSiliconResourceDirectories() {
        val intel = NativePlatform.detect("Mac OS X", "x86_64")
        val appleSilicon = NativePlatform.detect("Darwin", "arm64")

        assertEquals("macos-x86_64", intel.id)
        assertEquals("META-INF/native/macos-x86_64", intel.resourceRoot)
        assertEquals(listOf("libFLAC.14.dylib", "libjflac-jni.dylib"), intel.libraries.map { it.fileName })
        assertEquals("macos-aarch64", appleSilicon.id)
        assertEquals("META-INF/native/macos-aarch64", appleSilicon.resourceRoot)
        assertEquals(listOf("libFLAC.14.dylib", "libjflac-jni.dylib"), appleSilicon.libraries.map { it.fileName })
    }

    @Test
    fun unsupportedArchitectureReportsTheCompleteDetectedPlatformId() {
        val error = assertFailsWith<UnsupportedFeatureException> {
            NativePlatform.detect("Linux", "riscv64")
        }

        assertTrue(error.message!!.contains("linux-riscv64"))
        assertTrue(error.message!!.contains("windows-x86_64"))
        assertTrue(error.message!!.contains("linux-x86_64"))
    }

    @Test
    fun unsupportedOperatingSystemPreservesUsefulDiagnosticComponents() {
        val error = assertFailsWith<UnsupportedFeatureException> {
            NativePlatform.detect("Plan 9", "amd64")
        }

        assertTrue(error.message!!.contains("plan-9-x86_64"))
    }
}
