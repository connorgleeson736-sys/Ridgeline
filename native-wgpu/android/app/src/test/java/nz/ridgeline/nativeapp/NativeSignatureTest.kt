package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Every Kotlin `external fun` in NativeBridge returns the JNI type its Rust
 * export returns. A mismatch compiles and passes host tests but reads garbage
 * on the phone (0.1.27-0.1.29: labelAtlasRows returned u8, Kotlin read Int,
 * so new names were never applied). */
class NativeSignatureTest {
    @Test fun kotlinAndRustReturnTypesMatch(){
        val kotlin=File("src/main/java/nz/ridgeline/nativeapp/NativeBridge.kt").readText()
        val rust=File("../../renderer/src/android.rs").readText()
        val jni=mapOf("Boolean" to "u8","Int" to "i32","Long" to "i64","Float" to "f32","Double" to "f64","Unit" to "")
        val declared=Regex("""external fun (\w+)\([^)]*\)(?::\s*([\w?]+))?""").findAll(kotlin).associate{it.groupValues[1] to (it.groupValues[2].ifEmpty{"Unit"})}
        val exported=Regex("""fn Java_nz_ridgeline_nativeapp_NativeBridge_(\w+)\((?:[^()]|\([^()]*\))*\)\s*(?:->\s*([\w:]+))?\s*\{""").findAll(rust).associate{it.groupValues[1] to it.groupValues[2]}
        assertTrue(declared.size>40)
        val wrong=declared.mapNotNull{(name,type)->
            val rustType=exported[name]?:return@mapNotNull "$name: no Rust export"
            val normal=mapOf("jni::sys::jboolean" to "u8","jni::sys::jint" to "i32","jni::sys::jlong" to "i64","jni::sys::jfloat" to "f32","jni::sys::jdouble" to "f64")[rustType]?:rustType
            val expected=jni[type.removeSuffix("?")]?:return@mapNotNull "$name: unexpected Kotlin type $type"
            if(normal!=expected)"$name: Kotlin $type, Rust ${rustType.ifEmpty{"()"}}" else null
        }
        assertTrue(wrong.joinToString("\n"),wrong.isEmpty())
    }
}
