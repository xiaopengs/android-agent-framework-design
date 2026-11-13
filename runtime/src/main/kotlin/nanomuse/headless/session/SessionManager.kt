package nanomuse.headless.session

import nanomuse.headless.core.EventSink
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Layer3 —— SessionManager：会话注册表、状态机持久化、断点续跑。
 *
 * 对齐 docs/DESIGN.md 第 5 章状态图与 9.1 持久化清单。状态落盘为
 * sessions/<sessionId>.state.json，崩溃后可从 RUNNING/WAIT_CLOUD/TOOL_EXEC
 * 恢复，配合 JsonlSessionStore 的历史消息继续跑（断点续跑）。
 */
enum class SessionState { IDLE, RUNNING, WAIT_CLOUD, TOOL_EXEC, FINISHED, ERROR }

data class SessionSnapshot(
    val sessionId: String,
    val traceId: String,
    val state: SessionState,
    val turn: Int = 0,
    val mode: String = "LOCAL",
    val stoppedBy: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val checkpoint: JSONObject = JSONObject(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("session_id", sessionId)
        .put("trace_id", traceId)
        .put("state", state.name)
        .put("turn", turn)
        .put("mode", mode)
        .put("stopped_by", stoppedBy ?: JSONObject.NULL)
        .put("updated_at", updatedAt)
        .put("checkpoint", JSONObject(checkpoint.toString()))

    companion object {
        fun fromJson(o: JSONObject): SessionSnapshot = SessionSnapshot(
            sessionId = o.optString("session_id"),
            traceId = o.optString("trace_id"),
            state = runCatching { SessionState.valueOf(o.optString("state")) }.getOrDefault(SessionState.IDLE),
            turn = o.optInt("turn", 0),
            mode = o.optString("mode", "LOCAL").ifBlank { "LOCAL" },
            stoppedBy = o.optString("stopped_by").ifBlank { null },
            updatedAt = o.optLong("updated_at", System.currentTimeMillis()),
            checkpoint = o.optJSONObject("checkpoint") ?: JSONObject(),
        )
    }
}

/** 状态机合法转移表（DESIGN.md 5）。 */
object SessionTransitions {
    private val allowed: Map<SessionState, Set<SessionState>> = mapOf(
        SessionState.IDLE to setOf(SessionState.RUNNING, SessionState.ERROR),
        SessionState.RUNNING to setOf(
            SessionState.WAIT_CLOUD, SessionState.TOOL_EXEC,
            SessionState.FINISHED, SessionState.ERROR,
        ),
        SessionState.WAIT_CLOUD to setOf(SessionState.RUNNING, SessionState.FINISHED, SessionState.ERROR),
        SessionState.TOOL_EXEC to setOf(SessionState.RUNNING, SessionState.FINISHED, SessionState.ERROR),
        SessionState.FINISHED to setOf(SessionState.IDLE),
        SessionState.ERROR to setOf(SessionState.IDLE),
    )

    fun can(from: SessionState, to: SessionState): Boolean = to in (allowed[from] ?: emptySet())

    fun legalTargets(from: SessionState): Set<SessionState> = allowed[from] ?: emptySet()
}

/** 状态文件读写；同时追加一条状态变更审计日志（可观测）。 */
class SessionStateStore(private val dir: Path) {
    init { Files.createDirectories(dir) }

    private fun file(sessionId: String): Path =
        dir.resolve(sessionId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".state.json")

    fun save(s: SessionSnapshot) {
        Files.writeString(file(s.sessionId), s.toJson().toString(2))
        Files.writeString(
            dir.resolve("state-transitions.jsonl"),
            s.toJson().toString() + "\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND,
        )
    }

    fun load(sessionId: String): SessionSnapshot? {
        val f = file(sessionId)
        if (!Files.exists(f)) return null
        return runCatching { SessionSnapshot.fromJson(JSONObject(Files.readString(f))) }.getOrNull()
    }

    fun list(): List<SessionSnapshot> =
        (dir.toFile().listFiles { _, name -> name.endsWith(".state.json") } ?: emptyArray())
            .mapNotNull { f ->
                runCatching { SessionSnapshot.fromJson(JSONObject(Files.readString(f.toPath()))) }.getOrNull()
            }
}

class SessionManager(
    stateDir: Path,
    private val events: EventSink? = null,
) {
    private val store = SessionStateStore(stateDir)

    /** 新建或重置会话；已存在且处于中间态时保留现场供 resume。 */
    fun open(sessionId: String, traceId: String, mode: String, reset: Boolean = false): SessionSnapshot {
        val old = store.load(sessionId)
        if (!reset && old != null && old.state != SessionState.FINISHED && old.state != SessionState.ERROR) {
            return old
        }
        val s = SessionSnapshot(sessionId, traceId, SessionState.IDLE, mode = mode)
        store.save(s)
        emit("session_open", s)
        return s
    }

    fun get(sessionId: String): SessionSnapshot? = store.load(sessionId)

    fun transition(
        sessionId: String,
        to: SessionState,
        turn: Int? = null,
        checkpoint: JSONObject? = null,
    ): SessionSnapshot {
        val cur = store.load(sessionId) ?: SessionSnapshot(sessionId, "", SessionState.IDLE)
        require(SessionTransitions.can(cur.state, to)) {
            "illegal session transition ${cur.state} -> $to (legal: ${SessionTransitions.legalTargets(cur.state)})"
        }
        val next = cur.copy(
            state = to,
            turn = turn ?: cur.turn,
            checkpoint = checkpoint ?: cur.checkpoint,
            updatedAt = System.currentTimeMillis(),
        )
        store.save(next)
        emit("session_state", next)
        return next
    }

    /**
     * 循环内每轮写现场：直接落快照，不走转移合法性校验
     * （RUNNING → TOOL_EXEC → RUNNING 是高频往返，出错不应打断任务）。
     */
    fun checkpoint(sessionId: String, state: SessionState, turn: Int): SessionSnapshot {
        val cur = store.load(sessionId) ?: SessionSnapshot(sessionId, "", SessionState.IDLE)
        val next = cur.copy(state = state, turn = turn, updatedAt = System.currentTimeMillis())
        store.save(next)
        emit("session_checkpoint", next)
        return next
    }

    fun finish(sessionId: String, turn: Int, stoppedBy: String): SessionSnapshot {
        val cur = store.load(sessionId) ?: SessionSnapshot(sessionId, "", SessionState.RUNNING)
        val from = if (cur.state == SessionState.IDLE) SessionState.RUNNING else cur.state
        // 从任意中间态到 FINISHED 都合法；若已是终态则直接返回
        if (cur.state == SessionState.FINISHED || cur.state == SessionState.ERROR) return cur
        require(SessionTransitions.can(from, SessionState.FINISHED)) { "cannot finish from $from" }
        val next = cur.copy(
            state = SessionState.FINISHED, turn = turn, stoppedBy = stoppedBy,
            updatedAt = System.currentTimeMillis(),
        )
        store.save(next)
        emit("session_finish", next)
        return next
    }

    /** 断点续跑：存在未完成且已跑过至少 1 轮的会话。 */
    fun resumable(sessionId: String): Boolean {
        val s = store.load(sessionId) ?: return false
        return s.state in setOf(
            SessionState.RUNNING, SessionState.WAIT_CLOUD, SessionState.TOOL_EXEC,
        ) && s.turn > 0
    }

    fun resume(sessionId: String): SessionSnapshot? =
        if (resumable(sessionId)) store.load(sessionId) else null

    fun list(): List<SessionSnapshot> = store.list()

    private fun emit(type: String, s: SessionSnapshot) {
        events?.emit(
            type,
            JSONObject().put("session", s.sessionId).put("state", s.state.name)
                .put("turn", s.turn).put("trace_id", s.traceId).put("mode", s.mode),
        )
    }
}
