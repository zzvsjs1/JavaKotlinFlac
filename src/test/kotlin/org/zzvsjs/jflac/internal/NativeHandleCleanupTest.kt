package org.zzvsjs.jflac.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NativeHandleCleanupTest {
    @Test
    fun claimTransfersHandleOwnershipOnlyOnce() {
        val cleanup = NativeHandleCleanup(41L) { error("Unexpected automatic release.") }

        assertEquals(41L, cleanup.claimHandle())
        assertEquals(0L, cleanup.claimHandle())
        assertEquals(0L, cleanup.currentHandle())
    }

    @Test
    fun automaticCleanupReleasesHandleOnlyOnce() {
        val releasedHandles = mutableListOf<Long>()
        val cleanup = NativeHandleCleanup(42L) { handle -> releasedHandles += handle }

        cleanup.run()
        cleanup.run()

        assertEquals(listOf(42L), releasedHandles)
        assertEquals(0L, cleanup.currentHandle())
    }

    @Test
    fun explicitCleanupPreventsLaterAutomaticRelease() {
        val releasedHandles = mutableListOf<Long>()
        val releaser = NativeHandleReleaser { handle -> releasedHandles += handle }
        val cleanup = NativeHandleCleanup(43L, releaser)

        val claimedHandle = cleanup.claimHandle()
        releaser.release(claimedHandle)
        cleanup.run()

        assertEquals(listOf(43L), releasedHandles)
    }

    @Test
    fun automaticCleanupSwallowsReleaseFailure() {
        val cleanup = NativeHandleCleanup(44L) {
            throw IllegalStateException("automatic release failed")
        }

        cleanup.run()

        assertEquals(0L, cleanup.currentHandle())
    }

    @Test
    fun explicitReleaseFailureRestoresCleanerOwnership() {
        var releaseAttempts = 0
        val releaser = NativeHandleReleaser { handle ->
            assertEquals(45L, handle)
            releaseAttempts += 1
            if (releaseAttempts == 1) {
                throw IllegalStateException("synchronous release failed")
            }
        }
        val cleanup = NativeHandleCleanup(45L, releaser)
        val claimedHandle = cleanup.claimHandle()

        assertFailsWith<IllegalStateException> {
            releaseClaimedNativeHandle(cleanup, claimedHandle, releaser)
        }
        assertEquals(45L, cleanup.currentHandle())

        cleanup.run()

        assertEquals(2, releaseAttempts)
        assertEquals(0L, cleanup.currentHandle())
    }

    @Test
    fun releaseFailureIsSuppressedAndRetainsCleanerFallback() {
        val primaryFailure = IllegalArgumentException("wrapper construction failed")
        val releaseFailure = IllegalStateException("native release failed")
        val cleanup = NativeHandleCleanup(46L) { throw releaseFailure }
        val claimedHandle = cleanup.claimHandle()

        val released = releaseClaimedNativeHandleAfterFailure(
            cleanup,
            claimedHandle,
            primaryFailure
        ) {
            throw releaseFailure
        }

        assertTrue(!released)
        assertEquals(46L, cleanup.currentHandle())
        assertEquals(1, primaryFailure.suppressed.size)
        assertSame(releaseFailure, primaryFailure.suppressed.single())
    }
}
