package com.textnote.app.data

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 文档的编码信息：读取时探测出来，写回时沿用同一种编码。
 *
 * 为什么需要它：文本文件不一定是 UTF-8。Windows 记事本、不少国产编辑器默认 GBK/GB2312，
 * 老一点的文档还可能是 Big5。若一律按 UTF-8 读，会先显示成乱码；用户一旦编辑保存，
 * 文件会被整体改写成 UTF-8 —— 原文不可逆损坏。这是纯文本编辑器的立身之本：
 * 「打开看一眼」不能改变文件的一个字节。
 *
 * [withBom] 单独记一笔，是因为 BOM（字节序标记）在正文里是零宽的 U+FEFF：留在文本里
 * 会让「首行以某字符开头」这类行首匹配失效（`^#` 匹配不到 `\uFEFF#`），所以读进来要剥掉，
 * 写回去要补上，保持文件字节层面的原样。
 */
data class DocumentEncoding(
    val charset: Charset,
    val withBom: Boolean = false,
) {
    companion object {
        val UTF8 = DocumentEncoding(Charsets.UTF_8)
    }
}

/** 一次解码的结果：正文（已剥掉 BOM）与写回时要用的编码 */
data class DecodedText(val text: String, val encoding: DocumentEncoding)

/**
 * 编码探测与转换。
 *
 * 探测顺序刻意如此：
 * 1. **BOM 优先**——这是文件自己声明的编码，没有猜测成分，必须采信；
 * 2. **UTF-8 严格解码**——现代文件绝大多数是 UTF-8（纯 ASCII 也在此列），能过就不必再猜；
 * 3. **GB18030**——GBK / GB2312 的超集，覆盖绝大多数中文老文件；
 * 4. 都不成立时退回 UTF-8 容错解码，宁可让用户看到替换字符，也好过读不出内容。
 *
 * 为什么没有 Big5：GB18030 的双字节空间完全覆盖 Big5 的编码空间，也就是**任何一份合法的
 * Big5 文件都能被 GB18030 成功解码**，无法靠「能不能解码」来区分。把 Big5 排在前面则会让
 * 大量 GBK 文件被误判（GBK 的实际使用规模远大于 Big5），代价更大。所以繁体老文件会显示成
 * 乱码，但因写回用的是同一个编码，文件不会被破坏。
 *
 * 关于探测错误的代价：读与写用的是同一个编码，而 GB18030 是字节序列与字符之间的一一映射，
 * 所以即便猜错，显示是乱码、文件字节却能原样往返。相比之下「一律按 UTF-8 读、再按 UTF-8
 * 写回」才是真正的破坏性做法。
 *
 * 候选编码目前只有两种，够用但不多。CotEditor 支持十余种（Shift_JIS / EUC-JP / EUC-KR /
 * ISO-8859-x / UTF-32…），M1 会扩成一张可配置的候选表——届时探测逻辑不变，只换 [candidates]。
 */
object TextEncoding {

    /** 严格解码的候选编码，按优先级排列（BOM 已在 [decode] 里优先处理） */
    private val candidates: List<Charset> = listOfNotNull(
        Charsets.UTF_8,
        charsetOrNull("GB18030"),
    )

    fun decode(bytes: ByteArray): DecodedText {
        bomAt(bytes)?.let { (charset, bomLength) ->
            val text = String(bytes, bomLength, bytes.size - bomLength, charset)
            return DecodedText(text, DocumentEncoding(charset, withBom = true))
        }
        for (charset in candidates) {
            strictDecode(bytes, charset)?.let { return DecodedText(it, DocumentEncoding(charset)) }
        }
        return DecodedText(String(bytes, Charsets.UTF_8), DocumentEncoding.UTF8)
    }

    /**
     * 解码「可能被切在半截上的一段字节」，用于列表摘要（只读前若干字节）。
     *
     * 与 [decode] 的区别：这里的末尾很可能是被切开的半个多字节字符，逐字节严格解码必然
     * 失败。所以每个候选编码都额外容忍丢掉末尾 1~3 个残字节再试。
     *
     * **BOM 必须与 [decode] 同样优先采信**。少了这一步，同一个文件会出现两种结果：
     * 最近列表（走这里）里 UTF-16 文件的摘要是乱码、UTF-8 BOM 文件的摘要开头多一个不可见的
     * U+FEFF，而点开之后的正文（走 [decode]）却完全正常——用户只会以为文件坏了，
     * 或者以为列表里的链接指错了地方。
     */
    fun decodeTruncated(bytes: ByteArray): DecodedText {
        bomAt(bytes)?.let { (charset, bomLength) ->
            if (bytes.size <= bomLength) {
                // 只有 BOM、没有正文（空文件）
                return DecodedText("", DocumentEncoding(charset, withBom = true))
            }
            // UTF-16 的末尾常常被切在半个码元上，所以这里用宽松解码而不是 strictDecode：
            // 宁可最后一个字符是替换符，也好过为了「严格」把整段退回 UTF-8 变成乱码。
            return DecodedText(
                String(bytes, bomLength, bytes.size - bomLength, charset),
                DocumentEncoding(charset, withBom = true),
            )
        }
        for (charset in candidates) {
            for (dropped in 0..3) {
                val usable = bytes.size - dropped
                if (usable <= 0) break
                strictDecode(bytes.copyOf(usable), charset)?.let {
                    return DecodedText(it, DocumentEncoding(charset))
                }
            }
        }
        return DecodedText(String(bytes, Charsets.UTF_8), DocumentEncoding.UTF8)
    }

    fun encode(text: String, encoding: DocumentEncoding): ByteArray {
        val body = text.toByteArray(encoding.charset)
        if (!encoding.withBom) return body
        return bomBytes(encoding.charset) + body
    }

    /** 给用户看的编码名。带 BOM 时标出来，否则用户无法解释「为什么文件开头多了三个字节」 */
    fun displayName(encoding: DocumentEncoding): String {
        val base = encoding.charset.name()
        return if (encoding.withBom) "$base (BOM)" else base
    }

    /** 识别开头的 BOM，返回（BOM 对应的字符集，BOM 长度） */
    private fun bomAt(bytes: ByteArray): Pair<Charset, Int>? = when {
        bytes.startsWith(0xEF, 0xBB, 0xBF) -> Charsets.UTF_8 to 3
        bytes.startsWith(0xFF, 0xFE) -> Charsets.UTF_16LE to 2
        bytes.startsWith(0xFE, 0xFF) -> Charsets.UTF_16BE to 2
        else -> null
    }

    private fun bomBytes(charset: Charset): ByteArray = when (charset) {
        Charsets.UTF_8 -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        Charsets.UTF_16LE -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        Charsets.UTF_16BE -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        else -> ByteArray(0)
    }

    /** 整段解码，遇到非法字节立刻放弃（用于判断"这份字节到底是不是这种编码"） */
    private fun strictDecode(bytes: ByteArray, charset: Charset): String? {
        if (bytes.isEmpty()) return if (charset == Charsets.UTF_8) "" else null
        return try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    /** 设备不一定提供某个字符集，取不到就跳过，别让探测本身变成崩溃点 */
    private fun charsetOrNull(name: String): Charset? = runCatching { Charset.forName(name) }.getOrNull()

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { this[it] == prefix[it].toByte() }
    }
}
