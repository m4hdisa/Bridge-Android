package io.github.m4hdisa.bridge

/**
 * Many panels put a subscription's traffic and days left into the name of a config that doesn't
 * work, like "📊139.70GB Left-🕐expiredAt 26Days" or "۲۶ روز باقیمانده". Those aren't servers:
 * Bridge leaves them out of the list and shows what they say on the subscription instead, as the
 * Windows app reads them (src/main/subscription.ts there).
 */
object SubInfo {
    private val UNITS = mapOf('k' to 1024L, 'm' to 1024L * 1024, 'g' to 1024L * 1024 * 1024, 't' to 1024L * 1024 * 1024 * 1024)
    private val LEFT = Regex("([\\d.]+)\\s*([KMGT])i?B\\s*(?:left|remain)", RegexOption.IGNORE_CASE)
    private val LEFT_AFTER = Regex("(?:left|remain(?:ing)?|traffic)\\s*:?\\s*([\\d.]+)\\s*([KMGT])i?B", RegexOption.IGNORE_CASE)
    private val LEFT_FA = Regex("([\\d.]+)\\s*(?:([KMGT])i?B|(گیگ|مگ))[^\\d]{0,12}(?:باقی|مانده)", RegexOption.IGNORE_CASE)
    private val DAYS = Regex("(\\d+)\\s*(?:days?|روز)", RegexOption.IGNORE_CASE)
    private val ABOUT_TIME = Regex("expire|left|remain|روز", RegexOption.IGNORE_CASE)

    /** Persian and Arabic digits as ASCII, so one set of patterns covers them all. */
    private fun ascii(name: String) = name.map { c ->
        when (c) {
            in '۰'..'۹' -> '0' + (c - '۰')
            in '٠'..'٩' -> '0' + (c - '٠')
            else -> c
        }
    }.joinToString("")

    private fun remaining(name: String): Long? {
        val n = ascii(name)
        val m = LEFT.find(n) ?: LEFT_AFTER.find(n) ?: LEFT_FA.find(n) ?: return null
        val unit = m.groupValues.getOrNull(2)?.firstOrNull()?.lowercaseChar() ?: if (m.groupValues.getOrNull(3) == "مگ") 'm' else 'g'
        val value = m.groupValues[1].toDoubleOrNull() ?: return null
        return (value * (UNITS[unit] ?: return null)).toLong()
    }

    private fun days(name: String): Int? {
        val n = ascii(name)
        if (!ABOUT_TIME.containsMatchIn(n)) return null
        return DAYS.find(n)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** A config whose name is the subscription's traffic or days left, not a server. */
    fun isInfo(name: String): Boolean = remaining(name) != null || days(name) != null

    /** What the info names say, or null when none says anything. */
    fun usage(names: List<String>, now: Long = System.currentTimeMillis()): Usage? {
        var left: Long? = null
        var days: Int? = null
        for (name in names) {
            if (left == null) left = remaining(name)
            if (days == null) days = days(name)
        }
        if (left == null && days == null) return null
        return Usage(0, 0, days?.let { now / 1000 + it * 86_400L } ?: 0, left ?: 0)
    }

    /** A subscription's links without its info configs, and the usage those told. All are kept if every one looks like info. */
    fun split(links: List<String>): Pair<List<String>, Usage?> {
        val names = links.map { runCatching { ShareLink.nameOf(it) }.getOrDefault("") }
        val info = links.indices.filter { isInfo(names[it]) }.toSet()
        if (info.isEmpty() || info.size == links.size) return links to null
        return links.filterIndexed { i, _ -> i !in info } to usage(info.map { names[it] })
    }
}
