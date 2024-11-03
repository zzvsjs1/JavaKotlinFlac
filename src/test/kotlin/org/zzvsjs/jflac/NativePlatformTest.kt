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
    fun unsupportedPlatformReportsDetectedPlatformId() {
        val error = assertFailsWith<UnsupportedFeatureException> {
            NativePlatform.detect("Linux", "x86_64")
        }

        assertTrue(error.message!!.contains("linux-x86_64"))
        assertTrue(error.message!!.contains("windows-x86_64"))
    }
}
