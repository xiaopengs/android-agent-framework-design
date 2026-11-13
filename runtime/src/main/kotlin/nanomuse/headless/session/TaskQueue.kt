package nanomuse.headless.session

import nanomuse.headless.core.EventSink
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Layer3 —— TaskQueue + ConcurrencyController。
 *
 * 对齐 DESIGN.md 2.1「会话内部串行，会话之间并行」与 12 非功能需求：
 *  - 每个 sessionId 一个单线程 executor，保证会话内任务严格排队；
 *  - 全局 Semaphore 限制在途任务数，active session 数超限时拒绝新会话；
 *  - 支持 cancel（协作式取消，循环层轮询 isCancelled）与 awaitIdle。
 */
data class AgentTask(
    val id: String = "task-" + UUID.randomUUID().toString().take(8),
    val sessionId: String,
    val traceId: String,
    val mode: String = "LOCAL",
    val payload: JSONObject = JSONObject(),
    val priority: Int = 0,
    val enqueuedAt: Long = System.currentTimeMillis(),
)

class ConcurrencyController(
    private val maxConcurrentSessions: Int = 4,
    private val maxInFlightTasks: Int = 8,
    private val events: EventSink? = null,
) {
    private val sem = Semaphore(maxInFlightTasks, true)
    private val activeSessions = ConcurrentHashMap.newKeySet<String>()
    private val inFlight = AtomicInteger(0)

    fun tryAcquire(sessionId: String, timeoutMs: Long = 10_000): Boolean {
        if (activeSessions.size >= maxConcurrentSessions && sessionId !in activeSessions) {
            events?.emit(
                "task_rejected",
                JSONObject().put("session", sessionId).put("reason", "max_concurrent_sessions")
                    .put("limit", maxConcurrentSessions),
            )
            return false
        }
        if (!sem.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
            events?.emit(
                "task_rejected",
                JSONObject().put("session", sessionId).put("reason", "max_in_flight_tasks")
                    .put("limit", maxInFlightTasks),
            )
            return false
        }
        activeSessions += sessionId
        inFlight.incrementAndGet()
        events?.emit(
            "task_start",
            JSONObject().put("session", sessionId).put("in_flight", inFlight.get()),
        )
        return true
    }

    fun release(sessionId: String) {
        activeSessions -= sessionId
        inFlight.decrementAndGet()
        sem.release()
    }

    fun inFlight(): Int = inFlight.get()
    fun activeSessions(): Int = activeSessions.size
}

class TaskQueue(
    private val concurrency: ConcurrencyController = ConcurrencyController(),
    private val events: EventSink? = null,
) {
    private val executors = ConcurrentHashMap<String, ExecutorService>()
    private val futures = ConcurrentHashMap<String, MutableList<Future<*>>>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    private fun executor(sessionId: String): ExecutorService =
        executors.computeIfAbsent(sessionId) {
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "nm-session-$sessionId").apply { isDaemon = true }
            }
        }

    /** 提交任务：同一会话串行、不同会话并行，进执行体前先取并发额度。 */
    fun submit(task: AgentTask, work: (AgentTask) -> Unit): Future<*> {
        events?.emit(
            "task_enqueue",
            JSONObject().put("task_id", task.id).put("session", task.sessionId)
                .put("trace_id", task.traceId).put("mode", task.mode),
        )
        val f = executor(task.sessionId).submit {
            if (isCancelled(task.sessionId)) {
                events?.emit(
                    "task_cancelled",
                    JSONObject().put("task_id", task.id).put("session", task.sessionId),
                )
            } else if (concurrency.tryAcquire(task.sessionId)) {
                try {
                    work(task)
                } finally {
                    concurrency.release(task.sessionId)
                }
            }
        }
        futures.computeIfAbsent(task.sessionId) { java.util.concurrent.CopyOnWriteArrayList() }.add(f)
        return f
    }

    /** 协作式取消：置标记，循环层每轮检查。 */
    fun cancel(sessionId: String) {
        cancelled += sessionId
        events?.emit("task_cancel_requested", JSONObject().put("session", sessionId))
    }

    fun isCancelled(sessionId: String): Boolean = sessionId in cancelled
    fun clearCancel(sessionId: String) = cancelled.remove(sessionId)

    fun awaitIdle(sessionId: String, timeoutMs: Long = 60_000): Boolean {
        val list = futures[sessionId] ?: return true
        val deadline = System.currentTimeMillis() + timeoutMs
        return list.all { f ->
            val left = (deadline - System.currentTimeMillis()).coerceAtLeast(1)
            runCatching { f.get(left, TimeUnit.MILLISECONDS); true }.getOrDefault(false)
        }
    }

    fun awaitAll(timeoutMs: Long = 60_000): Boolean =
        futures.keys.all { awaitIdle(it, timeoutMs) }

    fun inFlight(): Int = concurrency.inFlight()
    fun activeSessions(): Int = concurrency.activeSessions()

    fun shutdown() {
        executors.values.forEach { it.shutdown() }
        executors.values.forEach { runCatching { it.awaitTermination(5, TimeUnit.SECONDS) } }
    }
}
