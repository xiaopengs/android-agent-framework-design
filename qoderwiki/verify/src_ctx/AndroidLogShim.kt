// TEST HARBOUR SHIM — not production code.
// android.util.Log entry points used by MemoryRepository.kt (i/e only),
// printed to stdout. Changes no repository logic.
package android.util

object Log {
    @JvmStatic fun i(tag: String, msg: String): Int { println("[I][$tag] $msg"); return 0 }
    @JvmStatic fun e(tag: String, msg: String, tr: Throwable?): Int { println("[E][$tag] $msg"); return 0 }
    @JvmStatic fun e(tag: String, msg: String): Int { println("[E][$tag] $msg"); return 0 }
}