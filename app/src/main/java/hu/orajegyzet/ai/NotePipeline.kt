package hu.orajegyzet.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import hu.orajegyzet.data.AppSettings
import hu.orajegyzet.data.Mode
import org.json.JSONObject

/** A feldolgozás kimenete. */
data class NoteResult(
    val transcript: String,
    val summary: String,
    val structured: String,
    val keywords: List<String>
)

/** Egy hangfájl → jegyzet feldolgozó. Két implementáció: Offline és Gemini. */
interface NotePipeline {
    /** @param audioPath M4A fájl (16 kHz mono AAC). Hosszú futás — Worker-szálon hívandó.
     *  @param isLesson igaz, ha órarendi órából indult (tanár/óra megfogalmazás),
     *         hamis, ha általános esemény (előadó/esemény megfogalmazás). */
    suspend fun process(audioPath: String, subject: String, dateLabel: String, isLesson: Boolean): NoteResult

    /** Újra-összefoglalás meglévő leiratból (hang nélkül), ha a felhasználónak
     *  nem tetszik az eredmény. A leiratot változatlanul visszaadja. */
    suspend fun resummarize(transcript: String, subject: String, dateLabel: String, isLesson: Boolean): NoteResult
}

/** Eldönti, hogy az adott körülmények között melyik pipeline fusson. */
object ModeSelector {

    fun choose(ctx: Context, s: AppSettings): Boolean /* true = online */ {
        if (s.privacyLock) return false
        if (s.geminiApiKey.isBlank()) return false
        return when (s.mode) {
            Mode.OFFLINE -> false
            Mode.ONLINE -> true
            Mode.AUTO -> hasNetwork(ctx, s.wifiOnly)
        }
    }

    private fun hasNetwork(ctx: Context, wifiOnly: Boolean): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return if (wifiOnly) caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) else true
    }
}

/** Közös segéd: a modellek JSON-válaszának tűrőképes feldolgozása. */
internal fun parseModelJson(raw: String, fallbackTranscript: String = ""): NoteResult {
    val cleaned = raw.trim()
        .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

    // 1) Először a szabályos JSON (ha a modell tisztán adta)
    runCatching {
        val o = JSONObject(cleaned)
        val kw = mutableListOf<String>()
        o.optJSONArray("keywords")?.let { arr -> for (i in 0 until arr.length()) kw += arr.getString(i) }
        val summary = o.optString("summary")
        if (summary.isNotBlank()) {
            return NoteResult(
                transcript = o.optString("transcript", fallbackTranscript).ifBlank { fallbackTranscript },
                summary = summary,
                structured = o.optString("notes"),
                keywords = kw
            )
        }
    }

    // 2) Megengedő, mezőnkénti kinyerés (működik hiányzó vesszők / hibás JSON esetén is)
    fun unescape(s: String) = s
        .replace("\\n", "\n").replace("\\t", "\t")
        .replace("\\\"", "\"").replace("\\\\", "\\")
    fun field(key: String): String {
        val m = Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"", RegexOption.DOT_MATCHES_ALL)
            .find(cleaned) ?: return ""
        return unescape(m.groupValues[1]).trim()
    }
    val summary = field("summary")
    val notes = field("notes")
    val tr = field("transcript")
    val kw = Regex("\"keywords\"\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL)
        .find(cleaned)?.groupValues?.get(1)
        ?.let { inner -> Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(inner).map { unescape(it.groupValues[1]) }.toList() }
        ?: emptyList()

    // ha még így sem találtunk semmit, legalább a nyers szöveg legyen az összefoglaló
    return if (summary.isBlank() && notes.isBlank()) {
        NoteResult(fallbackTranscript.ifBlank { tr }, cleaned.take(2000), "", emptyList())
    } else {
        NoteResult(fallbackTranscript.ifBlank { tr }, summary, notes, kw)
    }
}

internal fun buildNotePrompt(isLesson: Boolean): String {
    val role = if (isLesson) "tanár" else "előadó/felszólaló"
    val event = if (isLesson) "tanóra" else "esemény (pl. előadás, értekezlet)"
    return """Te egy precíz jegyzetelő asszisztens vagy.
A bemenet egy $event hanganyaga vagy leirata magyarul.
Válaszolj KIZÁRÓLAG érvényes JSON-nal, markdown nélkül, pontosan ezekkel a kulcsokkal:
{"transcript": "teljes leirat (ha hangot kaptál; leirat-bemenetnél hagyd üresen)",
 "summary": "8-12 mondatos tömör összefoglaló magyarul",
 "notes": "strukturált jegyzet markdown formátumban: ## témakörök, **fontos fogalmak**, definíciók, példák, és ha elhangzott, teendők/feladatok",
 "keywords": ["5-10 kulcsfogalom"]}
Ha a beszélőre hivatkozol, használd a(z) "$role" megfogalmazást (NE feltételezz tanárt, ha nem tanóráról van szó).
Csak az elhangzottakra támaszkodj, ne találj ki tartalmat.
FONTOS: ne ismételd meg ugyanazokat a mondatokat vagy bekezdéseket; minden gondolat csak egyszer szerepeljen."""
}

internal const val NOTE_PROMPT_HU = """Te egy precíz jegyzetelő asszisztens vagy.
A bemenet egy esemény hanganyaga vagy leirata magyarul.
Válaszolj KIZÁRÓLAG érvényes JSON-nal, markdown nélkül, pontosan ezekkel a kulcsokkal:
{"transcript": "teljes leirat",
 "summary": "8-12 mondatos tömör összefoglaló magyarul",
 "notes": "strukturált jegyzet markdown formátumban",
 "keywords": ["5-10 kulcsfogalom"]}
Csak az elhangzottakra támaszkodj, ne ismételd a mondatokat."""
