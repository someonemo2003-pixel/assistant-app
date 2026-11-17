package com.myassistant.app.finance

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.myassistant.app.tools.Schema
import com.myassistant.app.tools.Tool
import com.myassistant.app.tools.ToolContext
import com.myassistant.app.tools.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class Expense(
    val id: Long,
    val time: Long,
    val amount: Double,
    val currency: String,
    val debit: Boolean,
    val merchant: String?,
    val account: String?,
    val category: String,
    val source: String,
)

/** Money in/out read from bank & wallet messages, on the phone only. Blocking — use from IO. */
class ExpenseStore private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "expenses.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE expenses (id INTEGER PRIMARY KEY AUTOINCREMENT, hash TEXT UNIQUE NOT NULL, time INTEGER NOT NULL, " +
                "amount REAL NOT NULL, currency TEXT NOT NULL, debit INTEGER NOT NULL, merchant TEXT, account TEXT, " +
                "category TEXT NOT NULL, source TEXT NOT NULL, raw TEXT NOT NULL)",
        )
        db.execSQL("CREATE INDEX expenses_time ON expenses(time)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** Saves a parsed message once; returns true if new. */
    fun save(time: Long, source: String, raw: String, t: ParsedTxn): Boolean {
        val hash = "$source|${t.amount}|${t.debit}|${raw.take(120).hashCode()}|${time / 60_000}"
        return writableDatabase.insertWithOnConflict("expenses", null, ContentValues().apply {
            put("hash", hash); put("time", time); put("amount", t.amount); put("currency", t.currency)
            put("debit", if (t.debit) 1 else 0); put("merchant", t.merchant); put("account", t.account)
            put("category", t.category); put("source", source); put("raw", raw.take(500))
        }, SQLiteDatabase.CONFLICT_IGNORE) > 0
    }

    fun list(from: Long, to: Long, category: String? = null): List<Expense> {
        val where = StringBuilder("time >= ? AND time < ?")
        val args = mutableListOf(from.toString(), to.toString())
        if (category != null) { where.append(" AND category = ?"); args += category }
        return readableDatabase.rawQuery(
            "SELECT id, time, amount, currency, debit, merchant, account, category, source FROM expenses WHERE $where ORDER BY time DESC",
            args.toTypedArray(),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Expense(c.getLong(0), c.getLong(1), c.getDouble(2), c.getString(3), c.getInt(4) == 1,
                        c.getString(5), c.getString(6), c.getString(7), c.getString(8)),
                )
            }
        }
    }

    fun setCategory(id: Long, category: String) =
        writableDatabase.execSQL("UPDATE expenses SET category = ? WHERE id = ?", arrayOf<Any>(category, id))

    fun delete(id: Long) = writableDatabase.execSQL("DELETE FROM expenses WHERE id = ?", arrayOf<Any>(id))

    companion object {
        @Volatile private var instance: ExpenseStore? = null
        fun get(ctx: Context) = instance ?: synchronized(this) { instance ?: ExpenseStore(ctx.applicationContext).also { instance = it } }
    }
}

object ExpenseSync {
    private val financeHints = listOf(
        "bank", "بنك", "banque", "cib", "nbe", "misr", "مصر", "qnb", "hsbc", "alex", "faisal", "adib", "instapay", "انستا",
        "wallet", "محفظة", "cash", "كاش", "valu", "فاليو", "fawry", "فوري", "meeza", "ميزة", "paymob", "orange money", "etisalat cash",
    )

    fun looksFinancial(sourceName: String) = financeHints.any { sourceName.lowercase().contains(it) }

    /** Reads new SMS from the inbox since the last scan (first time: last 60 days). */
    fun scanSms(ctx: Context): Int {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return 0
        val prefs = ctx.getSharedPreferences("expenses", Context.MODE_PRIVATE)
        val since = prefs.getLong("sms_since", System.currentTimeMillis() - 60L * 86_400_000)
        val store = ExpenseStore.get(ctx)
        var added = 0
        var newest = since
        ctx.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} > ?", arrayOf(since.toString()), "${Telephony.Sms.DATE} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val from = c.getString(0).orEmpty()
                val body = c.getString(1).orEmpty()
                val time = c.getLong(2)
                newest = maxOf(newest, time)
                // Banks/wallets send from names; personal numbers are skipped unless it clearly is a wallet message.
                val fromName = from.any { it.isLetter() }
                if (!fromName && !looksFinancial(body)) continue
                val t = ExpenseParser.parse(body) ?: continue
                if (store.save(time, from, body, t)) added++
            }
        }
        prefs.edit().putLong("sms_since", newest).apply()
        return added
    }

    /** Called for every notification from a bank/wallet app. */
    fun fromNotification(ctx: Context, appLabel: String, pkg: String, text: String, time: Long) {
        if (!looksFinancial("$appLabel $pkg")) return
        val t = ExpenseParser.parse(text) ?: return
        ExpenseStore.get(ctx).save(time, appLabel, text, t)
    }
}

internal fun monthRange(offset: Int): Pair<Long, Long> {
    val c = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.MONTH, -offset)
    }
    val start = c.timeInMillis
    c.add(Calendar.MONTH, 1)
    return start to minOf(c.timeInMillis, System.currentTimeMillis() + 1)
}

class ExpensesTool : Tool {
    override val name = "expenses"
    override val title = "المصاريف"
    override val description =
        "The user's spending and income, read automatically from bank/wallet SMS and notifications (Egyptian banks, InstaPay, " +
            "Vodafone Cash…). Totals per category and the transactions. period: today | week | month | last_month."
    override val parameters = Schema.obj(
        "period" to Schema.string("today | week | month | last_month"),
        "category" to Schema.string("Optional: " + ExpenseParser.categories.keys.joinToString(" | ")),
    )
    override val permissions = listOf(Manifest.permission.READ_SMS)

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        ExpenseSync.scanSms(ctx.app)
        val (from, to) = when (args.str("period")) {
            "today" -> Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis to System.currentTimeMillis() + 1
            "week" -> System.currentTimeMillis() - 7 * 86_400_000L to System.currentTimeMillis() + 1
            "last_month" -> monthRange(1)
            else -> monthRange(0)
        }
        val cat = args.str("category").takeIf { it in ExpenseParser.categories }
        val items = ExpenseStore.get(ctx.app).list(from, to, cat)
        if (items.isEmpty()) return@withContext "No transactions found for this period (from bank/wallet messages)."
        val out = items.filter { it.debit }
        val inc = items.filter { !it.debit }
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
        buildString {
            appendLine("Spent: ${"%.2f".format(Locale.US, out.sumOf { it.amount })} EGP in ${out.size} transactions; received: ${"%.2f".format(Locale.US, inc.sumOf { it.amount })}")
            appendLine("By category:")
            out.groupBy { it.category }.entries.sortedByDescending { e -> e.value.sumOf { it.amount } }.forEach { (k, v) ->
                appendLine("- ${ExpenseParser.categories[k] ?: k}: ${"%.2f".format(Locale.US, v.sumOf { it.amount })} (${v.size})")
            }
            appendLine("Latest:")
            items.take(15).forEach { e ->
                appendLine("${fmt.format(Date(e.time))} ${if (e.debit) "-" else "+"}${e.amount} ${e.currency} ${e.merchant ?: ""} [${e.category}] ${e.account ?: e.source}")
            }
        }
    }
}
