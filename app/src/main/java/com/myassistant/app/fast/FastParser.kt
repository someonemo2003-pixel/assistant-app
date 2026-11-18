package com.myassistant.app.fast

/**
 * Understands short, common requests (mostly Egyptian Arabic) instantly, without any AI call.
 * Pure Kotlin (no Android) so it's unit-tested in CI.
 * To teach it a new command: add a branch in [parse] that returns a new [LocalIntent].
 */
sealed interface LocalIntent {
    data class Alarm(val hour: Int, val minute: Int) : LocalIntent
    data class Timer(val seconds: Int) : LocalIntent
    data class OpenApp(val names: List<String>) : LocalIntent
    data class Remember(val fact: String) : LocalIntent
    data class Flashlight(val on: Boolean) : LocalIntent
    data class Steps(val period: String) : LocalIntent
    data class Walk(val start: Boolean) : LocalIntent
    /** Where the user went on a day (places log). */
    data class Places(val day: String) : LocalIntent
    /** "صليت العصر" — prayer may be null (= the current one). */
    data class Prayed(val prayer: String?) : LocalIntent
    /** "العصر امتى؟" / "فاضل قد ايه على المغرب" / "مواعيد الصلاة". */
    data class PrayerTime(val prayer: String?, val remaining: Boolean) : LocalIntent
    /** "قريت 5 صفحات" / "ذاكرت ساعة" / "شربت كوبايتين" / "نمت 6 ساعات". */
    data class Habit(val habit: String, val amount: Double, val unit: String) : LocalIntent
    /** "سجل المكان ده باسم الجامعة". */
    data class SavePlace(val name: String) : LocalIntent
    data class Silent(val on: Boolean) : LocalIntent
    data object Battery : LocalIntent
    data object LockPhone : LocalIntent
    data object CheckUpdate : LocalIntent
    data object AskTime : LocalIntent
    data object AskDate : LocalIntent
    /** "تعرف ايه عني؟" — answered straight from memory, no AI call. */
    data object AboutMe : LocalIntent
}

object FastParser {

    fun parse(raw: String): LocalIntent? {
        val original = raw.trim()
        if (original.isEmpty() || original.length > 80) return null
        val t = normalize(original)
        val words = t.split(' ').filter { it.isNotEmpty() }

        // "سجل المكان ده باسم X" (before "سجل/افتكر" = remember).
        savePlaceRegex.find(original)?.let { m ->
            val name = m.groupValues[1].trim().trimEnd('.', '!', '؟', '?')
            if (name.isNotEmpty() && name.length <= 30) return LocalIntent.SavePlace(name)
        }

        // Prayers
        val prayerWord = words.firstNotNullOfOrNull { w -> prayerNames.entries.firstOrNull { w == it.key || w == "ال" + it.key }?.value }
        if (words.firstOrNull() in setOf("صليت", "صلّيت") || (words.firstOrNull() == "انا" && words.getOrNull(1) == "صليت")) {
            if (words.size <= 4) return LocalIntent.Prayed(prayerWord)
        }
        if (hasAny(t, "مواعيد الصلاه", "مواقيت الصلاه", "الصلاه امتي", "الصلاه الجايه")) return LocalIntent.PrayerTime(null, "فاضل" in words)
        val aboutAlarm = alarmWords.any { w -> words.any { it.startsWith(w) } }
        if (prayerWord != null && !aboutAlarm && (hasAny(t, "امتي", "امتى", "الساعه كام", "الساعه كم", "بيأذن", "بيدن", "هيأذن") || "فاضل" in words)) {
            return LocalIntent.PrayerTime(prayerWord, "فاضل" in words)
        }

        // Habits
        habit(t, words)?.let { return it }

        // Phone basics
        if (words.any { it.endsWith("صامت") || it.endsWith("سايلنت") || it == "silent" }) {
            val off = words.any { it in setOf("شيل", "الغي", "اقفل", "رجع", "ارجع", "بطل") }
            return LocalIntent.Silent(!off)
        }
        if (hasAny(t, "البطاريه كام", "كام البطاريه", "الشحن كام", "كام الشحن", "البطاريه فيها كام", "battery")) return LocalIntent.Battery
        if (hasAny(t, "اقفل الشاشه", "قفل الشاشه", "اقفل الموبايل", "lock screen")) return LocalIntent.LockPhone
        if (hasAny(t, "فيه تحديث", "في تحديث", "حدث نفسك", "اخر نسخه", "شوف التحديث", "دور علي تحديث", "check update")) return LocalIntent.CheckUpdate

        // "What do you know about me?" (before remember, which also matches "فاكر").
        if ((words.any { it == "عني" || it == "عنى" } && words.any { it in aboutMeVerbs }) ||
            hasAny(t, "about me", "اعرفه عني", "تعرفه عني")
        ) return LocalIntent.AboutMe

        // Remember: keep the user's own wording for the fact.
        rememberRegex.find(original)?.let { m ->
            val fact = m.groupValues[2].trim().trimEnd('.', '!', '؟', '?')
            if (fact.length >= 3) return LocalIntent.Remember(fact)
        }

        if (words.any { it == "كشاف" || it == "الكشاف" || it == "فلاش" || it == "الفلاش" || it == "flashlight" }) {
            val off = words.any { it in setOf("اقفل", "اقفلي", "طفي", "اطفي", "وقف", "off") }
            val on = words.any { it in setOf("شغل", "شغلي", "افتح", "ولع", "نور", "on") }
            if (off != on) return LocalIntent.Flashlight(on)
        }

        // Health & places
        val day = if ("امبارح" in words || "امس" in words) "yesterday" else "today"
        if (words.any { it.startsWith("خطو") } && words.any { it in setOf("كام", "كم", "عدد", "قد") }) {
            return LocalIntent.Steps(if ("الاسبوع" in words || "اسبوع" in words) "week" else day)
        }
        val aboutWalk = words.any { it == "المشي" || it == "مشي" || it == "تتبع" || it == "المشوار" }
        if (aboutWalk && words.any { it in setOf("ابدا", "ابدء", "شغل", "start") }) return LocalIntent.Walk(true)
        if (aboutWalk && words.any { it in setOf("وقف", "اقفل", "خلص", "stop", "انهي") }) return LocalIntent.Walk(false)
        val startsWithOpen = words.firstOrNull()?.let { it in openWords } == true
        if ("مشيت فين" in t || "رحت فين" in t || "روحت فين" in t || "كنت فين" in t || t == "انا فين" || "انا فين دلوقتي" in t ||
            (!startsWithOpen && words.any { it == "خريطه" || it == "الخريطه" || it == "خريطتي" })
        ) {
            return LocalIntent.Places(day)
        }

        val wantsAlarm = alarmWords.any { w -> words.any { it.startsWith(w) } } || "alarm" in words
        val wantsTimer = timerWords.any { it in words }
        val wantsOpen = words.firstOrNull()?.let { it in openWords } == true
        val mentionsOpen = words.any { w -> openWords.any { o -> w == o || w == "و$o" } }
        // Two different commands in one message → let the big brain handle it.
        if (listOf(wantsAlarm, wantsTimer, mentionsOpen).count { it } > 1) return null

        if (wantsTimer) return parseDuration(words)?.let { LocalIntent.Timer(it) }
        if (wantsAlarm) {
            // "نبهني بعد 10 دقايق" is really a timer.
            if ("بعد" in words) return parseDuration(words.dropWhile { it != "بعد" }.drop(1))?.let { LocalIntent.Timer(it) }
            return parseClock(t, words)?.let { (h, m) -> LocalIntent.Alarm(h, m) }
        }

        if (wantsOpen) {
            val verb = words.first()
            val rest = words.drop(1).filterNot { it in fillerWords }.joinToString(" ")
            if (rest.isEmpty()) return null
            val known = appAliases[rest] ?: appAliases[rest.removePrefix("ال")]
            // "شغّل" is also used for music/videos, so only accept it for known apps.
            if (verb != "افتح" && verb != "open" && known == null) return null
            val originalName = original.split(Regex("\\s+")).drop(1)
                .filterNot { normalize(it) in fillerWords }
                .joinToString(" ").trim().trimEnd('.', '!', '؟', '?')
            return LocalIntent.OpenApp(listOfNotNull(known, originalName).distinct())
        }

        if (timeQuestions.any { it in t }) return LocalIntent.AskTime
        if (dateQuestions.any { it in t }) return LocalIntent.AskDate
        return null
    }

    // ---------- time of day ----------

    /** Returns (hour 0-23, minute) or null if no time was found. */
    fun parseClock(t: String, words: List<String>): Pair<Int, Int>? {
        var hour: Int? = null
        var minute = 0

        Regex("(\\d{1,2})\\s*[:.]\\s*(\\d{2})").find(t)?.let {
            hour = it.groupValues[1].toInt()
            minute = it.groupValues[2].toInt()
        }
        if (hour == null) {
            Regex("(?<![\\d])(\\d{1,2})(?![\\d])(?:\\s*و\\s*(\\d{1,2})(?![\\d])(?!\\s*(?:دقيق|دقاي)))?").find(t)?.let {
                hour = it.groupValues[1].toInt()
                it.groupValues[2].takeIf { s -> s.isNotEmpty() }?.let { m -> minute = m.toInt() }
            }
        }
        if (hour == null) {
            for (i in words.indices) {
                val w = words[i]
                val n = hourWords[w] ?: hourWords[w.removePrefix("ال")]
                if (n != null && !(w == "الثانيه" && words.getOrNull(i + 1) != "عشر")) {
                    hour = if ((w.startsWith("ال") && words.getOrNull(i + 1) == "عشر")) n + 10 else n
                    break
                }
            }
        }
        var h = hour ?: return null

        when {
            hasAny(t, "الا ربع") -> { h -= 1; minute = 45 }
            hasAny(t, "الا تلت", "الا ثلث") -> { h -= 1; minute = 40 }
            hasAny(t, "ونص", "و نص") -> minute = 30
            hasAny(t, "وربع", "و ربع") -> minute = 15
            hasAny(t, "وتلت", "وثلث", "و تلت") -> minute = 20
        }
        if (h < 0) h = 23

        val pm = hasAny(t, "بالليل", "بليل", "مساء", "المسا", "العصر", "المغرب", "العشا", "pm")
        val noon = hasAny(t, "الضهر", "الظهر")
        val am = hasAny(t, "الصبح", "صباحا", "الفجر", "صباح", "am")
        if (h in 1..11 && (pm || (noon && h <= 5))) h += 12
        if (h == 12 && am) h = 0
        if (h !in 0..23 || minute !in 0..59) return null
        return h to minute
    }

    // ---------- durations ----------

    fun parseDuration(words: List<String>): Int? {
        val text = words.joinToString(" ")
        var total = 0
        if (hasAny(text, "نص ساعه")) total += 1800
        if (hasAny(text, "ربع ساعه")) total += 900
        if (hasAny(text, "ساعه ونص")) total += 1800
        for (i in words.indices) {
            val unit = unitSeconds(words[i]) ?: continue
            val (secs, explicit) = unit
            if (explicit) { total += secs; continue }
            val prev = words.getOrNull(i - 1)
            val n = prev?.toIntOrNull() ?: prev?.let { numberWords[it] }
            when {
                n != null -> total += n * secs
                prev == "نص" || prev == "ربع" -> Unit // handled above
                else -> total += secs
            }
        }
        if (total == 0) {
            // "تايمر 10" → minutes
            words.firstNotNullOfOrNull { it.toIntOrNull() }?.let { total = it * 60 }
        }
        return total.takeIf { it in 1..86_400 }
    }

    /** Returns (seconds per unit, true if the word already includes its count like "دقيقتين"). */
    private fun unitSeconds(w: String): Pair<Int, Boolean>? = when (w) {
        "ثانيه", "ثواني", "ثوان", "sec", "secs", "seconds" -> 1 to false
        "دقيقه", "دقايق", "دقائق", "min", "mins", "minutes" -> 60 to false
        "دقيقتين" -> 120 to true
        "ساعه", "ساعات", "hour", "hours" -> 3600 to false
        "ساعتين" -> 7200 to true
        else -> null
    }

    // ---------- learned phrases ----------

    internal val politeWords = setOf("لو", "سمحت", "فضلك", "please", "pls", "بقي", "يا", "مساعدي", "nova", "نوفا", "ممكن", "عايزك", "عاوزك")

    /** Stable key for a learned phrase: normalised, without polite filler words. */
    fun phraseKey(text: String): String =
        normalize(text).split(' ').filter { it.isNotEmpty() && it !in politeWords }.joinToString(" ")
            .replace(" من ", " ").trim()

    /** All numbers in a phrase (used so "صحيني 7" never replays a learned "صحيني 8"). */
    fun numbers(text: String): List<String> = Regex("\\d+").findAll(normalize(text)).map { it.value }.toList()

    // ---------- text helpers ----------

    fun normalize(s: String): String {
        val sb = StringBuilder()
        for (c in s.lowercase()) {
            when (c) {
                'أ', 'إ', 'آ', 'ٱ' -> sb.append('ا')
                'ة' -> sb.append('ه')
                'ى' -> sb.append('ي')
                in '٠'..'٩' -> sb.append('0' + (c - '٠'))
                in '۰'..'۹' -> sb.append('0' + (c - '۰'))
                in 'ً'..'ْ', 'ـ' -> Unit
                '؟', '?', '!', '،', ',', '.', '؛' -> if (sb.isNotEmpty() && sb.last().isDigit()) sb.append(c) else sb.append(' ')
                else -> sb.append(c)
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim().trimEnd('.')
    }

    private val aboutMeVerbs = setOf(
        "تعرف", "عارف", "تعرفه", "تعرفي", "فاكر", "فاكره", "مسجل", "حافظ", "متسجل", "عندك", "تفتكر",
    )

    private fun hasAny(t: String, vararg parts: String) = parts.any { t.contains(it) }

    private val prayerNames = mapOf(
        "فجر" to "fajr", "صبح" to "fajr", "ظهر" to "dhuhr", "ضهر" to "dhuhr", "جمعه" to "dhuhr",
        "عصر" to "asr", "مغرب" to "maghrib", "عشا" to "isha", "عشاء" to "isha",
    )

    private val savePlaceRegex = Regex(
        "^\\s*(?:سجل|سجّل|احفظ|احفظلي|سجلي)\\s+(?:لي\\s+)?(?:المكان|مكاني|الموقع|موقعي|المكان اللي انا فيه)\\s*(?:ده|دا|دة|هنا|الحالي)?\\s+(?:باسم|بإسم|بأسم|بسم|اسمه|إسمه|ب)\\s*(.+)$",
    )

    private fun count(words: List<String>, i: Int): Double? {
        val prev = words.getOrNull(i - 1) ?: return null
        return prev.toDoubleOrNull() ?: numberWords[prev]?.toDouble()
    }

    /** "قريت 5 صفحات", "ذاكرت ساعتين", "شربت 3 كوبايات", "نمت 6 ساعات", "اتمرنت نص ساعة". */
    private fun habit(t: String, words: List<String>): LocalIntent.Habit? {
        val verb = words.firstOrNull() ?: return null
        fun minutes(): Double? {
            if ("نص ساعه" in t) return 30.0
            if ("ربع ساعه" in t) return 15.0
            if ("ساعتين" in words) return 120.0
            for (i in words.indices) {
                when (words[i]) {
                    "ساعه", "ساعات" -> return (count(words, i) ?: 1.0) * 60 + if ("ونص" in words) 30 else 0
                    "دقيقه", "دقايق", "دقائق" -> return count(words, i)
                    "دقيقتين" -> return 2.0
                }
            }
            return null
        }
        return when (verb) {
            "قريت", "قرات", "خلصت" -> {
                val i = words.indexOfFirst { it.startsWith("صفح") }
                val pages: Double? = when {
                    i < 0 -> null
                    words[i] == "صفحتين" -> 2.0
                    else -> count(words, i) ?: 1.0
                }
                val quran = words.any { it.contains("قران") || it == "ورد" || it == "وردي" || it == "جزء" }
                when {
                    pages != null -> LocalIntent.Habit(if (quran) "quran" else "reading", pages, "pages")
                    quran && "جزء" in words -> LocalIntent.Habit("quran", 20.0, "pages")
                    else -> minutes()?.let { LocalIntent.Habit(if (quran) "quran" else "reading", it, "minutes") }
                }
            }
            "ذاكرت", "ذكرت", "زاكرت" -> minutes()?.let { LocalIntent.Habit("study", it, "minutes") }
            "نمت" -> minutes()?.let { LocalIntent.Habit("sleep", it / 60.0, "hours") }
            "اتمرنت", "لعبت", "جريت" -> minutes()?.let { LocalIntent.Habit("exercise", it, "minutes") }
            "شربت" -> {
                if (words.none { it.startsWith("مي") || it.startsWith("ماي") || it.startsWith("كوباي") || it.startsWith("كباي") }) return null
                val i = words.indexOfFirst { it.startsWith("كوباي") || it.startsWith("كباي") }
                val cups = when {
                    i < 0 -> 1.0
                    words[i].endsWith("تين") -> 2.0
                    else -> count(words, i) ?: 1.0
                }
                LocalIntent.Habit("water", cups, "cups")
            }
            else -> null
        }
    }

    private val rememberRegex = Regex("^\\s*(افتكر|افتكري|احفظ|سجل|سجّل|remember)\\s+(?:ان|أن|إن|انّ|that\\s+)?\\s*(.+)$")

    private val alarmWords = listOf("صحيني", "صحّيني", "منبه", "نبهني", "فوقني", "اصحيني", "صحيه")
    private val timerWords = setOf("تايمر", "مؤقت", "موقت", "timer", "عداد")
    private val openWords = setOf("افتح", "افتحلي", "شغل", "شغلي", "open")
    private val fillerWords = setOf("لي", "ليا", "تطبيق", "برنامج", "ابلكيشن", "app")

    private val timeQuestions = listOf("الساعه كام", "الساعه كم", "كام الساعه", "الوقت كام", "الوقت كم", "what time")
    private val dateQuestions = listOf(
        "النهارده ايه", "النهارده كام", "تاريخ النهارده", "التاريخ ايه", "التاريخ كام",
        "احنا يوم ايه", "احنا في يوم", "انهي يوم", "اليوم ايه", "what day", "today's date",
    )

    private val numberWords = mapOf(
        "واحد" to 1, "واحده" to 1, "اتنين" to 2, "اثنين" to 2, "تلاته" to 3, "ثلاثه" to 3, "تلات" to 3,
        "اربعه" to 4, "اربع" to 4, "خمسه" to 5, "خمس" to 5, "سته" to 6, "ست" to 6, "سبعه" to 7, "سبع" to 7,
        "تمانيه" to 8, "ثمانيه" to 8, "تمن" to 8, "تسعه" to 9, "تسع" to 9, "عشره" to 10, "عشر" to 10,
        "حداشر" to 11, "اتناشر" to 12, "خمستاشر" to 15, "عشرين" to 20, "تلاتين" to 30, "اربعين" to 40,
        "خمسين" to 50, "ستين" to 60,
    )

    private val hourWords = mapOf(
        "واحده" to 1, "اتنين" to 2, "تلاته" to 3, "اربعه" to 4, "خمسه" to 5, "سته" to 6,
        "سبعه" to 7, "تمانيه" to 8, "تسعه" to 9, "عشره" to 10, "حداشر" to 11, "اتناشر" to 12,
        // ordinal forms after removing "ال": السابعه → سابعه
        "حاديه" to 1, "ثانيه" to 2, "تانيه" to 2, "ثالثه" to 3, "تالته" to 3, "رابعه" to 4, "خامسه" to 5,
        "سادسه" to 6, "سابعه" to 7, "ثامنه" to 8, "تامنه" to 8, "تاسعه" to 9, "عاشره" to 10,
    )

    /** Arabic / slang names → the English name usually shown on the phone. */
    private val appAliases = mapOf(
        "واتساب" to "WhatsApp", "واتس" to "WhatsApp", "الواتس" to "WhatsApp", "whatsapp" to "WhatsApp",
        "يوتيوب" to "YouTube", "اليوتيوب" to "YouTube", "youtube" to "YouTube",
        "فيسبوك" to "Facebook", "فيس" to "Facebook", "الفيس" to "Facebook", "فيس بوك" to "Facebook",
        "انستجرام" to "Instagram", "انستا" to "Instagram", "انستاجرام" to "Instagram",
        "تيك توك" to "TikTok", "تيكتوك" to "TikTok",
        "كاميرا" to "Camera", "الكاميرا" to "Camera",
        "اعدادات" to "Settings", "الاعدادات" to "Settings", "الضبط" to "Settings",
        "كروم" to "Chrome", "جوجل" to "Google", "خرايط" to "Maps", "الخرايط" to "Maps", "خرائط" to "Maps",
        "جيميل" to "Gmail", "الجيميل" to "Gmail", "الايميل" to "Gmail",
        "تليجرام" to "Telegram", "التليجرام" to "Telegram", "ماسنجر" to "Messenger", "الماسنجر" to "Messenger",
        "سناب" to "Snapchat", "سناب شات" to "Snapchat", "تويتر" to "X", "اكس" to "X",
        "الاستوديو" to "Gallery", "المعرض" to "Gallery", "الصور" to "Photos",
        "الساعه" to "Clock", "المنبه" to "Clock", "الاله الحاسبه" to "Calculator", "الحاسبه" to "Calculator",
        "سبوتيفاي" to "Spotify", "نتفليكس" to "Netflix", "التقويم" to "Calendar", "الكالندر" to "Calendar",
        "الرسايل" to "Messages", "الرسائل" to "Messages", "التليفون" to "Phone", "الاتصال" to "Phone",
        "الملفات" to "Files", "المتجر" to "Play Store", "بلاي ستور" to "Play Store", "البلاي ستور" to "Play Store",
        "انغامي" to "Anghami", "الانغامي" to "Anghami", "شات جي بي تي" to "ChatGPT",
    )
}
