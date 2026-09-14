package com.textnote.app.data

import android.content.Context
import android.net.Uri
import com.textnote.app.core.Syntax
import org.json.JSONObject

/**
 * 每个文件手动指定的语法（覆盖按扩展名的自动识别）。
 *
 * 为什么必须持久化：手动切换最常发生在扩展名认不出来的文件上——`.log`、无扩展名的配置、
 * 内容其实是 JSON 的 `.txt`。这类文件如果每次打开都要重选一遍，这个功能等于没有。
 *
 * 按 **Uri** 记而不是按扩展名：按扩展名会把「这一个 .log 是 JSON」推广到所有 .log，
 * 那不是用户要的，而且事后无法只对其中一份撤销。
 *
 * 与 [SettingsRepository] 分开存：那里是全局唯一的一份设置，这里是每文件一条、条数无上限，
 * 混在一起的话两边的清理策略会互相打架。
 */
class SyntaxOverrides(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 这个文件上次手动选的语法 id，没有记录返回 null（＝ 交给自动识别）。
     *
     * 返回的是 **id 原样**，即使它已经不在 `SyntaxRegistry.all` 里（比如将来删掉了一门语言）。
     * 在这里静默丢掉返回 null 会变成「用户明明选过，却悄悄退回自动」，而未知 id 交给调用方
     * 退化成自动，界面上还能看到语法名——后者更容易被发现。
     */
    @Synchronized
    fun get(uri: Uri): String? = runCatching {
        val raw = prefs.getString(KEY_MAP, null) ?: return null
        JSONObject(raw).optString(uri.toString()).takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** 记下这个文件的手动选择 */
    @Synchronized
    fun set(uri: Uri, syntax: Syntax) {
        // put 已存在的 key 不会改变它在 LinkedHashMap 里的位置，所以「最近写过」不是自动成立的。
        // 这里先删再写，让淘汰顺序仍然反映最后使用时间。
        val obj = load()
        val key = uri.toString()
        obj.remove(key)
        obj.put(key, syntax.id)
        save(trim(obj))
    }

    /** 清掉这个文件的手动选择，回到自动识别 */
    @Synchronized
    fun clear(uri: Uri) {
        val obj = load()
        obj.remove(uri.toString())
        save(obj)
    }

    private fun load(): JSONObject = runCatching {
        JSONObject(prefs.getString(KEY_MAP, null) ?: "{}")
    }.getOrDefault(JSONObject())

    private fun save(obj: JSONObject) {
        prefs.edit().putString(KEY_MAP, obj.toString()).apply()
    }

    /**
     * 超过上限就丢掉**最早**写入的那些。
     *
     * 「最早」= 插入序的前几个：`org.json.JSONObject` 底层是 LinkedHashMap，`keys()` 按插入序
     * 返回。没有这个上限的话，这张表会随着打开文件的次数无限长，而它每次读写都要整体序列化
     * 成一个字符串——与 `DocumentRepository` 的最近列表是同一个问题。
     */
    private fun trim(obj: JSONObject): JSONObject {
        if (obj.length() <= MAX_ENTRIES) return obj
        val keys = ArrayList<String>()
        val it = obj.keys()
        while (it.hasNext()) keys.add(it.next())
        val out = JSONObject()
        keys.takeLast(MAX_ENTRIES).forEach { k -> out.put(k, obj.optString(k)) }
        return out
    }

    private companion object {
        const val PREFS = "syntax_overrides"
        const val KEY_MAP = "by_uri_json"
        const val MAX_ENTRIES = 200
    }
}
