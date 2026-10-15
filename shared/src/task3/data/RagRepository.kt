package task3.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import task2.data.ModelCatalog
import task2.data.downloadFile
import task2.data.nowMillis
import task2.data.platformCpuThreads
import task2.data.platformFileExists
import task2.data.PromptBuilder
import task2.domain.AiModel
import task2.domain.ChatMessage
import task2.domain.EngineStatus
import task2.llama.LlamaBridge
import task3.db.DocumentEntity
import task3.db.RagDao
import task3.db.RagMessageEntity
import task3.db.SettingEntity
import task3.domain.DocInfo
import task3.domain.LlmChoice
import task3.domain.LlmChoice.Companion.toStored
import task3.domain.ScoredChunk
import task3.llama.EmbedBridge

/** Embedding engine status for the UI. */
sealed interface EmbedState {
    data object Unset : EmbedState
    data class Downloading(val progress: Int) : EmbedState
    data object Loading : EmbedState
    data object Ready : EmbedState
    data class Error(val message: String) : EmbedState
}

/** Honest download progress: totalBytes == null means unknown length, no percentages drawn. */
data class DownloadProgress(val doneBytes: Long, val totalBytes: Long?) {
    val percent: Int? get() = totalBytes?.let { ((doneBytes * 100) / it).toInt().coerceIn(0, 100) }
    val doneMb: Long get() = doneBytes / 1024 / 1024
}

private const val K_LLM = "llm"
private const val K_EMBED = "embed"
private const val K_DEEPSEEK = "deepseek_key"
private const val K_PRESET = "gen_preset"

/**
 * Task 3 SSOT: model settings, documents, index, conversation.
 * Files: llm and embed subdirectories inside modelsDir plus modelsDir/rag.db.
 * No auto-download: load into memory only what is already on disk.
 */
class RagRepository(
    private val genBridge: LlamaBridge,
    private val embBridge: EmbedBridge,
    private val dao: RagDao,
    private val modelsDir: String,
    private val scope: CoroutineScope,
) {
    // --- settings ---
    private val _llmChoice = MutableStateFlow<LlmChoice?>(null)
    val llmChoice: StateFlow<LlmChoice?> = _llmChoice.asStateFlow()

    private val _embedId = MutableStateFlow<String?>(null)
    val embedId: StateFlow<String?> = _embedId.asStateFlow()

    private val _deepSeekKey = MutableStateFlow("")
    val deepSeekKey: StateFlow<String> = _deepSeekKey.asStateFlow()

    // --- Task 4 generation preset (default — Balanced) ---
    private val _preset = MutableStateFlow(task3.domain.GenPreset.DEFAULT)
    val preset: StateFlow<task3.domain.GenPreset> = _preset.asStateFlow()

    // --- statuses ---
    private val _llmStatus = MutableStateFlow<EngineStatus>(EngineStatus.Idle)
    val llmStatus: StateFlow<EngineStatus> = _llmStatus.asStateFlow()

    private val _embedStatus = MutableStateFlow<EmbedState>(EmbedState.Unset)
    val embedStatus: StateFlow<EmbedState> = _embedStatus.asStateFlow()

    /** Download progress per model id (llm and embed share one map). */
    private val _downloading = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloading: StateFlow<Map<String, DownloadProgress>> = _downloading.asStateFlow()

    /** Which files are already on disk. */
    private val _downloadedLlm = MutableStateFlow<Set<String>>(emptySet())
    val downloadedLlm: StateFlow<Set<String>> = _downloadedLlm.asStateFlow()

    private val _downloadedEmbed = MutableStateFlow<Set<String>>(emptySet())
    val downloadedEmbed: StateFlow<Set<String>> = _downloadedEmbed.asStateFlow()

    // --- documents ---
    private val _docs = MutableStateFlow<List<DocInfo>>(emptyList())
    val docs: StateFlow<List<DocInfo>> = _docs.asStateFlow()

    private val _indexProgress = MutableStateFlow<Map<String, Float?>>(emptyMap())
    val indexProgress: StateFlow<Map<String, Float?>> = _indexProgress.asStateFlow()

    // --- chat ---
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private var idSeq = 1L
    private var loadedGenPath: String? = null
    private var loadedEmbPath: String? = null

    val embedModel: EmbedModel? get() = EmbedCatalog.resolve(_embedId.value)

    /** Re-throw OOM and cancellation, everything else becomes an error. */
    private fun rethrowIfFatal(e: Throwable) {
        if (e is OutOfMemoryError || e is kotlinx.coroutines.CancellationException) throw e
    }

    fun llmFile(model: AiModel): String = "$modelsDir/llm/${model.fileName}"
    fun embedFile(model: EmbedModel): String = "$modelsDir/embed/${model.fileName}"

    suspend fun init() {
        _llmChoice.value = LlmChoice.fromStored(dao.getSetting(K_LLM))
        _embedId.value = dao.getSetting(K_EMBED)?.ifBlank { null }
        _deepSeekKey.value = dao.getSetting(K_DEEPSEEK).orEmpty()
        _preset.value = task3.domain.GenPreset.fromId(dao.getSetting(K_PRESET))
        refreshDownloaded()
        runCatching {
            _messages.value = dao.getMessages().map { ChatMessage(idSeq++, mapRole(it.role), it.text) }
        }
        refreshDocs()
        // Background loading of already downloaded models (no re-download): chat is ready faster.
        val choice = _llmChoice.value
        if (choice is LlmChoice.Local) {
            val m = ModelCatalog.resolve(choice.id)
            if (m.id == choice.id && platformFileExists(llmFile(m))) {
                scope.launch { runCatching { ensureLlmLoaded() } }
            }
        }
        val emb = embedModel
        if (emb != null && platformFileExists(embedFile(emb))) {
            scope.launch { runCatching { ensureEmbedLoaded() } }
        }
    }

    private fun refreshDownloaded() {
        _downloadedLlm.value = ModelCatalog.ALL.filter { platformFileExists(llmFile(it)) }.map { it.id }.toSet()
        _downloadedEmbed.value = EmbedCatalog.ALL.filter { platformFileExists(embedFile(it)) }.map { it.id }.toSet()
        // No embedding selected but exactly one file present — cannot pick silently:
        // selection is deliberate, made in Settings.
        if (_embedId.value != null && embedModel == null) {
            scope.launch {
                dao.setSetting(SettingEntity(K_EMBED, ""))
                _embedId.value = null
            }
        }
    }

    // --- LLM selection ---

    suspend fun selectLlm(choice: LlmChoice?) {
        dao.setSetting(SettingEntity(K_LLM, choice.toStored()))
        _llmChoice.value = choice
        if (choice is LlmChoice.Local) {
            val m = ModelCatalog.resolve(choice.id)
            if (platformFileExists(llmFile(m))) {
                scope.launch { runCatching { ensureLlmLoaded() } }
            } else {
                _llmStatus.value = EngineStatus.Idle
            }
        } else {
            _llmStatus.value = EngineStatus.Idle
        }
    }

    suspend fun downloadLlm(model: AiModel) {
        if (_downloading.value.containsKey(model.id)) return
        // Mark immediately: a second tap/radio before the first progress update must not
        // start a parallel resume download into the same .part (byte interleaving).
        _downloading.update { it + (model.id to DownloadProgress(0, null)) }
        // Global status is only for the selected model, otherwise another
        // download's progress shows up on the selected card. Each model's
        // progress is still visible individually via the downloading map.
        val isSelected = { (_llmChoice.value as? LlmChoice.Local)?.id == model.id }
        if (isSelected()) _llmStatus.value = EngineStatus.Downloading(0)
        try {
            downloadFile(model.downloadUrl, llmFile(model)) { done, total ->
                _downloading.update { it + (model.id to DownloadProgress(done, total)) }
                if (isSelected()) {
                    _llmStatus.value = EngineStatus.Downloading(total?.let { ((done * 100) / it).toInt().coerceIn(0, 100) } ?: 0)
                }
            }
            // Size check: the runtime won't open a truncated .litertlm anyway —
            // better to say so immediately and remove the garbage.
            validateModelFile(llmFile(model), model.sizeMb)?.let { problem ->
                runCatching { deleteFile(llmFile(model)) }
                if (isSelected()) _llmStatus.value = EngineStatus.Error(problem)
                return
            }
        } catch (e: Exception) {
            if (isSelected()) _llmStatus.value = EngineStatus.Error("Не скачалась модель: ${e.message}")
            return
        } finally {
            _downloading.update { it - model.id }
        }
        refreshDownloaded()
        if (isSelected()) ensureLlmLoaded(model)
    }

    suspend fun deleteLlm(model: AiModel) {
        runCatching { deleteFile(llmFile(model)) }
        if (loadedGenPath == llmFile(model)) {
            genBridge.close()
            loadedGenPath = null
        }
        refreshDownloaded()
        if ((_llmChoice.value as? LlmChoice.Local)?.id == model.id) {
            // It was the selected one being deleted — clear the selection, otherwise the UI hangs on a missing file.
            selectLlm(null)
        }
    }

    /** Load into memory only, no downloading (missing file is reported outward as an error). */
    suspend fun ensureLlmLoaded(model: AiModel = ModelCatalog.resolve((_llmChoice.value as? LlmChoice.Local)?.id)) {
        val dest = llmFile(model)
        if (!platformFileExists(dest)) {
            _llmStatus.value = EngineStatus.Error("Модель не скачана — скачай её в Настройках")
            return
        }
        // Size was already checked at download time; a corrupt file will fail load below
        // with a clear runtime error.
        if (loadedGenPath != dest || !genBridge.isReady) {
            _llmStatus.value = EngineStatus.LoadingModel
            try {
                genBridge.load(dest, model.defaultCtx, platformCpuThreads())
                loadedGenPath = dest
            } catch (e: Throwable) {
                rethrowIfFatal(e)
                _llmStatus.value = EngineStatus.Error("Не загрузилась в LiteRT: ${e.message}")
                return
            }
        }
        _llmStatus.value = EngineStatus.Ready
    }

    // --- embedding selection ---

    suspend fun selectEmbed(id: String?) {
        val prev = _embedId.value
        if (prev == id) return
        dao.setSetting(SettingEntity(K_EMBED, id.orEmpty()))
        _embedId.value = id
        if (prev != null && id != prev) {
            // Vectors of different dimensionality are incomparable — clear the index honestly.
            dao.clearChunks()
            dao.allDocs().forEach {
                dao.upsertDoc(it.copy(status = "ready", error = ""))
            }
            refreshDocs()
        }
        val emb = embedModel
        if (emb != null && platformFileExists(embedFile(emb))) {
            scope.launch { runCatching { ensureEmbedLoaded() } }
        } else {
            _embedStatus.value = EmbedState.Unset
        }
    }

    suspend fun downloadEmbed(model: EmbedModel) {
        if (_downloading.value.containsKey(model.id)) return
        // Mark immediately: see downloadLlm.
        _downloading.update { it + (model.id to DownloadProgress(0, null)) }
        // Global status is only for the selected model, otherwise another
        // download's progress shows up on the selected card (bug from the screenshot).
        val isSelected = { _embedId.value == model.id }
        if (isSelected()) _embedStatus.value = EmbedState.Downloading(0)
        try {
            downloadFile(model.downloadUrl, embedFile(model)) { done, total ->
                _downloading.update { it + (model.id to DownloadProgress(done, total)) }
                if (isSelected()) {
                    _embedStatus.value = EmbedState.Downloading(total?.let { ((done * 100) / it).toInt().coerceIn(0, 100) } ?: 0)
                }
            }
            // Size check: the runtime won't open a truncated bundle anyway —
            // better to say so immediately and remove the garbage.
            validateModelFile(embedFile(model), model.sizeMb)?.let { problem ->
                runCatching { deleteFile(embedFile(model)) }
                if (isSelected()) _embedStatus.value = EmbedState.Error(problem)
                return
            }
        } catch (e: Exception) {
            if (isSelected()) _embedStatus.value = EmbedState.Error("Не скачалась: ${e.message}")
            return
        } finally {
            _downloading.update { it - model.id }
        }
        refreshDownloaded()
        if (_embedId.value == model.id) ensureEmbedLoaded(model)
    }

    suspend fun deleteEmbed(model: EmbedModel) {
        runCatching { deleteFile(embedFile(model)) }
        if (loadedEmbPath == embedFile(model)) {
            embBridge.close()
            loadedEmbPath = null
        }
        refreshDownloaded()
        if (_embedId.value == model.id) {
            // Clear the selection but do NOT touch the index: after re-downloading
            // the same model the chunks become usable again (no reindexing needed).
            dao.setSetting(SettingEntity(K_EMBED, ""))
            _embedId.value = null
            _embedStatus.value = EmbedState.Unset
        }
    }

    suspend fun ensureEmbedLoaded(model: EmbedModel = embedModel ?: EmbedCatalog.DEFAULT) {
        val dest = embedFile(model)
        if (!platformFileExists(dest)) {
            _embedStatus.value = EmbedState.Error("Модель не скачана — скачай её в Настройках")
            return
        }
        // Size was already checked at download time; a corrupt file will fail load below
        // with a clear runtime error.
        if (loadedEmbPath != dest || !embBridge.isReady) {
            _embedStatus.value = EmbedState.Loading
            try {
                embBridge.load(dest, platformCpuThreads())
                loadedEmbPath = dest
                // Catalog dim is for the UI label; ground truth is embBridge.dim
                // (each chunk stores its own dim, search filters by fact).
            } catch (e: Throwable) {
                rethrowIfFatal(e)
                _embedStatus.value = EmbedState.Error("Не загрузилась: ${e.message}")
                return
            }
        }
        _embedStatus.value = EmbedState.Ready
    }

    // --- DeepSeek key ---

    suspend fun saveDeepSeekKey(key: String) {
        dao.setSetting(SettingEntity(K_DEEPSEEK, key.trim()))
        _deepSeekKey.value = key.trim()
    }

    // --- generation preset ---

    suspend fun selectPreset(preset: task3.domain.GenPreset) {
        dao.setSetting(SettingEntity(K_PRESET, preset.id))
        _preset.value = preset
    }

    // --- documents ---

    suspend fun refreshDocs() {
        runCatching {
            val chunks = dao.allChunks().groupBy { it.source }
            val entries = dao.allDocs().associateBy { it.source }
            _docs.value = (chunks.keys + entries.keys).map { src ->
                val list = chunks[src].orEmpty()
                val e = entries[src]
                DocInfo(
                    source = src,
                    title = list.firstOrNull()?.title ?: e?.title ?: src.substringAfterLast("/"),
                    chunks = list.size,
                    chars = list.sumOf { it.text.length.toLong() },
                    active = e?.active ?: true,
                    indexing = e?.status == "indexing",
                    error = e?.takeIf { it.status == "error" }?.error?.takeIf { it.isNotBlank() },
                )
            }.sortedBy { it.title.lowercase() }
        }
    }

    suspend fun addWikiDoc(input: String) {
        val emb = embedModel
        if (emb == null || !platformFileExists(embedFile(emb))) {
            return
        }
        val doc = try {
            WikiLoader.fetch(input)
        } catch (e: Exception) {
            dao.upsertDoc(
                DocumentEntity(
                    source = "wiki:$input",
                    title = input,
                    status = "error",
                    error = e.message ?: "ошибка загрузки",
                    updatedAt = nowMillis(),
                ),
            )
            refreshDocs()
            return
        }
        if (_indexProgress.value.containsKey(doc.source)) return
        dao.upsertDoc(
            DocumentEntity(doc.source, doc.title, "indexing", "", true, nowMillis()),
        )
        refreshDocs()
        _indexProgress.update { it + (doc.source to null) }
        try {
            try {
                ensureEmbedLoaded(emb)
            } catch (e: Throwable) {
                rethrowIfFatal(e)
                throw IllegalStateException("нет связи с embedding-моделью: ${e.message}")
            }
            if (_embedStatus.value !is EmbedState.Ready) {
                throw IllegalStateException("embedding-модель не готова")
            }
            // Chunks are shorter than the embedding model's context (n_ctx=512): Russian text
            // tokenizes densely (~2-3 chars per token), 1000 chars
            // regularly don't fit and native truncates the tail. 1000 chars ~ 400 tokens.
            // Slicing: article -> sections -> paragraphs tagged with section.
            val chunks = StructureChunker(maxChars = 1000).chunk(doc)
            if (chunks.isEmpty()) throw IllegalStateException("текст не нарезался на чанки")
            // Reuse vectors of unchanged texts.
            val vectors = chunks.map { c ->
                val stored = runCatching { dao.findVectorByText(c.text) }.getOrNull()
                if (stored != null && stored.dim == embBridge.dim) {
                    stored.embedding.toFloatArray()
                } else {
                    null
                }
            }
            val missing = vectors.mapIndexedNotNull { i, v -> if (v == null) i else null }
            val computed = mutableMapOf<Int, FloatArray>()
            var done = 0
            for (batch in missing.chunked(16)) {
                val texts = batch.map { emb.docPrefix + chunks[it].text }
                val embBatch = embBridge.embed(texts)
                batch.forEachIndexed { k, idx -> computed[idx] = embBatch[k] }
                done += batch.size
                val p = done.toFloat() / missing.size.coerceAtLeast(1)
                _indexProgress.update { it + (doc.source to p) }
            }
            val entities = chunks.mapIndexed { i, c ->
                val v = vectors[i] ?: computed[i]!!
                task3.db.ChunkEntity(
                    id = "${c.id}#${emb.id}",
                    source = c.source,
                    title = c.title,
                    section = c.section,
                    ord = c.ord,
                    text = c.text,
                    dim = v.size,
                    embedding = v.toBlob(),
                )
            }
            dao.deleteChunksOf(listOf(doc.source))
            dao.upsertChunks(entities)
            dao.upsertDoc(DocumentEntity(doc.source, doc.title, "ready", "", true, nowMillis()))
        } catch (e: Throwable) {
            rethrowIfFatal(e)
            dao.upsertDoc(
                DocumentEntity(doc.source, doc.title, "error", e.message ?: "ошибка", true, nowMillis()),
            )
        } finally {
            _indexProgress.update { it - doc.source }
            refreshDocs()
        }
    }

    suspend fun setDocActive(source: String, active: Boolean) {
        if (_indexProgress.value.containsKey(source)) return
        runCatching {
            val all = dao.allDocs()
            val prev = all.firstOrNull { it.source == source } ?: return
            dao.upsertDoc(prev.copy(active = active))
            refreshDocs()
        }
    }

    suspend fun deleteDoc(source: String) {
        if (_indexProgress.value.containsKey(source)) return
        dao.deleteChunksOf(listOf(source))
        dao.deleteDocs(listOf(source))
        refreshDocs()
    }

    suspend fun docPreview(source: String, maxChars: Int = 3000): String {
        val chunks = runCatching { dao.chunksOf(source) }.getOrDefault(emptyList())
        if (chunks.isEmpty()) return "(текст недоступен)"
        return chunks.sortedBy { it.ord }.joinToString("\n\n") { it.text }.take(maxChars)
    }

    // --- chat ---

    suspend fun send(prompt: String) {
        val clean = prompt.trim()
        if (clean.isEmpty() || _llmStatus.value is EngineStatus.Generating) return
        val choice = _llmChoice.value ?: return

        val userMsg = ChatMessage(idSeq++, ChatMessage.Role.USER, clean)
        val draftId = idSeq++
        _messages.update { it + userMsg + ChatMessage(draftId, ChatMessage.Role.ASSISTANT, "") }
        runCatching {
            dao.insertMessage(task3.db.RagMessageEntity(role = "USER", text = clean, createdAt = nowMillis()))
        }
        _llmStatus.value = EngineStatus.Generating
        try {
            val history = _messages.value.dropLast(2)
            // Phase 2: small-talk — free-form answer without retrieval
            // (don't touch EmbeddingGemma, don't feed junk context).
            val context = if (QueryRouter.isSmallTalk(clean)) emptyList() else retrieve(clean)
            val answer = when (choice) {
                is LlmChoice.DeepSeek -> answerCloud(history, clean, context)
                is LlmChoice.Local -> answerLocal(ModelCatalog.resolve(choice.id), history, clean, context)
            }
            _messages.update { list ->
                list.map { if (it.id == draftId) it.copy(text = answer) else it }
            }
            runCatching {
                dao.insertMessage(task3.db.RagMessageEntity(role = "ASSISTANT", text = answer, createdAt = nowMillis()))
            }
            _llmStatus.value = EngineStatus.Ready
        } catch (e: kotlinx.coroutines.CancellationException) {
            genBridge.cancel()
            _llmStatus.value = EngineStatus.Ready
        } catch (e: Throwable) {
            rethrowIfFatal(e)
            _llmStatus.value = EngineStatus.Error("Генерация упала: ${e.message}")
            _messages.update { list ->
                list.map {
                    if (it.id == draftId) it.copy(text = "Ошибка: ${e.message}") else it
                }
            }
        }
    }

    fun cancel() {
        genBridge.cancel()
        _llmStatus.value = EngineStatus.Ready
    }

    suspend fun clearChat() {
        _messages.value = emptyList()
        runCatching { dao.clearMessages() }
    }

    /**
     * Phase 1: score-gating. Without a threshold, an out-of-domain query ("thrushes" with a
     * crow database) got 5 junk chunks and a forced fallback.
     * Start 0.55 — tune on 3-5 queries for the Task 4 video.
     * _lastMaxScore is diagnostics for the video/demo (shows the preset matters,
     * and thrushes are simply out of the database).
     */
    companion object {
        const val MIN_SCORE: Float = 0.55f
    }

    private val _lastMaxScore = MutableStateFlow<Float?>(null)
    val lastMaxScore: StateFlow<Float?> = _lastMaxScore.asStateFlow()

    private suspend fun retrieve(query: String, topK: Int = 5): List<ScoredChunk> {
        val emb = embedModel ?: return emptyList()
        if (_embedStatus.value !is EmbedState.Ready) {
            runCatching { ensureEmbedLoaded(emb) }
            if (_embedStatus.value !is EmbedState.Ready) return emptyList()
        }
        val activeDocs = dao.allDocs().filter { it.active }.map { it.source }.toSet()
        val all = dao.allChunks().filter { it.source in activeDocs && it.dim == embBridge.dim }
        if (all.isEmpty()) return emptyList()
        val q = embBridge.embed(listOf(emb.queryPrefix + query)).first()
        val scored = cosineTopK(
            q,
            all.mapIndexed { i, e -> i to e.embedding.toFloatArray() },
            topK,
        )
        _lastMaxScore.value = scored.maxOfOrNull { it.second }
        // Gate 1: cosine below threshold — irrelevant, return empty.
        val above = scored.filter { it.second >= MIN_SCORE }
        if (above.isEmpty()) return emptyList()
        val hits = above.map { (i, s) ->
            val e = all[i]
            ScoredChunk(e.title, e.section, e.source, e.text, s)
        }
        // Gate 2 (hybrid-lite without a new model): no query stem
        // found in the top-hit texts — also a miss (crows vs thrushes).
        if (!hasTokenOverlap(query, hits)) return emptyList()
        return hits
    }

    /**
     * Cheap keyword check on top of dense retrieval: query stems of length 5+
     * against lowercased hit texts. No overlap — the query is not about the database.
     */
    private fun hasTokenOverlap(query: String, hits: List<ScoredChunk>): Boolean {
        val stems = query.lowercase()
            .split(Regex("[^а-яa-zё]+"))
            .filter { it.length >= 4 }
            .map { it.take(5) }
            .toSet()
        if (stems.isEmpty()) return true
        return hits.any { h ->
            val text = h.text.lowercase()
            stems.any { s -> s in text }
        }
    }

    private suspend fun answerLocal(
        model: AiModel,
        history: List<ChatMessage>,
        query: String,
        context: List<ScoredChunk>,
    ): String {
        ensureLlmLoaded(model)
        if (_llmStatus.value !is EngineStatus.Ready && _llmStatus.value !is EngineStatus.Generating) {
            throw IllegalStateException("локальная модель не готова")
        }
        // A small model (KV 1280–4096) won't fit the full context —
        // cut harder than for the cloud. LiteRT applies the template itself.
        // Empty context (no documents / small-talk) — free-form answer
        // without RAG constraints, otherwise "hello" hit "not in the documents".
        val preset = _preset.value
        val system: String
        val user: String
        if (context.isEmpty()) {
            // Phase 4: empty, but documents exist (= out-of-domain, not small-talk) —
            // hint the model at the database topics so the fallback suggests instead of dryly refusing.
            val topics = _docs.value.take(5).map { it.title }.filter { it.isNotBlank() }
            val hint = if (topics.isEmpty()) "" else
                " В локальной базе есть документы: ${topics.joinToString("; ")}. " +
                "Если вопрос не по ним — ответь свободно по общим знаниям, " +
                "а в конце одним предложением предложи спросить про документы из списка."
            system = PromptBuilder.chatSystem() + " " + preset.styleHint + hint
            user = PromptBuilder.chatUser(history = history.takeLast(6), userPrompt = query)
        } else {
            system = PromptBuilder.ragSystem(context.size) + " " + preset.styleHint
            user = PromptBuilder.chatUser(
                history = history.takeLast(6),
                userPrompt = "Контекст:\n${contextBlock(context, perHit = 600)}\n\nВопрос: $query",
            )
        }
        var acc = ""
        _llmStatus.value = EngineStatus.Generating
        genBridge.generateChat(system, user, preset.toGenParams()).collect { delta -> acc += delta }
        val text = PromptBuilder.cleanReply(acc).ifEmpty { acc.trim() }.ifEmpty { "(пустой ответ)" }
        return text + refsBlock(context)
    }

    private suspend fun answerCloud(
        history: List<ChatMessage>,
        query: String,
        context: List<ScoredChunk>,
    ): String {
        val key = _deepSeekKey.value
        if (key.isBlank()) throw IllegalStateException("Нет API-ключа DeepSeek — задай его в Настройках")
        val system = if (context.isEmpty()) {
            "Ты полезный ассистент."
        } else {
            PromptBuilder.ragSystem(context.size)
        }
        val user = if (context.isEmpty()) query else "Контекст:\n${contextBlock(context)}\n\nВопрос: $query"
        val hist = history.takeLast(12).map {
            (if (it.role == ChatMessage.Role.USER) "user" else "assistant") to it.text
        }
        return when (val r = DeepSeekClient.complete(system, user, hist, key)) {
            is DeepSeekResult.Ok -> r.text + refsBlock(context)
            is DeepSeekResult.HttpError -> "DeepSeek HTTP ${r.code}: ${r.detail}"
            is DeepSeekResult.NetworkError -> "DeepSeek: ${r.detail}"
        }
    }

    private fun contextBlock(context: List<ScoredChunk>, perHit: Int = 1200): String =
        context.mapIndexed { i, h ->
            "[${i + 1}] ${h.title}" +
                (if (h.section.isNotBlank()) " / ${h.section}" else "") +
                ":\n${h.text.take(perHit)}"
        }.joinToString("\n\n")

    private fun refsBlock(context: List<ScoredChunk>): String {
        if (context.isEmpty()) return ""
        return "\n\nИсточники:\n" + context.mapIndexed { i, h ->
            "[${i + 1}] ${h.title}" + (if (h.section.isNotBlank()) " / ${h.section}" else "")
        }.joinToString("\n")
    }

    private fun mapRole(role: String): ChatMessage.Role =
        if (role == "ASSISTANT") ChatMessage.Role.ASSISTANT else ChatMessage.Role.USER
}
