package nanomuse.headless.bus

import nanomuse.headless.core.EventSink
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Layer2 —— CloudAgentBus 端云双向可靠通信总线。
 *
 * 对齐 docs/DESIGN.md 6.2 信封结构与第 8 章协同闭环：
 *  - 信封编解码（envelopeId / traceId / sessionId / direction / msgType / routerTarget / seq / isStream / payload / meta）
 *  - 发送侧：幂等去重、seq 递增、ACK 等待、指数退避重试、死信落盘
 *  - 接收侧：deliver 入站、ACK 唤醒、per-session 本地队列 subscribe/poll
 *  - 全部投递动作落事件流，traceId 贯穿
 *
 * 取舍：方案接口写 `suspend send` / `Flow subscribe`。本骨架不引入协程依赖，
 * 用阻塞 ACK + BlockingQueue 订阅实现同语义；Android 端可直接替换为 Kotlin Flow。
 */
enum class EnvelopeDirection { TO_LOCAL, TO_CLOUD }
enum class MsgType { QUERY, STREAM_CHUNK, TOOL_CALL, TOOL_RESULT, STATUS, ERROR, ACK }
enum class RouterTarget { LOCAL, CLOUD, HYBRID }

data class AgentEnvelope(
    val envelopeId: String = "env-" + UUID.randomUUID().toString().replace("-", "").take(16),
    val traceId: String,
    val sessionId: String,
    val direction: EnvelopeDirection,
    val msgType: MsgType,
    val routerTarget: RouterTarget,
    val seq: Long = 0,
    val isStream: Boolean = false,
    val payload: JSONObject = JSONObject(),
    val meta: JSONObject = JSONObject(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("envelopeId", envelopeId)
        .put("traceId", traceId)
        .put("sessionId", sessionId)
        .put("direction", direction.name)
        .put("msgType", msgType.name)
        .put("routerTarget", routerTarget.name)
        .put("seq", seq)
        .put("isStream", isStream)
        .put("payload", JSONObject(payload.toString()))
        .put("meta", JSONObject(meta.toString()))

    companion object {
        fun fromJson(o: JSONObject): AgentEnvelope = AgentEnvelope(
            envelopeId = o.optString("envelopeId").ifBlank { "env-" + UUID.randomUUID().toString().take(12) },
            traceId = o.optString("traceId"),
            sessionId = o.optString("sessionId"),
            direction = enumOr(o.optString("direction"), EnvelopeDirection.TO_LOCAL),
            msgType = enumOr(o.optString("msgType"), MsgType.STATUS),
            routerTarget = enumOr(o.optString("routerTarget"), RouterTarget.LOCAL),
            seq = o.optLong("seq", 0),
            isStream = o.optBoolean("isStream", false),
            payload = o.optJSONObject("payload") ?: JSONObject(),
            meta = o.optJSONObject("meta") ?: JSONObject(),
        )

        private inline fun <reified T : Enum<T>> enumOr(s: String, default: T): T =
            enumValues<T>().firstOrNull { it.name.equals(s, ignoreCase = true) } ?: default
    }
}

/** 传输层可插拔：Android 端可换 WebSocket / gRPC / HTTP 长轮询。 */
interface Transport {
    /** 发送一个信封；返回是否送达链路（对端处理结果通过回调另回）。 */
    fun send(env: AgentEnvelope): Boolean

    /** 轮询对端下发的信封（可选，pull 型传输用）。 */
    fun poll(sessionId: String, timeoutMs: Long): AgentEnvelope? = null

    fun close() {}
}

data class SendResult(
    val ok: Boolean,
    val envelopeId: String,
    val seq: Long,
    val attempts: Int,
    val deadLettered: Boolean = false,
    val duplicate: Boolean = false,
    val error: String? = null,
)

data class DeadLetter(val envelope: AgentEnvelope, val reason: String, val at: Long)

class CloudAgentBus(
    private val transport: Transport,
    private val events: EventSink? = null,
    private val logDir: Path? = null,
    private val maxRetries: Int = 3,
    private val ackTimeoutMs: Long = 3_000,
    private val retryBackoffMs: Long = 25,
    private val requireAck: Boolean = true,
) {
    private val seqs = ConcurrentHashMap<String, AtomicLong>()
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private val ackWaiters = ConcurrentHashMap<String, CountDownLatch>()
    private val inbox = ConcurrentHashMap<String, LinkedBlockingQueue<AgentEnvelope>>()
    private val dead = mutableListOf<DeadLetter>()

    init {
        logDir?.let { Files.createDirectories(it) }
    }

    private fun queue(sessionId: String) =
        inbox.computeIfAbsent(sessionId) { LinkedBlockingQueue() }

    /** 出站：幂等 → 打 seq/timestamp → 重试 → ACK → 失败进死信。 */
    fun send(env: AgentEnvelope): SendResult {
        if (!seen.add(env.envelopeId)) {
            return SendResult(true, env.envelopeId, env.seq, 0, duplicate = true, error = "duplicate suppressed")
        }
        val seq = seqs.computeIfAbsent(env.sessionId) { AtomicLong(0) }.incrementAndGet()
        val meta = JSONObject(env.meta.toString())
            .put("timestamp", System.currentTimeMillis())
            .put("timeoutMs", env.meta.optLong("timeoutMs", 30_000))
        val stamped = env.copy(seq = seq, meta = meta)
        record(stamped)

        var attempts = 0
        var lastError = "no ack"
        while (attempts <= maxRetries) {
            attempts++
            val latch = CountDownLatch(1)
            if (requireAck) ackWaiters[stamped.envelopeId] = latch
            events?.emit(
                "bus_send",
                JSONObject().put("envelope_id", stamped.envelopeId)
                    .put("seq", seq).put("msg_type", stamped.msgType.name)
                    .put("direction", stamped.direction.name)
                    .put("attempt", attempts).put("trace_id", stamped.traceId)
                    .put("session", stamped.sessionId),
            )
            val delivered = runCatching { transport.send(stamped) }
                .getOrElse { lastError = "${it.javaClass.simpleName}: ${it.message}"; false }
            val acked = if (!delivered) false
            else if (!requireAck) true
            else latch.await(ackTimeoutMs, TimeUnit.MILLISECONDS)
            ackWaiters.remove(stamped.envelopeId)

            if (acked) {
                events?.emit(
                    "bus_ack",
                    JSONObject().put("envelope_id", stamped.envelopeId).put("seq", seq)
                        .put("session", stamped.sessionId).put("trace_id", stamped.traceId),
                )
                return SendResult(true, stamped.envelopeId, seq, attempts)
            }
            if (attempts <= maxRetries) {
                events?.emit(
                    "bus_retry",
                    JSONObject().put("envelope_id", stamped.envelopeId).put("attempt", attempts)
                        .put("reason", lastError).put("session", stamped.sessionId),
                )
                Thread.sleep(retryBackoffMs * attempts)
            }
        }

        val dl = DeadLetter(stamped, lastError, System.currentTimeMillis())
        synchronized(dead) { dead += dl }
        writeDeadLetter(dl)
        events?.emit(
            "bus_dead_letter",
            JSONObject().put("envelope_id", stamped.envelopeId).put("reason", lastError)
                .put("msg_type", stamped.msgType.name).put("session", stamped.sessionId),
        )
        return SendResult(false, stamped.envelopeId, seq, attempts, deadLettered = true, error = lastError)
    }

    /** 对端 ACK 回执（真实 transport 在网络线程里调用）。 */
    fun onAck(envelopeId: String) {
        ackWaiters[envelopeId]?.countDown()
    }

    /**
     * 入站：transport 收到对端信封后调用。
     * ACK 类消息只唤醒等待者，其余按 session 投递本地队列。
     */
    fun deliver(env: AgentEnvelope): Boolean {
        if (!seen.add(env.envelopeId)) return false
        record(env)
        if (env.msgType == MsgType.ACK) {
            val target = env.payload.optString("ack_for", env.envelopeId)
            onAck(target)
            return true
        }
        queue(env.sessionId).put(env)
        events?.emit(
            "bus_inbound",
            JSONObject().put("envelope_id", env.envelopeId).put("msg_type", env.msgType.name)
                .put("seq", env.seq).put("session", env.sessionId).put("trace_id", env.traceId),
        )
        return true
    }

    /** 本地任务直接入队（LOCAL 链路不经过 transport）。 */
    fun enqueueLocal(env: AgentEnvelope): Boolean = deliver(env)

    fun poll(sessionId: String, timeoutMs: Long = 500): AgentEnvelope? =
        queue(sessionId).poll(timeoutMs, TimeUnit.MILLISECONDS)

    fun pending(sessionId: String): Int = queue(sessionId).size

    /** 订阅：注册回调后由调用方驱动 poll；返回取消句柄。 */
    fun subscribe(sessionId: String, onEnvelope: (AgentEnvelope) -> Unit): () -> Unit {
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        val t = Thread {
            while (!stopped.get()) {
                val env = poll(sessionId, 200) ?: continue
                onEnvelope(env)
            }
        }
        t.isDaemon = true
        t.start()
        return { stopped.set(true) }
    }

    fun deadLetters(): List<DeadLetter> = synchronized(dead) { dead.toList() }

    private fun record(env: AgentEnvelope) {
        val dir = logDir ?: return
        val f = dir.resolve(env.sessionId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".jsonl")
        Files.writeString(
            f,
            JSONObject().put("flow", if (env.direction == EnvelopeDirection.TO_CLOUD) "out" else "in")
                .put("at", System.currentTimeMillis()).put("envelope", env.toJson()).toString() + "\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND,
        )
    }

    private fun writeDeadLetter(dl: DeadLetter) {
        val dir = logDir ?: return
        val f = dir.resolve("dead-letter.jsonl")
        Files.writeString(
            f,
            JSONObject().put("at", dl.at).put("reason", dl.reason)
                .put("envelope", dl.envelope.toJson()).toString() + "\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND,
        )
    }

    fun close() = transport.close()
}

/**
 * 用于 HYBRID / CLOUD 冒烟的模拟云端：不连外网，按脚本下发 TOOL_CALL。
 *
 * 闭环：QUERY → TOOL_CALL(file_write) → TOOL_CALL(shell_execute) → STATUS(FINISHED)
 * 每个 TO_CLOUD 信封立即回 ACK，TOOL_RESULT 到达后推进下一步。
 */
class SimulatedCloudTransport(
    private val script: List<Pair<String, JSONObject>> = defaultScript(),
) : Transport {
    /** 由装配层注入：把信封回投到 bus 的入站队列。 */
    var onDeliver: ((AgentEnvelope) -> Unit)? = null
    var onAck: ((String) -> Unit)? = null

    private val step = ConcurrentHashMap<String, Int>()

    override fun send(env: AgentEnvelope): Boolean {
        onAck?.invoke(env.envelopeId)
        if (env.direction != EnvelopeDirection.TO_CLOUD) return true
        when (env.msgType) {
            MsgType.QUERY -> {
                step[env.sessionId] = 0
                if (env.routerTarget == RouterTarget.HYBRID) pushNext(env, 0) else {
                    // CLOUD：纯流式回答，本地内核零启动
                    deliver(
                        env, MsgType.STREAM_CHUNK,
                        JSONObject().put("delta", "（云端全权模式回答：本地内核未启动）"), isStream = true,
                    )
                    deliver(env, MsgType.STATUS, JSONObject().put("status", "FINISHED").put("text", "cloud done."))
                }
            }
            MsgType.TOOL_RESULT -> {
                val next = (step[env.sessionId] ?: 0) + 1
                step[env.sessionId] = next
                if (next < script.size) pushNext(env, next)
                else deliver(
                    env, MsgType.STATUS,
                    JSONObject().put("status", "FINISHED").put("text", "HYBRID 协同闭环完成。"),
                )
            }
            else -> Unit
        }
        return true
    }

    private fun pushNext(origin: AgentEnvelope, index: Int) {
        val (name, args) = script[index]
        deliver(
            origin, MsgType.TOOL_CALL,
            JSONObject()
                .put("tool_name", name)
                .put("tool_call_id", "cloud_call_$index")
                .put("parameters", args)
                .put("from_cloud", true),
        )
    }

    private fun deliver(origin: AgentEnvelope, type: MsgType, payload: JSONObject, isStream: Boolean = false) {
        onDeliver?.invoke(
            AgentEnvelope(
                traceId = origin.traceId,
                sessionId = origin.sessionId,
                direction = EnvelopeDirection.TO_LOCAL,
                msgType = type,
                routerTarget = origin.routerTarget,
                isStream = isStream,
                payload = payload,
                meta = JSONObject().put("origin", origin.envelopeId),
            )
        )
    }

    companion object {
        fun defaultScript(): List<Pair<String, JSONObject>> = listOf(
            "file_write" to JSONObject()
                .put("path", "hybrid.txt")
                .put("content", "HYBRID 端云协同闭环\n由云端下发 TOOL_CALL、端侧执行\n"),
            "shell_execute" to JSONObject().put("command", "ls -l && cat hybrid.txt"),
        )
    }
}

/** 离线/纯 LOCAL 使用的空传输：任何上云信封都失败并进死信，可验证降级。 */
class OfflineTransport : Transport {
    override fun send(env: AgentEnvelope): Boolean = false
}
