package com.openminis.app.logging

/**
 * JVM harness stub for com.openminis.app.logging.AppLogger.
 *
 * The real AppLogger (android/.../logging/AppLogger.kt) imports android.content.Context
 * and android.util.Log, so it cannot load on a plain JVM. ToolLoopDetector only calls
 * AppLogger.warning(tag, msg) and AppLogger.debug(tag, msg), so this stub reproduces
 * exactly those two entry points and prints to stdout.
 *
 * This is a TEST HARBOUR SHIM, not production code. It changes no detector logic.
 */
object AppLogger {
    fun warning(tag: String, msg: String) = println("[W][$tag] $msg")
    fun debug(tag: String, msg: String) = println("[D][$tag] $msg")
    fun info(tag: String, msg: String) = println("[I][$tag] $msg")
    fun error(tag: String, msg: String) = println("[E][$tag] $msg")
}