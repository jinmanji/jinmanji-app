package com.mobai.jm.util

/**
 * 语言 / 全彩 推测（用于封面国旗与搜索筛选）。
 *
 * 判定顺序：
 *  ① 明确关键词（漢化/中文/翻译组 → 中文优先，即使标题含假名）
 *  ② 韩文谚文、日文假名（标题有假名 = 日文原版的可能性极高）
 *  ③ 纯拉丁字母 → English；纯汉字（无假名无谚文）→ 中文
 */
object LangDetect {

    enum class Lang(val code: String, val label: String, val flag: String) {
        CHINESE("zh", "中文", "🇨🇳"),
        JAPANESE("ja", "日本語", "🇯🇵"),
        ENGLISH("en", "English", "🇺🇸"),
        KOREAN("ko", "한국어", "🇰🇷"),
    }

    private fun isHangul(cp: Int): Boolean =
        (cp in 0xAC00..0xD7AF) || (cp in 0x1100..0x11FF) || (cp in 0x3130..0x318F)

    private fun isKana(cp: Int): Boolean =
        (cp in 0x3040..0x309F) || (cp in 0x30A0..0x30FF) || (cp in 0x31F0..0x31FF)

    private fun isHan(cp: Int): Boolean =
        (cp in 0x4E00..0x9FFF) || (cp in 0x3400..0x4DBF)

    private fun isLatinLetter(cp: Int): Boolean =
        (cp in 0x41..0x5A) || (cp in 0x61..0x7A)

    fun guess(title: String, extras: List<String> = emptyList()): Lang? {
        val hay = (title + " " + extras.joinToString(" ")).lowercase()

        // ① 明确关键词（中文优先）
        if (listOf(
                "中文", "漢化", "汉化", "中字", "中國", "中国", "chinese",
                "中文翻譯", "中文翻译", "汉化组", "漢化組", "翻譯組", "烤肉",
            ).any { it in hay }
        ) {
            return Lang.CHINESE
        }
        if (listOf("日本語", "日語", "日文", "japanese", "生肉", "日版").any { it in hay }) {
            return Lang.JAPANESE
        }
        if (listOf("한국", "한글", "korean", "韓文", "韩文", "번역").any { it in hay }) {
            return Lang.KOREAN
        }
        if (listOf("english", "英文", "英譯", "英译", "translated").any { it in hay }) {
            return Lang.ENGLISH
        }

        // ② 字符脚本统计
        var hangul = 0
        var kana = 0
        var han = 0
        var latin = 0
        for (ch in title) {
            val cp = ch.code
            when {
                isHangul(cp) -> hangul++
                isKana(cp) -> kana++
                isHan(cp) -> han++
                isLatinLetter(cp) -> latin++
            }
        }
        when {
            hangul > 0 -> return Lang.KOREAN
            kana > 0 -> return Lang.JAPANESE
            han > 0 -> return Lang.CHINESE
            latin >= 4 -> return Lang.ENGLISH
        }
        return null
    }

    fun emoji(title: String, extras: List<String> = emptyList()): String? = guess(title, extras)?.flag

    /** 全彩推测 */
    fun isFullColor(title: String, extras: List<String> = emptyList()): Boolean {
        val hay = (title + " " + extras.joinToString(" ")).lowercase()
        return listOf("全彩", "フルカラー", "full color", "fullcolor", "彩漫", "彩色").any { it in hay }
    }
}
