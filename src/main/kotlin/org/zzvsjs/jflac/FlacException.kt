package org.zzvsjs.jflac

/**
 * Base exception type for the public wrapper API.
 *
 * JNI throws into JVM code by looking up Java-visible constructors, so these
 * exceptions intentionally expose explicit `(String)` and `(String, Throwable)`
 * overloads instead of relying on Kotlin default arguments.
 */
open class FlacException : RuntimeException {
    /** Creates an exception with a human-readable message only. */
    constructor(message: String) : super(message)

    /** Creates an exception with both a message and original cause. */
    constructor(message: String, cause: Throwable?) : super(message, cause)
}

/** Raised when bundled native binaries cannot be extracted or loaded. */
class NativeLoadException : FlacException {
    constructor(message: String) : super(message)

    constructor(message: String, cause: Throwable?) : super(message, cause)
}

/** Raised when libFLAC rejects the input or decoding cannot complete. */
class FlacDecodeException : FlacException {
    constructor(message: String) : super(message)

    constructor(message: String, cause: Throwable?) : super(message, cause)
}

/** Raised when libFLAC cannot initialise, process, or finalise an encode job. */
class FlacEncodeException : FlacException {
    constructor(message: String) : super(message)

    constructor(message: String, cause: Throwable?) : super(message, cause)
}

/** Raised when libFLAC cannot commit metadata edits to an existing FLAC file. */
class FlacMetadataEditException : FlacException {
    constructor(message: String) : super(message)

    constructor(message: String, cause: Throwable?) : super(message, cause)
}

/** Raised for features intentionally outside the scope of the current wrapper. */
class UnsupportedFeatureException(message: String) : FlacException(message)
