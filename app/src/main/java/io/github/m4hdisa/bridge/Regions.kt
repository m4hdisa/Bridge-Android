package io.github.m4hdisa.bridge

/**
 * The country a config is in, guessed from its name: a flag emoji ("🇩🇪 Germany"), a country
 * or city name in English or Persian, or a two-letter code written in capitals ("DE-1"). The
 * country Bridge saw traffic leave from, once connected through it, wins over the guess.
 */
object Regions {
    private val NAMES: List<Pair<String, String>> = listOf(
        "DE" to "germany|deutschland|frankfurt|berlin|falkenstein|nuremberg|nürnberg|آلمان|فرانکفورت",
        "NL" to "netherlands|holland|amsterdam|هلند|آمستردام",
        "FI" to "finland|helsinki|فنلاند|هلسینکی",
        "FR" to "france|paris|فرانسه|پاریس",
        "GB" to "united kingdom|england|london|britain|انگلیس|انگلستان|لندن|بریتانیا",
        "US" to "united states|usa|america|new york|los angeles|chicago|dallas|miami|seattle|آمریکا|امریکا",
        "CA" to "canada|toronto|montreal|vancouver|کانادا",
        "TR" to "turkey|türkiye|turkiye|istanbul|ترکیه|استانبول",
        "SE" to "sweden|stockholm|سوئد",
        "CH" to "switzerland|zurich|سوئیس",
        "AT" to "austria|vienna|اتریش",
        "PL" to "poland|warsaw|لهستان",
        "RU" to "russia|moscow|روسیه|مسکو",
        "AE" to "emirates|uae|dubai|امارات|دبی",
        "JP" to "japan|tokyo|ژاپن|توکیو",
        "SG" to "singapore|سنگاپور",
        "HK" to "hong kong|هنگ کنگ",
        "IN" to "india|mumbai|هند",
        "AM" to "armenia|yerevan|ارمنستان",
        "LT" to "lithuania|لیتوانی",
        "LV" to "latvia|لتونی",
        "EE" to "estonia|استونی",
        "RO" to "romania|رومانی",
        "BG" to "bulgaria|بلغارستان",
        "IT" to "italy|milan|ایتالیا",
        "ES" to "spain|madrid|اسپانیا",
        "NO" to "norway|نروژ",
        "DK" to "denmark|دانمارک",
        "AU" to "australia|sydney|استرالیا",
        "KR" to "korea|seoul|کره",
    )

    private val CODES = NAMES.map { it.first }.toSet() + setOf("UK")
    private val WORDS = NAMES.map { (code, words) -> code to Regex("(?i)(^|[^\\p{L}])(${words})($|[^\\p{L}])") }

    fun guess(name: String): String? {
        flagIn(name)?.let { return it }
        WORDS.firstOrNull { it.second.containsMatchIn(name) }?.let { return it.first }
        // A capital two-letter code on its own: "DE-1", "NL | Reality".
        Regex("(^|[^A-Za-z])([A-Z]{2})($|[^A-Za-z])").findAll(name).map { it.groupValues[2] }.firstOrNull { it in CODES }?.let { return if (it == "UK") "GB" else it }
        return null
    }

    /** The first flag emoji: two regional indicator letters. */
    private fun flagIn(name: String): String? {
        val points = name.codePoints().toArray()
        for (i in 0 until points.size - 1) {
            val a = points[i] - 0x1F1E6
            val b = points[i + 1] - 0x1F1E6
            // Iran's flag is never shown, so it says nothing here either.
            if (a in 0..25 && b in 0..25) return "${'A' + a}${'A' + b}".takeIf { it != "IR" }
        }
        return null
    }
}
