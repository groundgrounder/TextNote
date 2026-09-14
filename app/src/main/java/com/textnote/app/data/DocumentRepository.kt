package com.textnote.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.format.DateFormat
import com.textnote.app.R
import com.textnote.app.core.EditorLimits
import com.textnote.app.core.LineEnding
import com.textnote.app.core.LineEndings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一次完整的读取结果：正文（已归一化成 LF）、编码、原始行尾 */
data class LoadedDocument(
    val text: String,
    val encoding: DocumentEncoding,
    val lineEnding: LineEnding,
    val mixedEndings: Boolean,
)

/**
 * 打开文档的三种结果。
 *
 * 用 sealed 而不是「可空 + 布尔」，是因为这几种情况要给用户**完全不同的交代**：
 * 读不到是权限问题（去重新授权），太大是能力边界（换编辑器或等后续版本）。
 * 混成一个 null 就只能显示一句含糊的「打不开」，用户不知道该怎么办。
 */
sealed interface OpenResult {

    /**
     * 打开成功。[readOnly] 表示这份文本只能看不能编辑——体积超过了
     * [com.textnote.app.core.EditorLimits.OPEN_CHARS]，编排器每次改动都要重排全文，
     * 在这个体积下会卡到没法用；而按行懒加载的只读渲染器不受体积影响（实测 8MB 仍然平坦）。
     */
    data class Loaded(val document: LoadedDocument, val readOnly: Boolean = false) : OpenResult

    /** 读不到：无权限 / 文件已被移动删除 */
    data object Unavailable : OpenResult

    /**
     * 大得读不动，不开。
     *
     * 只有一种情况会走到这里：文件超过 [com.textnote.app.core.EditorLimits.READ_BYTES]。
     * 那不只是 UI 的事，是**内存安全问题**——不设上限的话，用户点一个几百 MB 的文件
     * 会把内容整个读进内存然后崩掉。所以 [bytes] 就是真正的大小（provider 报得出时），
     * 报不出来时退回上限，两种情况下界面都只能说「大于」。
     */
    data class TooLarge(val bytes: Long) : OpenResult
}

/**
 * 最近打开列表中的一项。
 *
 * 只有首页真正要显示的东西。曾经这里还带一个 `writable`，但没有任何界面用它——
 * 而为了填它，[recentDocuments] 要给列表里**每一项**多发一次跨进程的
 * `checkUriPermission`。列表上限 20 条，也就是每次刷新白打 20 次 IPC。
 * 写权限在打开文档时（`EditorViewModel.open`）才问，那里是真正需要它的地方。
 */
data class DocumentMeta(
    val uri: String,
    val name: String,
    val snippet: String,
    val accessible: Boolean,
    val openedAt: Long,
)

/**
 * 文件的体积与最后修改时间。用于**回到前台时**判断文件是否被别的应用改过。
 *
 * 为什么两个字段都要：[modifiedAt] 是主判据，但并非所有 provider 都报；[size] 是兜底。
 * 两个都拿不到（-1）时调用方应当放弃检测，而不是假装「没变化」——把「问不出来」和
 * 「没变」混为一谈，是这个功能最容易出的错。
 */
data class FileState(val size: Long, val modifiedAt: Long) {

    /** 这个 provider 报得出修改时间吗 */
    val hasModifiedAt: Boolean get() = modifiedAt > 0
}

/**
 * 文档仓库：基于 SAF（Storage Access Framework）读写任意位置的文本文件。
 *
 * 关于权限：只有带 FLAG_GRANT_PERSISTABLE_URI_PERMISSION 的授权才能跨进程重启保留。
 * 系统文档选择器会给，文件管理器「打开方式」或其他应用分享过来的 content:// Uri 大多不给
 * （FileProvider、MediaStore 均不支持持久化授权），这类授权随进程结束一起消失。
 */
class DocumentRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("recent_docs", Context.MODE_PRIVATE)

    /** 时间格式按界面语言缓存；locale 变化时重建，避免每个列表项都新建 formatter */
    private var timeFormat: SimpleDateFormat? = null
    private var formattedLocale: Locale? = null

    // ---------- 读写 ----------

    /**
     * 打开文档：读取、探测编码与行尾、把行尾归一化成 LF，并决定「可编辑 / 只读 / 不开」。
     *
     * 三档的边界都是实测定的（见 `core/EditorLimits.kt` 与 `tools/kernel_bench.py`）：
     * - 超过 [EditorLimits.READ_BYTES]：**不开**。这是内存安全问题，不是体验问题；
     * - 超过 [EditorLimits.OPEN_CHARS]：**只读打开**。编排器在这个体积下一次输入要等一秒以上，
     *   而只读渲染器的代价与体积无关（实测 188K 与 8MB 都是 30ms 上下）；
     * - 其余：正常可编辑。
     *
     * 读取用 [readAtMost] 而不是 `readBytes()`：后者遇到几百 MB 的文件会先把内容全读进内存
     * 再崩掉，那是个纯粹的崩溃风险，跟用户体验无关。
     */
    suspend fun openDocument(uri: Uri): OpenResult = withContext(Dispatchers.IO) {
        runCatching {
            val input = context.contentResolver.openInputStream(uri)
                ?: return@runCatching OpenResult.Unavailable
            // 多读 1 字节：这样才能区分「正好等于上限」和「被截断」
            val bytes = input.use { readAtMost(it, EditorLimits.READ_BYTES + 1) }
            if (bytes.size > EditorLimits.READ_BYTES) {
                // 用 provider 声明的真实体积，而不是把上限当体积报出去——报「大于 4MB」
                // 而它其实有 800MB，用户会以为换个工具裁到 5MB 就能打开。拿不到声明值时才退回上限。
                return@runCatching OpenResult.TooLarge(
                    bytes = declaredSize(uri) ?: EditorLimits.READ_BYTES.toLong(),
                )
            }
            val decoded = TextEncoding.decode(bytes)
            val text = LineEndings.normalize(decoded.text)
            OpenResult.Loaded(
                document = LoadedDocument(
                    text = text,
                    encoding = decoded.encoding,
                    lineEnding = LineEndings.detect(decoded.text),
                    mixedEndings = LineEndings.isMixed(decoded.text),
                ),
                readOnly = text.length > EditorLimits.OPEN_CHARS,
            )
        }.getOrElse { OpenResult.Unavailable }
    }

    /** 最多读 [limit] 字节；读到就停，不多读一个字节 */
    private fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (total < limit) {
            val read = input.read(buffer, 0, minOf(buffer.size, limit - total))
            if (read < 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return out.toByteArray()
    }

    /**
     * 写回原位置。编码与行尾都沿用打开时的那一组，否则「打开看一眼再保存」就会把
     * GBK 文件改写成 UTF-8、把 CRLF 文件改写成 LF，diff 里全是不可逆的噪声。
     */
    suspend fun saveDocument(
        uri: Uri,
        content: String,
        encoding: DocumentEncoding,
        lineEnding: LineEnding,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = TextEncoding.encode(LineEndings.restore(content, lineEnding), encoding)
            // "wt" 是截断写；部分 provider 不支持，回退到 "w"
            val truncated = runCatching {
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } != null
            }.getOrDefault(false)
            if (truncated) {
                true
            } else {
                context.contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) } != null
            }
        }.getOrDefault(false)
    }

    /**
     * 读前 limit 字节做摘要与可访问性探测；失败返回 null。
     * 走 [TextEncoding.decodeTruncated] 而不是直接按 UTF-8 解，否则非 UTF-8 文档的摘要
     * 会和编辑器里显示的内容对不上。
     */
    fun probe(uri: Uri, limit: Int = 512): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(limit)
            val n = input.read(buf)
            if (n > 0) TextEncoding.decodeTruncated(buf.copyOf(n)).text else ""
        }
    }.getOrNull()

    /** 查询显示名。优先问 provider；查不到时回退到最近列表，最后才是 Uri 最后一段。 */
    fun displayName(uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    cursor.getString(idx)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
        load().firstOrNull { it.uri == uri.toString() }?.name
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return uri.lastPathSegment?.substringAfterLast('/') ?: context.getString(R.string.untitled)
    }

    /**
     * 问 provider 要文件体积。**只用来把「太大」这个结论说准，不是拦截手段**。
     *
     * 真正的 OOM 防护在 [openDocument] 里：那条读取路径按字节上限截断（`readAtMost`），
     * 多读的那 1 个字节专门用来区分「正好等于上限」和「被截断」。不能靠先问体积再决定读不读——
     * provider 未必报得出来，而一旦退化成「先读进来再判断」就已经晚了：整段字节加解码后的
     * String 是两份内存，一个 50MB 的文件足够把进程直接 OOM 掉。
     *
     * 那为什么还留着：被拒绝打开时界面要说清「这个文件有多大」。provider 报不出来时返回 null，
     * 调用方退回显示读取上限（此时界面只能说「大于」，见 `OpenResult.TooLarge` 的说明）。
     */
    fun declaredSize(uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) {
                cursor.getLong(idx)
            } else {
                null
            }
        }
    }.getOrNull()

    // ---------- 外部改动检测 ----------

    /**
     * 查文件的体积与最后修改时间。两个都问不出来返回 null。
     *
     * 列名不统一是这里的主要麻烦：SAF 的 DocumentsProvider 用 `last_modified`（**毫秒**），
     * MediaStore 用 `date_modified`（**秒**）。
     *
     * **必须一列一列分开查**：把两个列名一起塞进 projection 是不行的——MediaStore 背后是
     * SQL，遇到自己不认识的 `last_modified` 会直接抛异常（"no such column"），
     * 于是什么都拿不到。
     *
     * ⚠️ **这个结果不能单独作为「文件没变」的证据。** 实测：应用没有读媒体权限时，
     * MediaStore 的 `query` 会返回**空 cursor**（不抛异常、不报错），看起来就像文件不存在；
     * 而 adb shell 查同一个 Uri 却查得到。也就是说 provider 报不报这些列，取决于权限与
     * provider 实现，不是文件本身。判据要落在内容上，见 [EditorViewModel.checkExternalChange]。
     */
    fun fileState(uri: Uri): FileState? {
        val size = queryLong(uri, OpenableColumns.SIZE)
        val modified = queryModifiedAt(uri)
        if ((size ?: -1L) < 0 && modified < 0) return null
        return FileState(size ?: -1L, modified)
    }

    /**
     * 修改时间，毫秒。拿不到返回 -1。
     *
     * 先按 SAF 的列名试，再按 MediaStore 的试：两个 provider 只会认其中一个。
     */
    private fun queryModifiedAt(uri: Uri): Long {
        queryLong(uri, DocumentsContract.Document.COLUMN_LAST_MODIFIED)?.let { return it }
        // MediaColumns.DATE_MODIFIED 要求 API 29，写死字面量省掉版本分支；它报的是秒
        queryLong(uri, "date_modified")?.let { return it * 1000L }
        return -1L
    }

    /** 查单个 long 列。列不存在 / 值为空 / 整个查询失败都返回 null */
    private fun queryLong(uri: Uri, column: String): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
        }
    }.getOrNull()

    // ---------- 权限持久化 ----------

    /** 是否已持有该 Uri 的持久化读权限（重启后依然有效） */
    fun hasPersistedPermission(uri: Uri): Boolean = context.contentResolver
        .persistedUriPermissions
        .any { it.uri == uri && it.isReadPermission }

    /** 申请持久化权限，返回是否拿到。部分来源的 Uri 系统不允许持久化。 */
    fun persistPermission(uri: Uri): Boolean {
        if (hasPersistedPermission(uri)) return true
        val resolver = context.contentResolver
        // 只授了读权限时，带写标志调用会抛 SecurityException，退化后重试
        runCatching {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure {
            runCatching {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        return hasPersistedPermission(uri)
    }

    /** 当前是否对该文档有写权限（只读打开时保存一定失败，需要提示用户） */
    suspend fun canWrite(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.checkUriPermission(
                uri,
                Process.myPid(),
                Process.myUid(),
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            ) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(true) // 判断不了时按可写处理，避免误报只读
    }

    // ---------- 最近打开列表 ----------

    /** 把 Uri 记入最近列表（置顶） */
    @Synchronized
    fun addToRecents(uri: Uri) {
        val key = uri.toString()
        val list = load()
        val old = list.firstOrNull { it.uri == key }
        val entry = Entry(
            uri = key,
            name = old?.name?.ifBlank { null } ?: displayName(uri),
            time = System.currentTimeMillis(),
        )
        // 只保留最近若干条。存档原本只增不减，而它每次读写都要整体序列化成 JSON，
        // 用久了会变成一份每次进首页都要反复解析的长字符串。
        save((list.filterNot { it.uri == key } + entry).takeLast(MAX_STORED_RECENTS))
    }

    /** 重新授权后，用新 Uri 替换旧条目（同一份文件在不同来源下 Uri 不同） */
    @Synchronized
    fun replaceRecent(oldUriString: String, newUri: Uri) {
        if (oldUriString == newUri.toString()) {
            addToRecents(newUri)
            return
        }
        val list = load()
        val old = list.firstOrNull { it.uri == oldUriString }
        val entry = Entry(
            uri = newUri.toString(),
            name = displayName(newUri).ifBlank { old?.name.orEmpty() },
            time = old?.time ?: System.currentTimeMillis(),
        )
        save(list.filterNot { it.uri == oldUriString || it.uri == entry.uri } + entry)
    }

    /** 从最近列表移除（不删除文件本身） */
    @Synchronized
    fun removeFromRecents(uriString: String) {
        save(load().filterNot { it.uri == uriString })
    }

    /**
     * 最近打开列表，按打开时间倒序。
     *
     * 每个条目都要跨进程问 provider 一件事（读摘要，同时用来探测可访问性），
     * 所以**先截断再探测**——否则一个开了几百个文件的列表每次进首页都要发上千次 IPC。
     * 20 条足够覆盖「接着上次继续」的实际使用。
     */
    suspend fun recentDocuments(): List<DocumentMeta> = withContext(Dispatchers.IO) {
        load().sortedByDescending { it.time }.take(MAX_RECENTS).map { entry ->
            val uri = Uri.parse(entry.uri)
            val head = probe(uri)
            DocumentMeta(
                uri = entry.uri,
                name = entry.name.ifBlank { displayName(uri) },
                snippet = head.orEmpty().lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .take(2)
                    .joinToString("  ")
                    .take(80),
                accessible = head != null,
                openedAt = entry.time,
            )
        }
    }

    /** 格式化打开时间。日期格式由当前界面语言的 locale 决定。 */
    fun formatTime(epochMillis: Long): String {
        if (epochMillis <= 0) return ""
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        if (timeFormat == null || formattedLocale != locale) {
            timeFormat = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, TIME_SKELETON), locale)
            formattedLocale = locale
        }
        return timeFormat!!.format(Date(epochMillis))
    }

    // ---------- 存取 ----------

    private data class Entry(val uri: String, val name: String, val time: Long)

    @Synchronized
    private fun load(): List<Entry> {
        val raw = prefs.getString(KEY_DOCS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val uri = obj.optString(FIELD_URI)
                if (uri.isBlank()) {
                    null
                } else {
                    Entry(
                        uri = uri,
                        name = obj.optString(FIELD_NAME),
                        time = obj.optLong(FIELD_TIME, 0L),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun save(entries: List<Entry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put(FIELD_URI, e.uri)
                    .put(FIELD_NAME, e.name)
                    .put(FIELD_TIME, e.time),
            )
        }
        prefs.edit().putString(KEY_DOCS, arr.toString()).apply()
    }

    private companion object {
        const val KEY_DOCS = "docs_json"
        const val FIELD_URI = "uri"
        const val FIELD_NAME = "name"
        const val FIELD_TIME = "time"

        /** ICU 骨架：年月日时分，具体排列由 locale 决定（如 zh 为 y/M/d HH:mm） */
        const val TIME_SKELETON = "yMdHm"

        /**
         * 首页最多显示多少条最近记录。限制的理由见 [recentDocuments]（探测是跨进程的），
         * 不是存储限制——存档里可以留更多，只是不一次全列出来。
         */
        const val MAX_RECENTS = 20

        /**
         * 存档里最多留多少条。
         *
         * 比 [MAX_RECENTS] 大，是为了「往下翻还能找到更早的」；但不能无限——
         * 存档是一个整体序列化在 SharedPreferences 里的 JSON 字符串，
         * 每个条目都要读进来解析一遍，几千条之后每次读写都变成纯浪费。
         */
        const val MAX_STORED_RECENTS = 100
    }
}
