package com.verdroid.app.data.memory

import android.content.Context
import com.verdroid.app.data.local.MemoryDao
import com.verdroid.app.data.local.MemoryEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Room-backed [MemoryStore]; every change rewrites files/memory/MEMORY.md. */
@Singleton
class MemoryRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val dao: MemoryDao,
) : MemoryStore {
    private val prefs = context.getSharedPreferences("memory", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    val exportFile: File = File(context.filesDir, "memory/MEMORY.md")

    private val _autoSave = MutableStateFlow(prefs.getBoolean(KEY_AUTO, true))
    val autoSaveFlow: StateFlow<Boolean> = _autoSave
    override val autoSave: Boolean get() = _autoSave.value
    fun setAutoSave(on: Boolean) { prefs.edit().putBoolean(KEY_AUTO, on).apply(); _autoSave.value = on }

    val memories: Flow<List<Memory>> = dao.observeAll().map { l -> l.map(::toModel) }

    private fun toModel(e: MemoryEntity) = Memory(e.id, e.text, MemoryText.parseTags(e.tags), e.createdAt, e.updatedAt, e.chatId)

    override suspend fun all(): List<Memory> = dao.all().map(::toModel)

    override suspend fun save(text: String, tags: List<String>, chatId: Long?): SaveResult = mutex.withLock {
        val t = text.trim().take(MemoryText.MAX_TEXT)
        require(t.isNotBlank()) { "text is empty" }
        val now = System.currentTimeMillis()
        val dup = MemoryText.findDuplicate(all(), t, chatId)
        val res = if (dup != null) {
            dao.update(MemoryEntity(dup.id, if (t.length > dup.text.length) t else dup.text,
                MemoryText.tagsString((dup.tags + tags).distinct()), dup.createdAt, now, dup.chatId))
            SaveResult(dup.id, dup.id)
        } else SaveResult(dao.insert(MemoryEntity(0, t, MemoryText.tagsString(tags), now, now, chatId)), null)
        export(); res
    }

    override suspend fun update(id: Long, text: String, tags: List<String>): Boolean = mutex.withLock {
        val e = dao.get(id) ?: return false
        dao.update(e.copy(text = text.trim().take(MemoryText.MAX_TEXT), tags = MemoryText.tagsString(tags), updatedAt = System.currentTimeMillis()))
        export(); true
    }

    override suspend fun delete(id: Long): Boolean = mutex.withLock { (dao.delete(id) > 0).also { export() } }

    override suspend fun clear(chatId: Long?) { mutex.withLock { if (chatId == null) dao.clearGlobal() else dao.clearChat(chatId); export() } }

    /** Short-term → long-term (chatId null) or into a chat; a long-term duplicate is merged instead. */
    override suspend fun move(id: Long, chatId: Long?): Boolean = mutex.withLock {
        val e = dao.get(id) ?: return false
        val dup = MemoryText.findDuplicate(all().filter { it.id != id }, e.text, chatId)
        if (dup != null) dao.delete(id) else dao.setChat(id, chatId, System.currentTimeMillis())
        export(); true
    }

    /** System-prompt block: long-term (≤ 20 items, ~1.5k tokens) + this chat's short-term notes (~1k tokens). */
    suspend fun promptBlock(chatId: Long? = null): String = MemoryText.promptBlock(all(), autoSave, chatId)

    private suspend fun export() = withContext(Dispatchers.IO) {
        runCatching {
            exportFile.parentFile?.mkdirs()
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            exportFile.writeText(MemoryText.markdown(all(), { fmt.format(java.util.Date(it)) }))
        }
    }

    private companion object { const val KEY_AUTO = "auto_save" }
}
