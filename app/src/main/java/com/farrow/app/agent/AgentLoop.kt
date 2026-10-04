package com.farrow.app.agent

import com.farrow.app.agent.context.ContextManager
import com.farrow.app.agent.tools.FencedToolCallParser
import com.farrow.app.agent.tools.ToolRegistry
import com.farrow.app.data.network.*
import com.farrow.app.data.social.SessionGuard
import com.farrow.app.domain.model.*
import com.farrow.app.domain.repository.NotificationRepository
import com.farrow.app.domain.repository.SettingsRepository
import com.farrow.app.domain.repository.TaskRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

sealed interface RunResult {
    data object Completed : RunResult
    /** Paused with automatic resume at [resumeAt] (scheduled through WorkManager by the runner). */
    data class Paused(val resumeAt: Long) : RunResult
    data object Stopped : RunResult
}

/**
 * The tool-use loop: model → tool_calls → tool results → model … until a final answer or max steps.
 * Phase 3: progress is checkpointed after every step; rate limits and transient errors pause the
 * task (with backoff / reset-time / 00:00 UTC resume) instead of failing it.
 *
 * v0.9.8: the loop never ends silently. Tool exceptions become tool results and the loop continues; an empty
 * model reply gets one nudge, then a fallback agent message; every non-final exit (max steps, crash, cancel,
 * content filter, truncation/parse/empty streaks) posts a visible status message and sets PAUSED/FAILED/CANCELLED.
 */
@Singleton
class AgentLoop internal constructor(
    private val tasks: TaskRepository,
    private val settings: SettingsRepository,
    private val registry: ToolRegistry,
    private val notifications: NotificationRepository,
    private val model: suspend (messages: List<ApiMessage>, tools: kotlinx.serialization.json.JsonArray?, taskId: Long, onStatus: suspend (String) -> Unit) -> ChatOutcome,
    private val summarize: suspend (taskId: Long, systemPrompt: String, onStatus: suspend (String) -> Unit) -> Unit,
    private val consumeExpiredSession: (taskId: Long) -> String?,
    private val notifyOnFinish: () -> Boolean,
    private val systemNotify: (taskId: Long, title: String, body: String) -> Unit,
    private val browserLanguage: () -> String = { com.farrow.app.data.browser.BrowserLanguage.DEFAULT },
    /** v0.9.16: persistent memory block (instructions + up to ~20 memories, ~1.5k tokens). */
    private val memoryPrompt: suspend (taskId: Long) -> String = { "" },
) {
    @Inject constructor(
        tasks: TaskRepository,
        settings: SettingsRepository,
        client: OpenRouterClient,
        registry: ToolRegistry,
        context: ContextManager,
        notifications: NotificationRepository,
        sessionGuard: SessionGuard,
        prefs: com.farrow.app.data.prefs.AppPrefs,
        alerts: com.farrow.app.data.notify.AlertNotifier,
        memory: com.farrow.app.data.memory.MemoryRepository,
    ) : this(
        tasks, settings, registry, notifications,
        model = { m, t, id, st -> client.complete(m, t, id, st) },
        summarize = { id, sp, st -> context.maybeSummarize(id, sp, st) },
        consumeExpiredSession = sessionGuard::consumePending,
        notifyOnFinish = { prefs.notifyOnFinish.value },
        systemNotify = { id, title, body -> alerts.notify(FINISH_NOTIFICATION_BASE + (id % 10_000).toInt(), title, body, id) },
        browserLanguage = { prefs.browserLanguage.value },
        memoryPrompt = { id -> try { memory.promptBlock(id) } catch (e: Exception) { "" } },
    )

    private suspend fun fullSystemPrompt(taskId: Long): String = systemPrompt(browserLanguage()) + memoryPrompt(taskId).let { if (it.isBlank()) "" else "\n\n" + com.farrow.app.data.network.MemoryRedaction.wrap(it) }

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    suspend fun run(taskId: Long): RunResult {
        val task = tasks.getTask(taskId) ?: return RunResult.Stopped
        try {
            return runInner(task)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                tasks.updateStatus(taskId, TaskStatus.CANCELLED, "Stopped")
                status(taskId, "⏹️ Generation stopped. Tap Continue to resume.", null)
            }
            throw e
        } catch (e: Throwable) {
            // Never leave the task silently RUNNING/IDLE: record the crash where the user sees it.
            withContext(NonCancellable) { fail(taskId, "The agent loop crashed: ${e.javaClass.simpleName}: ${e.message ?: "no message"}. Tap Continue to retry.") }
            return RunResult.Stopped
        }
    }

    private suspend fun runInner(task: Task): RunResult {
        val taskId = task.id
        if (task.startedAt == null) tasks.markStarted(taskId)
        tasks.updateStatus(taskId, TaskStatus.RUNNING, "Thinking...")
        val maxSteps = settings.currentLimits().maxSteps
        var attempt = task.attempt
        var cp = CheckpointCodec.decode(task.checkpointJson, task.currentStep)
        if (cp.pendingStep != null) status(taskId, "▶️ Resuming from checkpoint (step ${cp.pendingStep?.step}).", null)
        var lastToolError: String? = null
        var emptyStreak = 0
        var lengthStreak = 0
        var parseStreak = 0
        var nudge: String? = null
        /** The empty-reply nudge asked for a visible progress report; a text-only answer to it is not the final answer. */
        var reportRequested = false
        for (i in 1..maxSteps) {
            val step = cp.step + 1
            coroutineContext.ensureActive()
            summarize(taskId, fullSystemPrompt(taskId)) { tasks.updateSubtitle(taskId, it) }
            tasks.updateSubtitle(taskId, "Thinking...")
            cp = cp.copy(pendingStep = PendingStep(step, "calling_model", startedAt = System.currentTimeMillis()), phase = "calling_model")
            saveCheckpoint(taskId, cp)
            val messages = buildApiMessages(taskId).let { m -> nudge?.let { m + ApiMessage("system", it) } ?: m }
            val outcome = model(messages, registry.schemas(), taskId) { tasks.updateSubtitle(taskId, it) }
            when (outcome) {
                ChatOutcome.NoApiKey -> {
                    fail(taskId, "No model available: add an OpenRouter API key (Settings → API keys) or keep kilo:kilo-auto/free in Settings → Model priority.")
                    return RunResult.Stopped
                }
                is ChatOutcome.Failure -> return pauseForFailure(taskId, outcome.message, attempt)
                is ChatOutcome.RateLimited -> return pauseForRateLimit(task, outcome, attempt)
                is ChatOutcome.Success -> {
                    if (attempt != 0) { attempt = 0; tasks.setAttempt(taskId, 0) }
                    val c = outcome.completion
                    val finish = c.finishReason?.lowercase()
                    val native = c.toolCalls
                    val fenced = if (native.isEmpty()) FencedToolCallParser.parse(c.content, registry.names) else emptyList()
                    val calls = native.ifEmpty { fenced }
                    val blank = c.content.isNullOrBlank()

                    if (finish == "error") return pauseForFailure(taskId, "The model returned finish_reason=error", attempt)
                    if (blank && calls.isEmpty()) {
                        if (finish == "content_filter") {
                            return pauseWithReason(taskId, "Blocked by content filter",
                                "🚫 The model's reply was blocked by its content filter (finish_reason=content_filter). Rephrase, or tap Continue to try again (another model may answer).")
                        }
                        emptyStreak++
                        if (emptyStreak == 1) {
                            nudge = LoopMessages.nudge(lastToolError)
                            reportRequested = true
                            status(taskId, "↩️ The model returned an empty reply — asking it for a short progress report, then to continue.", null)
                            cp = cp.copy(pendingStep = null, phase = "nudged")
                            saveCheckpoint(taskId, cp)
                            continue
                        }
                        // Still empty: explain it ourselves, then pause visibly.
                        reportRequested = false
                        tasks.addMessage(ChatMessage(0, taskId, MessageRole.ASSISTANT, LoopMessages.fallback(lastToolError, cp.intermediateResults), MessageKind.NORMAL,
                            null, null, null, outcome.model, false, null, System.currentTimeMillis()))
                        return pauseWithReason(taskId, "Model returned empty replies",
                            "⏸️ Stopped: the model returned empty replies twice${lastToolError?.let { " after a tool error" } ?: ""}. Tap Continue to retry, or send a message with more guidance.")
                    }
                    emptyStreak = 0
                    nudge = null
                    val msgId = tasks.addMessage(
                        ChatMessage(0, taskId, MessageRole.ASSISTANT, c.content, MessageKind.NORMAL,
                            if (native.isNotEmpty()) json.encodeToString(native.map { ApiToolCall(it.id, "function", ApiFunctionCall(it.name, it.argumentsJson)) }) else null,
                            null, null, outcome.model, false, null, System.currentTimeMillis())
                    )
                    if (calls.isEmpty()) {
                        if (finish == "length") {
                            lengthStreak++
                            if (lengthStreak > MAX_LENGTH_CONTINUATIONS) {
                                return pauseWithReason(taskId, "Reply cut off", "✂️ The model's reply was cut off (finish_reason=length) ${lengthStreak} times. Tap Continue to keep going, or lower the context in Settings → Limits.")
                            }
                            nudge = "Your previous reply was cut off by the token limit. Continue exactly where you stopped, without repeating."
                            status(taskId, "✂️ Reply was cut off (finish_reason=length) — asking the model to continue.", null)
                            cp = cp.copy(step = step, pendingStep = null, lastMessageId = msgId, model = outcome.model, phase = "truncated")
                            saveCheckpoint(taskId, cp)
                            continue
                        }
                        if (LoopMessages.looksLikeToolCall(c.content)) {
                            parseStreak++
                            if (parseStreak > 1) {
                                return pauseWithReason(taskId, "Tool call not understood", "⚠️ The model's tool call couldn't be parsed twice in a row. Tap Continue to retry.")
                            }
                            nudge = "Your last tool call could not be parsed. Use a native tool call, or exactly one fenced ```json block like {\"tool\": \"name\", \"arguments\": {...}} with valid JSON and a known tool name (${registry.names.sorted().joinToString()})."
                            status(taskId, "⚠️ Couldn't parse the model's tool call — asking it to resend.", null)
                            cp = cp.copy(step = step, pendingStep = null, lastMessageId = msgId, model = outcome.model, phase = "parse_failed")
                            saveCheckpoint(taskId, cp)
                            continue
                        }
                        if (reportRequested) {
                            // The progress report asked for by the empty-reply nudge: shown as an agent message; keep working.
                            reportRequested = false
                            nudge = LoopMessages.CONTINUE_AFTER_REPORT
                            cp = cp.copy(step = step, pendingStep = null, lastMessageId = msgId, model = outcome.model, phase = "reported")
                            saveCheckpoint(taskId, cp)
                            continue
                        }
                        val snippet = c.content?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(80) ?: "Done"
                        cp = cp.copy(
                            step = step, pendingStep = null, lastMessageId = msgId, model = outcome.model, phase = "completed",
                            completedSteps = cp.completedSteps + CompletedStep(step, outcome.model, emptyList(), System.currentTimeMillis()),
                        )
                        saveCheckpoint(taskId, cp)
                        tasks.updateStatus(taskId, TaskStatus.COMPLETED, snippet)
                        if (finish == "content_filter") status(taskId, "🚫 Part of the reply may have been removed by the model's content filter.", null)
                        // v0.9.8: completions only notify when the user opted in ("Notify when a task finishes").
                        if (notifyOnFinish()) {
                            notifications.add(NotificationType.COMPLETED, "Completed: ${task.title}", snippet, taskId)
                            runCatching { systemNotify(taskId, "Completed: ${task.title}", snippet) }
                        }
                        return RunResult.Completed
                    }
                    lengthStreak = 0
                    parseStreak = 0
                    reportRequested = false
                    cp = cp.copy(pendingStep = PendingStep(step, "executing_tools", calls.map { it.id }, System.currentTimeMillis()),
                        lastMessageId = msgId, model = outcome.model, phase = "executing_tools")
                    saveCheckpoint(taskId, cp)
                    val results = mutableListOf<IntermediateResult>()
                    lastToolError = null
                    var repeatStop: String? = null
                    for (call in calls) {
                        coroutineContext.ensureActive()
                        // v1.0.6: x_reply on the same post more than twice since the user's last message → stop the task.
                        val rk = ReplyRepeatGuard.key(call.name, call.argumentsJson)
                        if (rk != null && repeatStop == null) {
                            val since = tasks.getMessages(taskId).lastOrNull { it.role == MessageRole.USER }?.createdAt ?: 0L
                            val prev = ReplyRepeatGuard.previous(tasks.observeToolCalls(taskId).first(), rk, since)
                            if (ReplyRepeatGuard.blocked(prev)) repeatStop = ReplyRepeatGuard.message(rk.removePrefix("x:").let { if (it.all(Char::isDigit)) "post $it" else it }, prev)
                        }
                        if (repeatStop != null && (rk != null || call.name.startsWith("web_"))) {
                            val j = LoopMessages.toolErrorJson(repeatStop!!)
                            val recId = tasks.addToolCall(ToolCallRecord(0, taskId, msgId, call.id, call.name, call.argumentsJson, null,
                                ToolCallStatus.PENDING, if (native.isNotEmpty()) "native" else "fenced", System.currentTimeMillis(), null))
                            tasks.finishToolCall(recId, j, ToolCallStatus.ERROR)
                            tasks.addMessage(ChatMessage(0, taskId, MessageRole.TOOL, j, MessageKind.NORMAL, null, call.id, call.name,
                                null, false, null, System.currentTimeMillis()))
                            results += IntermediateResult(step, call.name, j.take(300), true)
                            continue
                        }
                        val r = executeTool(taskId, msgId, call, if (native.isNotEmpty()) "native" else "fenced")
                        results += IntermediateResult(step, call.name, r.json.take(300), r.isError)
                        if (r.isError) lastToolError = "${call.name}: ${r.json.take(300)}"
                    }
                    cp = cp.copy(
                        step = step, pendingStep = null, phase = "tools_executed",
                        completedSteps = cp.completedSteps + CompletedStep(step, outcome.model, calls.map { it.name }, System.currentTimeMillis()),
                        intermediateResults = cp.intermediateResults + results,
                    )
                    saveCheckpoint(taskId, cp)
                    repeatStop?.let { why ->
                        tasks.addMessage(ChatMessage(0, taskId, MessageRole.ASSISTANT, "⚠️ $why", MessageKind.NORMAL, null, null, null,
                            outcome.model, false, null, System.currentTimeMillis()))
                        return pauseWithReason(taskId, "x_reply repeated on the same post", "⏸️ $why Tap Continue only after checking the post.")
                    }
                    // Phase 5/9: a social tool hit a login wall -> pause until the user re-logs in.
                    consumeExpiredSession(taskId)?.let { site ->
                        tasks.pause(taskId, TaskStatus.PAUSED, "Session expired on $site – re-login needed", null,
                            PauseReason.SESSION_EXPIRED, 0)
                        status(taskId, "🔐 Paused: $site session expired. Tap Re-login, then Continue.", null)
                        return RunResult.Stopped
                    }
                }
            }
        }
        tasks.pause(taskId, TaskStatus.PAUSED, "Max steps reached", null, PauseReason.MAX_STEPS, 0)
        status(taskId, "⏸️ Reached the max-steps cap ($maxSteps). Tap Continue to keep going.", null)
        return RunResult.Stopped
    }

    private suspend fun pauseForFailure(taskId: Long, message: String, attempt: Int): RunResult {
        if (attempt + 1 >= BackoffPolicy.MAX_TRANSIENT_ATTEMPTS) {
            fail(taskId, "$message (gave up after $attempt retries)")
            return RunResult.Stopped
        }
        val resumeAt = System.currentTimeMillis() + BackoffPolicy.backoffMs(attempt)
        tasks.pause(taskId, TaskStatus.PAUSED, "Retrying after error…", resumeAt, PauseReason.TRANSIENT, attempt + 1)
        status(taskId, "⏸️ Paused after an error: ${message.take(160)}. Retrying with backoff.", resumeAt)
        return RunResult.Paused(resumeAt)
    }

    /** Manual-resume pause with a visible reason (the chat shows a Continue button for PAUSED tasks). */
    private suspend fun pauseWithReason(taskId: Long, subtitle: String, message: String): RunResult {
        tasks.pause(taskId, TaskStatus.PAUSED, subtitle, null, PauseReason.USER, 0)
        status(taskId, message, null)
        return RunResult.Stopped
    }
    private suspend fun pauseForRateLimit(task: Task, outcome: ChatOutcome.RateLimited, attempt: Int): RunResult {
        val now = System.currentTimeMillis()
        val resumeAt = BackoffPolicy.resumeAt(
            now = now,
            attempt = attempt,
            dailyQuota = outcome.dailyQuota,
            dailyResetAt = ClientRateLimiter.nextUtcMidnight(now),
            explicitResetAt = if (outcome.explicitReset) outcome.resumeAt else null,
            earliestAvailable = outcome.resumeAt,
        )
        if (outcome.dailyQuota) {
            tasks.pause(task.id, TaskStatus.RATE_LIMITED, "Daily free quota exhausted – resumes 00:00 UTC", resumeAt, PauseReason.DAILY_QUOTA, attempt + 1)
            status(task.id, DAILY_QUOTA_MESSAGE, null)
            notifications.add(NotificationType.RATE_LIMIT, "Daily free quota exhausted: ${task.title}",
                "Paused until 00:00 UTC. It will resume automatically.", task.id)
        } else {
            tasks.pause(task.id, TaskStatus.RATE_LIMITED, "Waiting for OpenRouter reset...", resumeAt, PauseReason.RATE_LIMIT, attempt + 1)
            status(task.id, "⏸️ Paused: Rate limit hit.", resumeAt)
            notifications.add(NotificationType.RATE_LIMIT, "Rate limit hit: ${task.title}",
                "All models/keys are rate-limited; auto-resume scheduled. ${outcome.message}".take(300), task.id)
        }
        return RunResult.Paused(resumeAt)
    }

    private suspend fun saveCheckpoint(taskId: Long, cp: TaskCheckpoint) {
        tasks.updateCheckpoint(taskId, cp.step, cp.model, CheckpointCodec.encode(cp.copy(at = System.currentTimeMillis())))
    }

    private suspend fun executeTool(taskId: Long, msgId: Long, call: ParsedToolCall, source: String): com.farrow.app.agent.tools.ToolResult {
        tasks.updateSubtitle(taskId, "Running ${call.name}…")
        val recId = tasks.addToolCall(ToolCallRecord(0, taskId, msgId, call.id, call.name, call.argumentsJson, null,
            ToolCallStatus.PENDING, source, System.currentTimeMillis(), null))
        // Any Throwable from a tool becomes an error tool result; the loop always continues (only cancellation propagates).
        val result = try {
            registry.execute(call.name, call.argumentsJson, taskId)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { tasks.finishToolCall(recId, LoopMessages.toolErrorJson("cancelled"), ToolCallStatus.ERROR) }
            throw e
        } catch (e: Throwable) {
            com.farrow.app.agent.tools.ToolResult(LoopMessages.toolErrorJson("${e.javaClass.simpleName}: ${e.message ?: "no message"}"), true)
        }
        tasks.finishToolCall(recId, result.json, if (result.isError) ToolCallStatus.ERROR else ToolCallStatus.SUCCESS)
        tasks.addMessage(ChatMessage(0, taskId, MessageRole.TOOL, result.json, MessageKind.NORMAL, null, call.id, call.name,
            null, false, null, System.currentTimeMillis()))
        return result
    }

    private suspend fun fail(taskId: Long, message: String) {
        tasks.updateStatus(taskId, TaskStatus.FAILED, "Failed: ${message.take(80)}", error = message)
        status(taskId, "❌ $message", null)
        val title = tasks.getTask(taskId)?.title ?: "Task"
        notifications.add(NotificationType.FAILED, "Failed: $title", message.take(300), taskId)
    }

    private suspend fun status(taskId: Long, text: String, resumeAt: Long?) {
        tasks.addMessage(ChatMessage(0, taskId, MessageRole.SYSTEM, text, MessageKind.STATUS, null, null, null, null,
            false, resumeAt, System.currentTimeMillis()))
    }

    /** System prompt + summaries + live turns, converted to the OpenAI-compatible wire format. */
    suspend fun buildApiMessages(taskId: Long): List<ApiMessage> {
        val live = ContextManager.live(tasks.getMessages(taskId))
        val answeredIds = live.filter { it.role == MessageRole.TOOL }.mapNotNull { it.toolCallId }.toSet()
        val out = mutableListOf(ApiMessage("system", fullSystemPrompt(taskId)))
        live.filter { it.kind == MessageKind.SUMMARY }.forEach {
            out += ApiMessage("system", "Summary of the earlier conversation:\n${it.content}")
        }
        for (m in live.filter { it.kind == MessageKind.NORMAL }) {
            when (m.role) {
                MessageRole.USER -> out += ApiMessage("user", m.content.orEmpty())
                MessageRole.SYSTEM -> out += ApiMessage("system", m.content.orEmpty())
                MessageRole.ASSISTANT -> {
                    val calls = m.toolCallsJson?.let { runCatching { json.decodeFromString<List<ApiToolCall>>(it) }.getOrNull() }
                        ?.filter { it.id in answeredIds }.orEmpty()
                    if (calls.isEmpty() && m.content.isNullOrBlank()) continue
                    out += ApiMessage("assistant", m.content ?: "", toolCalls = calls.ifEmpty { null })
                }
                MessageRole.TOOL -> {
                    val id = m.toolCallId.orEmpty()
                    out += if (id.startsWith("fenced_")) {
                        ApiMessage("user", "Tool result for ${m.toolName}:\n```json\n${m.content}\n```")
                    } else {
                        ApiMessage("tool", m.content.orEmpty(), toolCallId = id, name = m.toolName)
                    }
                }
            }
        }
        // web_screenshot: attach the JPEG to this request when the model can see images (stripped per model otherwise).
        val trailing = live.filter { it.kind == MessageKind.NORMAL }.takeLastWhile { it.role == MessageRole.TOOL }
        com.farrow.app.agent.tools.ScreenshotAttach.pending(trailing.map { it.toolName to it.content }).mapNotNull { p ->
            runCatching { java.io.File(p).readBytes() }.getOrNull()?.let(com.farrow.app.agent.tools.ScreenshotAttach::dataUrl)
        }.takeIf { it.isNotEmpty() }?.let { urls ->
            out += ApiMessage("user", "Screenshot from web_screenshot (current page of the internal browser):", images = urls)
        }
        return out
    }

    companion object {
        private const val MAX_LENGTH_CONTINUATIONS = 3
        private const val FINISH_NOTIFICATION_BASE = 52_000
        const val DAILY_QUOTA_MESSAGE = "⏸️ Paused: Daily free quota exhausted. Resuming at 00:00 UTC."
        val SYSTEM_PROMPT = """
            You are Farrow, an autonomous assistant running inside an Android app.
            You can call the tools listed in the tool definitions (the user can turn tools off in Settings > Tools; only call
            the ones you were given). File tools work in a private sandboxed workspace (paths relative to its root).
            Prefer native tool/function calls. If you cannot use native tool calls, emit exactly one fenced block like:
            ```json
            {"tool": "write_file", "arguments": {"path": "notes.txt", "content": "hello"}}
            ```
            and wait for the result. When the task is finished, reply with a concise final answer (Markdown allowed) and no tool call.
        """.trimIndent()

        /** Which tools control what — the agent kept asking for accessibility permission to click in its own browser. */
        val TOOL_GROUPS = """
            Tool groups:
            - Internal browser (Firefox driven by Termux Browser Pilot inside Termux, invisible, not on the phone screen):
              web_scrape, web_click, web_type, web_session, web_screenshot, and the site tools x_status / x_post / x_reply /
              x_scrape, fb_*. They need NO accessibility permission and no screen access. Use them for every web task
              (searching, reading pages, clicking/typing on websites, posting on X).
              X rules (strict): to reply to or comment on an X post ALWAYS call x_reply(url, text) with url = the post's
              status_url from x_scrape (mode=quote to quote it); new posts: x_post. NEVER use web_click / web_type on X
              composers, reply boxes or Reply/Post buttons: they are refused, and they caused "Save post?" prompts and
              truncated text. Reading replies: x_scrape kind=replies. If x_reply or x_post fails, report its error and
              steps to the user instead of improvising clicks; call x_reply at most twice per post (a third call stops
              the task). If x_reply says "submitted but not confirmed", check with x_scrape kind=replies, never reply again.
            - Phone screen (accessibility): screen_read, screen_tap, screen_swipe, screen_type, screen_action. Only for
              controlling OTHER Android apps on the phone's display; they cannot see or click the internal browser.
              Never ask the user for accessibility permission for a web task.
            - Device: run_shell (Shizuku), termux_run (Termux packages). Files: read_file, write_file, list_dir. Git: git_*.
            - Memory: memory_save, memory_search, memory_delete — scope="chat" (short-term) for the current task's progress
              and decisions, scope="global" (long-term) for lasting facts and preferences about the user.
            System note (built in): clicking, typing and logging in on websites happens in the internal browser with the
            web_* / x_* tools — accessibility is never required for that.
        """.trimIndent()

        /** [SYSTEM_PROMPT] + the source-language rule for the "Browser language" setting (default English). */
        fun systemPrompt(lang: String): String {
            val l = com.farrow.app.data.browser.BrowserLanguage.of(lang)
            return SYSTEM_PROMPT + "\n" + TOOL_GROUPS + "\n" + if (l.code == "en")
                "Prefer English-language sources and search in English (e.g. https://html.duckduckgo.com/html/?q=<query>&kl=us-en), " +
                    "even if the user is in France, unless the user asks for another language. Reply in the user's language."
            else "Prefer ${l.label} (${l.code}) sources, falling back to English. Reply in the user's language."
        }
    }
}

/** User-visible texts of the loop's recovery paths (kept here so tests can check them). */
object LoopMessages {
    /** Empty reply → ask for a short visible report (done / stuck / next), then to carry on with tools. */
    fun nudge(lastToolError: String?): String = buildString {
        append("Your last reply was empty. First write a short progress report to the user as a normal chat message, ")
        append("2 to 4 sentences: what you have done so far, what went wrong or what you are stuck on, and what you will try next. ")
        if (lastToolError != null) append("The last tool failed: ${lastToolError.take(300)}. ")
        append("Then continue the task with tool calls (in the same reply if you can). If the task is actually finished, give the final answer instead.")
    }

    const val CONTINUE_AFTER_REPORT = "Thanks for the update. Now continue the task with tool calls as you described, " +
        "or give the final answer if it is done."

    /** Second empty reply: a summary built from the recent tool calls so the user still sees where things stand. */
    fun fallback(lastToolError: String?, recent: List<IntermediateResult> = emptyList()): String = buildString {
        append("⚠️ I couldn't continue: the model returned empty replies twice.\n\n")
        val last = recent.takeLast(6)
        if (last.isNotEmpty()) {
            append("**What I did so far** (last ${last.size} tool calls):\n")
            last.forEach { r ->
                append("- ").append(if (r.isError) "❌ " else "✅ ").append('`').append(r.tool).append('`')
                val p = r.preview.replace(Regex("\\s+"), " ").trim().take(140)
                if (p.isNotEmpty()) append(" — ").append(p)
                append('\n')
            }
            append('\n')
        }
        if (lastToolError != null) append("**Stuck on** this tool error:\n\n```\n").append(lastToolError.take(500)).append("\n```\n\n")
        append("Tap **Continue** to retry (another model may answer), or tell me how to proceed.")
    }

    fun toolErrorJson(message: String): String =
        kotlinx.serialization.json.buildJsonObject { put("error", kotlinx.serialization.json.JsonPrimitive(message)) }.toString()

    /** A reply that tried to emit a fenced tool call that the parser rejected. */
    fun looksLikeToolCall(content: String?): Boolean {
        val c = content ?: return false
        return c.contains("```") && Regex("\"(tool|name)\"\\s*:").containsMatchIn(c) && c.contains("\"arguments\"")
    }
}
