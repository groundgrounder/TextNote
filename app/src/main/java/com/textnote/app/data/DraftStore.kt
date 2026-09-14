package com.textnote.app.data

import android.content.Context
import android.net.Uri
import com.textnote.app.core.LineEnding
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** 一份未保存的草稿。正文是编辑器内部的 LF 形态，编码与行尾是写回时要用的那一组。 */
data class Draft(
    val uri: String,
    val name: String,
    val text: String,
    val encoding: DocumentEncoding,
    val lineEnding: LineEnding,
    val updatedAt: Long,
)

/** 列表展示用的草稿摘要，不含正文——列草稿时没必要把 1MB 文本全读进内存 */
data class DraftMeta(
    val uri: String,
    val name: String,
    val updatedAt: Long,
    val chars: Int,
)

/**
 * 草稿快照仓库。
 *
 * 为什么必须有它：Android 没有 macOS 的文档型应用模型。用户按 Home 切走之后，系统随时可以
 * 回收进程，而 `onSaveInstanceState` 有 1MB 的 Bundle 上限（开销大且文本也不该走 Binder）。
 * 唯一可靠的做法是在 `onStop` 把正文同步落到内部存储，下次打开同一份文档时问用户要不要恢复。
 *
 * 关于「内部存储」：草稿写在 `filesDir`，不碰用户的原文件，也不需要任何权限。
 * 正文一律按 UTF-8 存——草稿是自家格式，与用户文件的编码无关；真正写回原文件时才用
 * [Draft.encoding] 转换，所以「打开一个 GBK 文件 → 后台被杀 → 恢复」不会改变文件编码。
 *
 * 关于原子写：先写 `.tmp` 再 rename。直接覆写的话，写到一半进程被杀会留下半截文件，
 * 恢复出来的是被截断的正文，比没有草稿更糟。
 */
class DraftStore(private val context: Context) {

    private val dir = File(context.filesDir, DIR).also { it.mkdirs() }
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 写一份草稿。调用方已在 IO 线程，这里不再切线程——onStop 路径上必须同步完成。 */
    @Synchronized
    fun write(draft: Draft) {
        val key = key(draft.uri)
        val tmp = File(dir, "$key.tmp")
        val target = File(dir, key)
        runCatching {
            tmp.writeText(draft.text, Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                // 某些文件系统 rename 会失败，退化成直接写
                target.writeText(draft.text, Charsets.UTF_8)
                tmp.delete()
            }
        }.onFailure { tmp.delete() }
        saveMeta(loadMeta().filterNot { it.uri == draft.uri } + draft.toMeta())
    }

    /** 读取草稿。读不到（没草稿 / 内容文件被清掉）返回 null。 */
    @Synchronized
    fun read(uri: String): Draft? {
        val meta = loadMeta().firstOrNull { it.uri == uri } ?: return null
        val text = runCatching { File(dir, key(uri)).readText(Charsets.UTF_8) }.getOrNull()
            ?: return null
        return Draft(
            uri = meta.uri,
            name = meta.name,
            text = text,
            encoding = DocumentEncoding(
                charset = runCatching { java.nio.charset.Charset.forName(meta.charset) }
                    .getOrDefault(Charsets.UTF_8),
                withBom = meta.withBom,
            ),
            lineEnding = runCatching { LineEnding.valueOf(meta.lineEnding) }
                .getOrDefault(LineEnding.LF),
            updatedAt = meta.time,
        )
    }

    @Synchronized
    fun delete(uri: String) {
        runCatching { File(dir, key(uri)).delete() }
        saveMeta(loadMeta().filterNot { it.uri == uri })
    }

    /** 待恢复的草稿摘要，按时间倒序 */
    @Synchronized
    fun pending(): List<DraftMeta> = loadMeta()
        .filter { File(dir, key(it.uri)).exists() }
        .sortedByDescending { it.time }
        .map { DraftMeta(it.uri, it.name, it.time, it.chars) }

    /**
     * 清理过期草稿。用户丢弃的、或者原文件已经打不开的草稿会一直留在这里，
     * 不清就是无声的空间泄漏。
     *
     * ## 为什么是 30 天，而且界面上要说出来
     *
     * 草稿是没有写回文件的**用户内容**，静默删掉它和「绝不替用户决定」这条原则是冲突的：
     * 编辑完切走、一个月没回来，正文就没了，而且从来不会有人告诉他。
     *
     * 两件事一起做才算解决：期限放宽到 [RETENTION_MILLIS]（够长，正常使用时碰不到），
     * 并且首页在**快到期时明确标出来**（见 `HomeScreen.DraftRow`）——
     * 于是「被清理」不再是无声事件：要么用户早就被提醒过，要么根本走不到那一步。
     * 仍然要清理，是因为不清就是无声的空间泄漏，每一份草稿都可能是一整篇文本。
     */
    @Synchronized
    fun prune(maxAgeMillis: Long = RETENTION_MILLIS) {
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        val stale = loadMeta().filter { it.time < cutoff }
        stale.forEach { runCatching { File(dir, key(it.uri)).delete() } }
        if (stale.isNotEmpty()) saveMeta(loadMeta() - stale.toSet())
    }

    // ---------- 存取 ----------

    /**
     * 文件名用 Uri 字符串的 MD5。
     *
     * 不用 `hashCode()`：它只有 32 位，且不同 Uri 碰撞的概率在几百个文件量级下已经不可忽略，
     * 撞上了就是两份草稿互相覆盖。也不用原始字符串做文件名——Uri 里有 `/`、`:` 和中文，
     * 当文件名用既可能超长也可能踩到文件系统限制。
     */
    private fun key(uri: String): String = MessageDigest.getInstance("MD5")
        .digest(uri.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun Draft.toMeta(): Meta =
        Meta(uri, name, encoding.charset.name(), encoding.withBom, lineEnding.name, text.length, updatedAt)

    private data class Meta(
        val uri: String,
        val name: String,
        val charset: String,
        val withBom: Boolean,
        val lineEnding: String,
        val chars: Int,
        val time: Long,
    )

    private fun loadMeta(): List<Meta> {
        val raw = prefs.getString(KEY_META, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val uri = o.optString(F_URI)
                if (uri.isBlank()) return@mapNotNull null
                Meta(
                    uri = uri,
                    name = o.optString(F_NAME),
                    charset = o.optString(F_CHARSET, Charsets.UTF_8.name()),
                    withBom = o.optBoolean(F_BOM, false),
                    lineEnding = o.optString(F_LE, LineEnding.LF.name),
                    chars = o.optInt(F_CHARS, 0),
                    time = o.optLong(F_TIME, 0L),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveMeta(list: List<Meta>) {
        val arr = JSONArray()
        list.forEach { m ->
            arr.put(
                JSONObject()
                    .put(F_URI, m.uri)
                    .put(F_NAME, m.name)
                    .put(F_CHARSET, m.charset)
                    .put(F_BOM, m.withBom)
                    .put(F_LE, m.lineEnding)
                    .put(F_CHARS, m.chars)
                    .put(F_TIME, m.time),
            )
        }
        prefs.edit().putString(KEY_META, arr.toString()).apply()
    }

    companion object {
        const val PREFS = "drafts"
        const val DIR = "drafts"
        const val KEY_META = "meta_json"
        const val F_URI = "uri"
        const val F_NAME = "name"
        const val F_CHARSET = "cs"
        const val F_BOM = "bom"
        const val F_LE = "le"
        const val F_CHARS = "chars"
        const val F_TIME = "time"

        private const val DAY_MILLIS = 24L * 60 * 60 * 1000

        /** 草稿保留多久。见 [prune] 里的说明：改这里，首页的「即将过期」提醒会跟着变。 */
        const val RETENTION_MILLIS = 30 * DAY_MILLIS

        /**
         * 草稿离过期还剩多少天（向上取整；已经过期返回 0）。
         *
         * 界面用它决定要不要提醒——与 [prune] 用的是同一个 [RETENTION_MILLIS]，
         * 不会出现「界面说 30 天、实际 7 天就删了」这种错。
         */
        fun daysUntilExpiry(updatedAt: Long, nowMillis: Long = System.currentTimeMillis()): Int {
            val remaining = updatedAt + RETENTION_MILLIS - nowMillis
            if (remaining <= 0) return 0
            return ((remaining + DAY_MILLIS - 1) / DAY_MILLIS).toInt()
        }
    }
}
