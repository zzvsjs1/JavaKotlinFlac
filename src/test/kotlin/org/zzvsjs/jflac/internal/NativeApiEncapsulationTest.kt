package org.zzvsjs.jflac.internal

import java.io.ByteArrayOutputStream
import java.lang.reflect.Modifier
import java.nio.file.Files
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Guards the source-level boundary between public wrappers and JNI transport. */
class NativeApiEncapsulationTest {
    @Test
    fun jniDeclarationsAndTransportDtosAreNotPublicClasses() {
        val internalClasses = listOf(
            NativeBindings::class.java,
            NativeEncodingRequest::class.java,
            NativeMetadataEditRequest::class.java,
            NativeMetadataPayload::class.java,
            NativePullDecoderOpenResult::class.java,
            NativeVorbisCommentBlock::class.java
        )

        internalClasses.forEach { type ->
            assertTrue(!Modifier.isPublic(type.modifiers), "${type.name} must remain package-private.")
        }
    }

    @Test
    fun kotlinNativeFacadeHasNoJavaCallablePublicMethods() {
        val javaCallableMethods = NativeAccess::class.java.declaredMethods.filter { method ->
            Modifier.isPublic(method.modifiers) && !method.isSynthetic
        }

        assertEquals(emptyList(), javaCallableMethods)
    }

    @Test
    fun externalJavaSourceCannotImportNativeBindings() {
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) {
            "The test JVM must be a JDK so Java API visibility can be checked."
        }

        val directory = Files.createTempDirectory("jflac-internal-api-probe")
        val outputDirectory = Files.createDirectory(directory.resolve("classes"))
        val source = directory.resolve("NativeAccessProbe.java")
        Files.writeString(
            source,
            """
                package consumer;
                import org.zzvsjs.jflac.internal.NativeBindings;
                final class NativeAccessProbe {
                    void call() {
                        NativeBindings.verifyRuntime();
                    }
                }
            """.trimIndent()
        )

        try {
            val diagnostics = ByteArrayOutputStream()
            val exitCode = compiler.run(
                null,
                null,
                diagnostics,
                "-proc:none",
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                outputDirectory.toString(),
                source.toString()
            )

            assertTrue(exitCode != 0, "External Java compilation unexpectedly accessed NativeBindings.")
            assertTrue(
                diagnostics.toString().contains("NativeBindings"),
                "The compiler failure should identify the inaccessible JNI declaration."
            )
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun externalJavaSourceCannotCallKotlinNativeFacade() {
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) {
            "The test JVM must be a JDK so Java API visibility can be checked."
        }

        val directory = Files.createTempDirectory("jflac-native-facade-probe")
        val outputDirectory = Files.createDirectory(directory.resolve("classes"))
        val source = directory.resolve("NativeFacadeProbe.java")
        Files.writeString(
            source,
            """
                package consumer;
                import org.zzvsjs.jflac.internal.NativeAccess;
                final class NativeFacadeProbe {
                    void call() {
                        NativeAccess.INSTANCE.verifyRuntime();
                    }
                }
            """.trimIndent()
        )

        try {
            val diagnostics = ByteArrayOutputStream()
            val exitCode = compiler.run(
                null,
                null,
                diagnostics,
                "-proc:none",
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                outputDirectory.toString(),
                source.toString()
            )

            assertTrue(exitCode != 0, "External Java compilation unexpectedly called NativeAccess.")
            assertTrue(
                diagnostics.toString().contains("verifyRuntime"),
                "The compiler failure should identify the JVM-synthetic native façade method."
            )
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
