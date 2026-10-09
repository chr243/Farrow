package com.farrow.app.agent

import com.farrow.app.agent.tools.AgentTool
import com.farrow.app.agent.tools.ToolRegistry
import com.farrow.app.data.network.*
import com.farrow.app.domain.model.*
import com.farrow.app.domain.repository.NotificationRepository
import com.farrow.app.domain.repository.SettingsRepository
import com.farrow.app.domain.repository.TaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

/** v0.9.8: the agent loop never stops silently. */
class AgentLoopTest {

    private class FakeTasks : TaskRepository {
        var task = Task(1, "Demo", "do it", TaskType.CHAT, TaskStatus.QUEUED, "", 0, null, null, 0, 0, null, 0, null, null)
        val messages = mutableListOf<ChatMessage>()
        val toolCalls = mutableListOf<ToolCallRecord>()
        var pausedReason: PauseReason? = null
        override fun observeConversations(): Flow<List<Conversation>> = flowOf(emptyList())
        override fun observeTask(taskId: Long): Flow<Task?> = flowOf(task)
        override fun observeMessages(taskId: Long): Flow<List<ChatMessage>> = flowOf(messages)
        override fun observeToolCalls(taskId: Long): Flow<List<ToolCallRecord>> = flowOf(toolCalls)
        override suspend fun getTask(taskId: Long) = task
        override suspend fun getMessages(taskId: Long) = messages.toList()
        override suspend fun createTask(title: String, prompt: String, type: TaskType) = 1L
        override suspend fun updateStatus(taskId: Long, status: TaskStatus, subtitle: String, resumeAt: Long?, error: String?) {
            task = task.copy(status = status, subtitle = subtitle, resumeAt = resumeAt, errorMessage = error)
        }
        override suspend fun updateSubtitle(taskId: Long, subtitle: String) { task = task.copy(subtitle = subtitle) }
        override suspend fun updateCheckpoint(taskId: Long, step: Int, model: String?, checkpointJson: String) {
            task = task.copy(currentStep = step, checkpointJson = checkpointJson)
        }
        override suspend fun markStarted(taskId: Long) {}
        override suspend fun markOpened(taskId: Long) {}
        override suspend fun addMessage(message: ChatMessage): Long { messages += message.copy(id = messages.size + 1L); return messages.size.toLong() }
        override suspend fun markSummarized(ids: List<Long>) {}
        override suspend fun addToolCall(record: ToolCallRecord): Long { toolCalls += record.copy(id = toolCalls.size + 1L); return toolCalls.size.toLong() }
        override suspend fun finishToolCall(id: Long, resultJson: String, status: ToolCallStatus) {
            toolCalls[(id - 1).toInt()] = toolCalls[(id - 1).toInt()].copy(resultJson = resultJson, status = status)
        }
        override suspend fun deleteTask(taskId: Long) {}
        override fun observeArchived(): Flow<List<Task>> = flowOf(emptyList())
        override suspend fun archive(taskId: Long) {}
        override suspend fun restore(taskId: Long) {}
        override suspend fun archivedIds() = emptyList<Long>()
        override suspend fun pause(taskId: Long, status: TaskStatus, subtitle: String, resumeAt: Long?, reason: PauseReason, attempt: Int) {
            task = task.copy(status = status, subtitle = subtitle, resumeAt = resumeAt); pausedReason = reason
        }
        override suspend fun setAttempt(taskId: Long, attempt: Int) {}
        override suspend fun tasksWithStatus(statuses: List<TaskStatus>) = listOf(task).filter { it.status in statuses }
        fun statusTexts() = messages.filter { it.kind == MessageKind.STATUS }.map { it.content.orEmpty() }
    }

    private class FakeSettings(val maxSteps: Int) : SettingsRepository {
        override val modelPriority: Flow<List<String>> = flowOf(listOf("m"))
        override val limits: Flow<LimitSettings> = flowOf(LimitSettings(maxSteps = maxSteps))
        override val chatHeadMode: Flow<ChatHeadMode> = flowOf(ChatHeadMode.AUTO)
        override suspend fun setChatHeadMode(mode: ChatHeadMode) {}
        override suspend fun currentModels() = listOf("m")
        override suspend fun currentLimits() = LimitSettings(maxSteps = maxSteps)
        override suspend fun setModelPriority(models: List<String>) {}
        override suspend fun resetModelPriority() {}
        override suspend fun updateLimits(transform: (LimitSettings) -> LimitSettings) {}
    }

    private class FakeNotifications : NotificationRepository {
        val added = mutableListOf<NotificationType>()
        override fun observe(): Flow<List<AppNotification>> = MutableStateFlow(emptyList())
        override fun observeUnreadCount(): Flow<Int> = flowOf(0)
        override suspend fun add(type: NotificationType, title: String, body: String, taskId: Long?) { added += type }
        override suspend fun markAllRead() {}
        override suspend fun clear() {}
    }

    private class ThrowingTool : AgentTool {
        override val name = "boom"
        override val description = "always throws"
        override val parameters = JsonObject(emptyMap())
        override suspend fun execute(args: JsonObject): String = throw NotImplementedError("tool exploded")
    }

    private fun reply(content: String?, calls: List<ParsedToolCall> = emptyList(), finish: String? = "stop") =
        ChatOutcome.Success(ParsedCompletion(content, calls, finish, "m"), "m", "k")

    private class Harness(val replies: List<ChatOutcome>, maxSteps: Int = 10, notifyFinish: Boolean = false) {
        val tasks = FakeTasks()
        val notifications = FakeNotifications()
        val sent = mutableListOf<List<ApiMessage>>()
        var systemNotified = 0
        private var i = 0
        val loop = AgentLoop(
            tasks, FakeSettings(maxSteps), ToolRegistry(listOf(ThrowingTool())), notifications,
            model = { m: List<ApiMessage>, _: JsonArray?, _: Long, _: suspend (String) -> Unit -> sent += m; replies[minOf(i++, replies.lastIndex)] },
            summarize = { _, _, _ -> },
            notifyOnFinish = { notifyFinish },
            systemNotify = { _, _, _ -> systemNotified++ },
        )
    }

    @Test fun `tool exception becomes a tool result and the loop continues`() = runTest {
        val h = Harness(listOf(
            reply(null, listOf(ParsedToolCall("c1", "boom", "{}")), finish = "tool_calls"),
            reply("The tool failed, so I did it another way."),
        ))
        assertEquals(RunResult.Completed, h.loop.run(1))
        val tool = h.tasks.messages.single { it.role == MessageRole.TOOL }
        assertTrue(tool.content!!, tool.content!!.contains("tool exploded"))
        assertEquals(ToolCallStatus.ERROR, h.tasks.toolCalls.single().status)
        // The second model call saw the error as a role=tool message.
        assertTrue(h.sent[1].any { it.role == "tool" && it.content.orEmpty().contains("tool exploded") })
        assertEquals(TaskStatus.COMPLETED, h.tasks.task.status)
        // Completions don't notify by default.
        assertTrue(h.notifications.added.isEmpty()); assertEquals(0, h.systemNotified)
    }

    @Test fun `empty reply gets one nudge, then a fallback message and a visible pause`() = runTest {
        val h = Harness(listOf(
            reply(null, listOf(ParsedToolCall("c1", "boom", "{}")), finish = "tool_calls"),
            reply(""), reply(null),
        ))
        assertEquals(RunResult.Stopped, h.loop.run(1))
        assertEquals(3, h.sent.size)
        val nudge = h.sent[2].last()
        assertEquals("system", nudge.role)
        assertTrue(nudge.content!!, nudge.content!!.contains("progress report") && nudge.content!!.contains("2 to 4 sentences") &&
            nudge.content!!.contains("The last tool failed: boom:") && nudge.content!!.contains("tool exploded"))
        val fallback = h.tasks.messages.last { it.role == MessageRole.ASSISTANT }
        assertTrue(fallback.content!!, fallback.content!!.contains("tool exploded") && fallback.content!!.contains("Continue"))
        // Summary of the recent tool calls.
        assertTrue(fallback.content!!, fallback.content!!.contains("What I did so far") && fallback.content!!.contains("`boom`"))
        assertEquals(TaskStatus.PAUSED, h.tasks.task.status)
        assertTrue(h.tasks.statusTexts().last().contains("Tap Continue"))
    }

    @Test fun `nudge asks for a visible report, which is an agent message, then the loop keeps working`() = runTest {
        val h = Harness(listOf(reply(null), reply("I opened the page but the button didn't respond. Next I'll try the search."),
            reply(null, listOf(ParsedToolCall("c1", "boom", "{}")), finish = "tool_calls"), reply("Done: here is the answer.")))
        assertEquals(RunResult.Completed, h.loop.run(1))
        assertTrue(h.sent[1].last().content!!.contains("Your last reply was empty"))
        // The report did not end the task: the next call got the continue nudge and ran a tool.
        assertEquals(LoopMessages.CONTINUE_AFTER_REPORT, h.sent[2].last().content)
        val agent = h.tasks.messages.filter { it.role == MessageRole.ASSISTANT && it.kind == MessageKind.NORMAL }.mapNotNull { it.content }
        assertTrue(agent.toString(), agent.any { it.startsWith("I opened the page") })
        assertEquals(1, h.tasks.toolCalls.size)
        assertEquals(4, h.sent.size)
    }

    @Test fun `report plus tool call in one reply continues without an extra nudge`() = runTest {
        val h = Harness(listOf(reply(null), reply("Status: stuck on login; trying again.", listOf(ParsedToolCall("c1", "boom", "{}")), finish = "tool_calls"),
            reply("Final answer.")))
        assertEquals(RunResult.Completed, h.loop.run(1))
        assertEquals(3, h.sent.size)
    }

    @Test fun `system prompt has no internal browser and keeps web_fetch, termux_run, crypto and English sources`() {
        val p = AgentLoop.systemPrompt()
        assertTrue(p.contains("Phone screen (accessibility)") && p.contains("OTHER Android apps"))
        assertTrue(p.contains("web_fetch") && p.contains("There is no internal browser"))
        assertTrue(p.contains("crypto_place_order") && p.contains("OFF by default") && p.contains("Revolut has no public crypto"))
        assertTrue(p.contains("Prefer English-language sources"))
        for (gone in listOf("web_scrape", "web_click", "web_type", "web_session", "web_screenshot", "x_post", "x_scrape", "fb_",
            "reset_browser", "Firefox", "tbp")) assertFalse(gone, p.contains(gone))
        assertTrue(p.contains("termux_run") && p.contains("run_shell"))
        assertTrue(p.contains("web_search first") && p.contains("format=markdown"))
        assertTrue(p.indexOf("web_search") < p.indexOf("web_fetch"))
    }

    @Test fun `memory block is appended to the system prompt`() = runTest {
        val tasks = FakeTasks()
        val sent = mutableListOf<List<ApiMessage>>()
        val loop = AgentLoop(tasks, FakeSettings(5), ToolRegistry(emptyList()), FakeNotifications(),
            model = { m: List<ApiMessage>, _: JsonArray?, _: Long, _: suspend (String) -> Unit -> sent += m; reply("ok") },
            summarize = { _, _, _ -> }, notifyOnFinish = { false }, systemNotify = { _, _, _ -> },
            memoryPrompt = { "Memory (persistent):\n- [#1] The user is called Chris." })
        loop.run(1)
        assertTrue(sent[0][0].content!!.contains("[#1] The user is called Chris."))
    }

    @Test fun `enabled skills are appended to the system prompt and the prompt offers to save skills`() = runTest {
        val dir = java.nio.file.Files.createTempDirectory("skills").toFile()
        val store = com.farrow.app.data.skills.SkillStore(dir)
        store.save("Weekly report", "Build the weekly report", "1. Fetch prices\n2. Chart them")
        val off = store.save("Old way", "", "SECRET-OLD-STEPS")
        store.setEnabled(off.id, false)
        val tasks = FakeTasks()
        val sent = mutableListOf<List<ApiMessage>>()
        val loop = AgentLoop(tasks, FakeSettings(5), ToolRegistry(emptyList()), FakeNotifications(),
            model = { m: List<ApiMessage>, _: JsonArray?, _: Long, _: suspend (String) -> Unit -> sent += m; reply("ok") },
            summarize = { _, _, _ -> }, notifyOnFinish = { false }, systemNotify = { _, _, _ -> },
            skillsPrompt = { store.promptBlock() })
        loop.run(1)
        val sys = sent[0][0].content!!
        assertTrue(sys.contains("## Saved skills") && sys.contains("2. Chart them"))
        assertFalse(sys.contains("SECRET-OLD-STEPS") || sys.contains("old-way"))
        assertTrue(AgentLoop.systemPrompt().contains("offer to save it as a") && AgentLoop.systemPrompt().contains("skill_save"))
        dir.deleteRecursively()
    }

    @Test fun `max steps posts a system message and pauses`() = runTest {
        val h = Harness(listOf(reply(null, listOf(ParsedToolCall("c", "boom", "{}")), finish = "tool_calls")), maxSteps = 3)
        assertEquals(RunResult.Stopped, h.loop.run(1))
        assertEquals(TaskStatus.PAUSED, h.tasks.task.status)
        assertEquals(PauseReason.MAX_STEPS, h.tasks.pausedReason)
        assertTrue(h.tasks.statusTexts().last(), h.tasks.statusTexts().last().contains("max-steps cap (3)") && h.tasks.statusTexts().last().contains("Continue"))
    }

    @Test fun `content filter and truncation are handled explicitly`() = runTest {
        val f = Harness(listOf(reply(null, finish = "content_filter")))
        assertEquals(RunResult.Stopped, f.loop.run(1))
        assertEquals(TaskStatus.PAUSED, f.tasks.task.status)
        assertTrue(f.tasks.statusTexts().last().contains("content filter"))

        val l = Harness(listOf(reply("part one", finish = "length"), reply("part two")))
        assertEquals(RunResult.Completed, l.loop.run(1))
        assertTrue(l.sent[1].last().content!!.contains("cut off"))
    }

    @Test fun `a crashing model call fails visibly`() = runTest {
        val tasks = FakeTasks()
        val loop = AgentLoop(tasks, FakeSettings(5), ToolRegistry(emptyList()), FakeNotifications(),
            model = { _: List<ApiMessage>, _: JsonArray?, _: Long, _: suspend (String) -> Unit -> throw IllegalStateException("kaboom") },
            summarize = { _, _, _ -> }, notifyOnFinish = { false }, systemNotify = { _, _, _ -> })
        assertEquals(RunResult.Stopped, loop.run(1))
        assertEquals(TaskStatus.FAILED, tasks.task.status)
        assertTrue(tasks.statusTexts().last().contains("kaboom"))
    }

    @Test fun `finish notification only when enabled`() = runTest {
        val h = Harness(listOf(reply("done")), notifyFinish = true)
        h.loop.run(1)
        assertEquals(listOf(NotificationType.COMPLETED), h.notifications.added)
        assertEquals(1, h.systemNotified)
    }
}
