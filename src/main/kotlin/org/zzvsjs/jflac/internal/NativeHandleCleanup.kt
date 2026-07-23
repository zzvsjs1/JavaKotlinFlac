package org.zzvsjs.jflac.internal

import java.lang.ref.Cleaner
import java.util.concurrent.atomic.AtomicLong

/**
 * Releases one opaque native handle without retaining the Java owner.
 *
 * Implementations used by [NativeHandleCleanup] must be stateless. In
 * particular, a releaser must never capture the session registered with the
 * cleaner, because that would keep the session reachable and prevent cleanup.
 */
internal fun interface NativeHandleReleaser {
    fun release(handle: Long)
}

/**
 * Holds the single transferable ownership token for one native handle.
 *
 * Explicit close/finalise paths claim the handle before calling native code.
 * The Cleaner action uses the same atomic hand-off, so exactly one path can
 * become responsible for releasing the native owner.
 */
internal class NativeHandleCleanup(
    initialHandle: Long,
    private val releaser: NativeHandleReleaser
) : Runnable {
    private val handle = AtomicLong(initialHandle)

    fun currentHandle(): Long = handle.get()

    fun claimHandle(): Long = handle.getAndSet(0L)

    /**
     * Returns a claimed handle to the Cleaner after synchronous release fails.
     */
    fun restoreHandle(claimedHandle: Long): Boolean {
        if (claimedHandle == 0L) {
            return false
        }

        return handle.compareAndSet(0L, claimedHandle)
    }

    override fun run() {
        val claimedHandle = claimHandle()
        if (claimedHandle == 0L) {
            return
        }

        try {
            releaser.release(claimedHandle)
        } catch (_: Throwable) {
            /*
             * A Cleaner action has no caller to receive an exception. Explicit
             * lifecycle paths claim the handle first and report their own
             * release failures synchronously.
             */
        }
    }
}

private val nativeHandleCleaner: Cleaner = Cleaner.create()

/**
 * Registers a non-capturing cleanup action with the process-level Cleaner.
 */
internal fun registerNativeHandleCleanup(
    owner: Any,
    cleanup: NativeHandleCleanup
): Cleaner.Cleanable = nativeHandleCleaner.register(owner, cleanup)

/**
 * Releases a claimed handle, restoring Cleaner ownership if release fails.
 */
internal fun releaseClaimedNativeHandle(
    cleanup: NativeHandleCleanup,
    handle: Long,
    releaser: NativeHandleReleaser
) {
    if (handle == 0L) {
        return
    }

    try {
        releaser.release(handle)
    } catch (releaseFailure: Throwable) {
        cleanup.restoreHandle(handle)
        throw releaseFailure
    }
}

/**
 * Adds a release failure to an existing failure and leaves a Cleaner fallback.
 *
 * The return value tells the caller whether synchronous release succeeded and
 * the Cleaner registration can therefore be removed immediately.
 */
internal fun releaseClaimedNativeHandleAfterFailure(
    cleanup: NativeHandleCleanup,
    handle: Long,
    failure: Throwable,
    releaser: NativeHandleReleaser
): Boolean {
    if (handle == 0L) {
        return true
    }

    return try {
        releaser.release(handle)
        true
    } catch (releaseFailure: Throwable) {
        cleanup.restoreHandle(handle)
        if (releaseFailure !== failure) {
            failure.addSuppressed(releaseFailure)
        }

        false
    }
}
