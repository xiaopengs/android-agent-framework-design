// TEST HARBOUR SHIM — not production code.
// Minimal android.content surface referenced by ContextCalibrationStore.kt:
//   context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
//   SharedPreferences.getString(key, null) / .edit().putString(k, v).apply()
// The unit test mocks SharedPreferences with Mockito; this shim only needs to
// provide the type signatures so the verbatim source compiles on a plain JVM.
package android.content

interface SharedPreferences {
    fun getString(key: String, defValue: String?): String?
    fun edit(): Editor

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun apply()
    }
}

abstract class Context {
    abstract val applicationContext: Context
    abstract fun getSharedPreferences(name: String, mode: Int): SharedPreferences

    companion object {
        const val MODE_PRIVATE = 0
    }
}