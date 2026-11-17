package com.myassistant.app.finance

/** A money movement read from a bank / wallet message. */
data class ParsedTxn(
    val amount: Double,
    val currency: String,
    /** true = money out (spent/withdrawn/sent), false = money in. */
    val debit: Boolean,
    val merchant: String?,
    /** "**1234" (card/account ending) or null. */
    val account: String?,
    val category: String,
)

/**
 * Understands Egyptian bank & wallet SMS/notifications (Arabic and English): CIB, NBE, Banque Misr,
 * InstaPay, Vodafone Cash, cards, ATMs… Pure Kotlin — unit-tested in CI. Strict on purpose: promos
 * ("خصم 20%") and OTP codes are ignored.
 */
object ExpenseParser {

    /** key → Arabic label. */
    val categories = linkedMapOf(
        "food" to "🍔 أكل",
        "groceries" to "🛒 سوبر ماركت",
        "transport" to "🚕 مواصلات",
        "fuel" to "⛽ بنزين",
        "bills" to "💡 فواتير وشحن",
        "shopping" to "🛍️ شوبينج",
        "health" to "💊 صحة",
        "entertainment" to "🎬 ترفيه",
        "transfers" to "🔁 تحويلات",
        "cash" to "🏧 سحب كاش",
        "income" to "💵 فلوس داخلة",
        "other" to "📦 أخرى",
    )

    private val currencyWords = "EGP|LE|L\\.E|USD|SAR|AED|EUR|ج\\.م|جنيه|جنية|جم|دولار|ريال|درهم|يورو"
    private val num = "([0-9][0-9,]*(?:\\.[0-9]+)?)"
    private val amountPatterns = listOf(
        Regex("(?:$currencyWords)\\s*\\.?\\s*$num", RegexOption.IGNORE_CASE),
        Regex("$num\\s*(?:$currencyWords)", RegexOption.IGNORE_CASE),
        Regex("(?:مبلغ|بمبلغ|قيمة|بقيمة|amount of|amount|for)\\s*:?\\s*$num", RegexOption.IGNORE_CASE),
    )

    private val debitWords = listOf(
        "تم خصم", "خصم مبلغ", "تم سحب", "سحب نقدي", "عملية شراء", "تم شراء", "شراء بمبلغ", "تم دفع", "تم سداد", "دفع مبلغ",
        "تم تحويل", "حولت", "مدين", "debited", "debit ", "charged", "purchase", "spent", "withdrawn", "withdrawal",
        "paid", "payment of", "transferred to", "you sent", "sent to",
    )
    private val creditWords = listOf(
        "تم إضافة", "تم اضافة", "إضافة مبلغ", "اضافة مبلغ", "تم إيداع", "تم ايداع", "إيداع", "ايداع", "استلام", "استلمت",
        "دائن", "إليك", "اليك", "لحسابك", "credited", "credit to", "received", "deposit", "refund", "استرداد",
    )
    private val ignoreWords = listOf("otp", "one time", "verification", "رمز التحقق", "كود التحقق", "كلمة المرور", "password", "رمز الدخول")
    private val balanceCut = Regex("(الرصيد|رصيدك|رصيد|balance|avl|available|المتاح)", RegexOption.IGNORE_CASE)

    fun parse(text: String): ParsedTxn? {
        val t = normalize(text)
        val low = t.lowercase()
        if (ignoreWords.any { low.contains(it) }) return null

        val dIdx = debitWords.mapNotNull { w -> low.indexOf(w).takeIf { it >= 0 } }.minOrNull()
        val cIdx = creditWords.mapNotNull { w -> low.indexOf(w).takeIf { it >= 0 } }.minOrNull()
        if (dIdx == null && cIdx == null) return null
        // "تم تحويل … إليك" is money in, even though it starts with a transfer word.
        val incomingTransfer = low.contains("تم تحويل") && (low.contains("إليك") || low.contains("اليك") || low.contains("لحسابك"))
        val debit = when {
            incomingTransfer -> false
            dIdx != null && cIdx != null -> dIdx <= cIdx
            else -> dIdx != null
        }

        // Ignore amounts that belong to the remaining balance.
        val main = balanceCut.find(t)?.let { m -> t.substring(0, m.range.first) }?.takeIf { it.isNotBlank() } ?: t
        val amount = amountPatterns.firstNotNullOfOrNull { p ->
            p.find(main)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
        } ?: return null
        if (amount <= 0 || amount > 10_000_000) return null

        val merchant = merchantOf(main, debit)
        val account = Regex("(?:ending(?: with)?|ending in|منتهي(?:ة)? ب|المنتهية ب|رقم|حساب|بطاقة|card)\\s*[:#]?\\s*[*xX•.]*\\s*([0-9]{3,4})\\b", RegexOption.IGNORE_CASE)
            .find(t)?.groupValues?.get(1)?.let { "**$it" }
            ?: Regex("[*xX•]{2,}([0-9]{3,4})").find(t)?.groupValues?.get(1)?.let { "**$it" }
        val currency = Regex(currencyWords, RegexOption.IGNORE_CASE).find(main)?.value?.let { currencyOf(it) } ?: "EGP"
        val category = if (!debit) "income" else categorize("${merchant.orEmpty()} $low")
        return ParsedTxn(amount, currency, debit, merchant, account, category)
    }

    private fun merchantOf(t: String, debit: Boolean): String? {
        val stop = "(?=\\s+(?:on|يوم|بتاريخ|في تاريخ|via|عبر|بنجاح|using|ref|reference|الساعة|at\\s+\\d)|[،,.\\n]|$)"
        val markers = if (debit) listOf("at", "@", "لدى", "لدي", "عند", "في", "to", "إلى", "الى", "ل")
        else listOf("from", "من")
        for (m in markers) {
            val r = Regex("(?:^|\\s)${Regex.escape(m)}\\s+(.{2,40}?)$stop", RegexOption.IGNORE_CASE)
            val v = r.find(t)?.groupValues?.get(1)?.trim()?.trim('-', ':', '"')
            if (!v.isNullOrBlank() && !v.startsWith("حساب") && !v.startsWith("بطاقة") && !v.lowercase().startsWith("your") &&
                !v.lowercase().startsWith("card") && v.any { it.isLetterOrDigit() }
            ) return v
        }
        return null
    }

    fun categorize(s: String): String {
        val x = s.lowercase()
        fun has(vararg k: String) = k.any { x.contains(it) }
        return when {
            has("atm", "سحب نقدي", "سحب", "withdraw") -> "cash"
            has("talabat", "طلبات", "elmenus", "mcdonald", "kfc", "pizza", "بيتزا", "burger", "برجر", "cafe", "كافيه", "coffee", "قهوة", "starbucks", "costa", "restaurant", "مطعم", "breadfast", "otlob", "hardee", "koshary", "كشري") -> "food"
            has("carrefour", "كارفور", "spinneys", "سبينيس", "hyper", "هايبر", "kazyon", "كازيون", "metro", "مترو ماركت", "seoudi", "سعودي", "kheir zaman", "خير زمان", "market", "ماركت", "بقالة", "oscar") -> "groceries"
            has("uber", "أوبر", "اوبر", "careem", "كريم", "didi", "indrive", "swvl", "سويفل", "metro ticket", "تذكرة") -> "transport"
            has("wataniya", "وطنية", "mobil", "موبيل", "total", "توتال", "shell", "chillout", "بنزين", "fuel", "emarat misr") -> "fuel"
            has("vodafone", "فودافون", "orange", "أورانج", "اورانج", "etisalat", "اتصالات", " we ", "telecom", "كهرباء", "electric", "غاز", "gas", "مياه", "water", "شحن", "recharge", "fawry", "فوري", "internet", "انترنت", "bill", "فاتورة") -> "bills"
            has("amazon", "أمازون", "noon", "نون", "jumia", "جوميا", "shein", "zara", "h&m", "lc waikiki", "mall", "مول", "store") -> "shopping"
            has("pharmacy", "صيدلية", "صيدليه", "hospital", "مستشفى", "clinic", "عيادة", "lab", "معمل", "doctor", "دكتور", "seif", "el ezaby", "العزبي") -> "health"
            has("netflix", "spotify", "anghami", "أنغامي", "shahid", "شاهد", "watch it", "cinema", "سينما", "playstation", "steam", "youtube") -> "entertainment"
            has("instapay", "انستاباي", "إنستاباي", "تحويل", "transfer", "sent to", "wallet", "محفظة") -> "transfers"
            else -> "other"
        }
    }

    private fun currencyOf(w: String): String = when (w.lowercase()) {
        "usd", "دولار" -> "USD"
        "sar", "ريال" -> "SAR"
        "aed", "درهم" -> "AED"
        "eur", "يورو" -> "EUR"
        else -> "EGP"
    }

    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(
                when (c) {
                    in '٠'..'٩' -> '0' + (c - '٠')
                    in '۰'..'۹' -> '0' + (c - '۰')
                    '٫' -> '.'
                    '٬' -> ','
                    else -> c
                },
            )
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }
}
