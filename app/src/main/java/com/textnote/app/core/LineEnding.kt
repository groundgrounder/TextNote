package com.textnote.app.core

/**
 * 换行符（行尾）类型。
 *
 * 为什么要在意它：类 Unix 用 LF，Windows 用 CRLF，老版 macOS（OS 9 及更早）用 CR。
 * 一份文件里出现哪种，取决于它上次是在哪个系统上被谁写的。编辑器若读进来一律按 LF 处理、
 * 再按 LF 写回去，就会把整个文件的行尾悄悄改掉——diff 里表现为「每一行都被修改」，
 * 实质内容一个字没变。CotEditor 把行尾和编码并列为文档属性，正是这个原因。
 *
 * 处理方式与编码同构：**读入时归一化成 LF，编辑期间只认 LF，写回时还原成原行尾**。
 * 这样 [com.textnote.app.core.LineIndex]、搜索、语法高亮全都只需要处理一种行尾。
 */
enum class LineEnding(val token: String) {
    LF("\n"),
    CRLF("\r\n"),
    CR("\r"),
}

object LineEndings {

    /**
     * 探测文件的行尾：以**第一个**出现的换行符为准。
     *
     * 用「第一个」而不是「投票多数」，是因为多数派在混合文件里会随编辑漂移——用户删掉
     * 几行之后行尾突然从 CRLF 变成 LF，写回时整份文件的行尾就跟着变了。「第一个」是
     * 稳定可预测的规则，混合行尾的情况另外由 [isMixed] 报给使用者去提示。
     *
     * CR 必须先于 LF 判断之前与之后的字符结合来看：单独的 \r 是 CR，\r\n 才是 CRLF。
     * 反过来先判 \n 会把 CRLF 误判成 LF。
     */
    fun detect(text: String): LineEnding {
        for (i in text.indices) {
            when (text[i]) {
                '\r' -> return if (i + 1 < text.length && text[i + 1] == '\n') LineEnding.CRLF else LineEnding.CR
                '\n' -> return LineEnding.LF
            }
        }
        return LineEnding.LF
    }

    /** 文件中是否混用了不止一种行尾。混合文件即使按某一种写回，也会改动其中一部分行。 */
    fun isMixed(text: String): Boolean {
        var sawLf = false
        var sawCrlf = false
        var sawCr = false
        var i = 0
        while (i < text.length) {
            when {
                text[i] == '\r' && i + 1 < text.length && text[i + 1] == '\n' -> {
                    sawCrlf = true
                    i++
                }
                text[i] == '\r' -> sawCr = true
                text[i] == '\n' -> sawLf = true
            }
            i++
        }
        return listOf(sawLf, sawCrlf, sawCr).count { it } > 1
    }

    /**
     * 把任意行尾归一化成 LF。绝大多数文件没有 \r，先用 indexOf 快速跳过可以省掉一次
     * 全量替换（1MB 文本上这是毫秒级的差别，但输入过程中会被反复调用）。
     */
    fun normalize(text: String): String {
        if (text.indexOf('\r') < 0) return text
        return text.replace("\r\n", "\n").replace('\r', '\n')
    }

    /** 把内部统一的 LF 还原成目标行尾。归一化 → 还原必须能原样往返。 */
    fun restore(text: String, ending: LineEnding): String {
        if (ending == LineEnding.LF) return text
        return text.replace("\n", ending.token)
    }
}
