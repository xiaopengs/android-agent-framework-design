// JVM shim for io.github.nanomuse.sync.RemoteRows.
// The real object also has a suspend overload touching android.content.Context,
// Hub and RoomSyncStore; only the pure in-memory branch is reachable from
// InterruptedTailDetector, so it is reproduced verbatim here with a test-settable
// backing set in place of ConversationSync.remoteRows.
package io.github.nanomuse.sync

import com.openminis.app.data.model.LLMMessage

object RemoteRows {
    /** Stand-in for ConversationSync.remoteRows.value (id -> device). */
    val remoteIds: MutableSet<String> = mutableSetOf()

    fun isRemote(messageId: String?): Boolean = deviceOf(messageId) != null

    fun deviceOf(messageId: String?): String? {
        val id = messageId?.substringBefore('#')?.takeIf { it.isNotBlank() } ?: return null
        return if (id in remoteIds) "other-device" else null
    }

    fun isRemote(entry: LLMMessage?): Boolean = isRemote(entry?.dbMessageId)
}