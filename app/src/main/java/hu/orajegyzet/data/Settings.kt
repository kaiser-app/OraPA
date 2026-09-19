package hu.orajegyzet.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class Mode { OFFLINE, ONLINE, AUTO }

data class AppSettings(
    val mode: Mode = Mode.AUTO,
    val privacyLock: Boolean = false,      // ha igaz: a hang SOHA nem hagyja el a készüléket
    val wifiOnly: Boolean = true,
    val useGemma: Boolean = true,           // offline összefoglaló a Gemmával (kikapcsolható)
    // --- Teljesítmény / haladó ---
    val whisperThreads: Int = 6,            // Whisper szálszám (SD8Gen3: 6 nagy mag)
    val keepModelWarm: Boolean = true,      // a Whisper modellt ne töltse újra minden felvételnél
    val warmMinutes: Int = 5,               // meddig maradjon "melegen" a modell (perc)
    val stagedDisplay: Boolean = true,      // előbb a leirat, az összefoglaló utólag
    val whisperChunkSec: Int = 30,          // felvétel-darab hossza (mp) – hosszú felvételhez
    val whisperOverlapSec: Int = 2,         // átfedés a darabok közt (mp)
    val summaryBlockChars: Int = 2000,      // Gemma összefoglaló-blokk mérete (karakter)
    val audioRetentionMin: Int = 30,        // a nyers hang megtartása (perc), majd törlés
    val geminiApiKey: String = "",
    val geminiModel: String = "gemini-3.1-flash-lite",
    val email: String = "",
    val smtpHost: String = "smtp.gmail.com",
    val smtpPort: Int = 587,
    val smtpUser: String = "",
    val smtpPass: String = "",              // Gmail esetén alkalmazásjelszó!
    val digestHour: Int = 18
)

private val Context.ds by preferencesDataStore("settings")

object Settings {
    private val MODE = stringPreferencesKey("mode")
    private val PRIVACY = booleanPreferencesKey("privacy")
    private val WIFI = booleanPreferencesKey("wifi")
    private val USE_GEMMA = booleanPreferencesKey("use_gemma")
    private val THREADS = intPreferencesKey("threads")
    private val WARM = booleanPreferencesKey("keep_warm")
    private val WARM_MIN = intPreferencesKey("warm_min")
    private val STAGED = booleanPreferencesKey("staged")
    private val CHUNK = intPreferencesKey("chunk_sec")
    private val OVERLAP = intPreferencesKey("overlap_sec")
    private val SUMBLOCK = intPreferencesKey("sum_block")
    private val AUDIORET = intPreferencesKey("audio_ret")
    private val GKEY = stringPreferencesKey("gkey")
    private val GMODEL = stringPreferencesKey("gmodel")
    private val EMAIL = stringPreferencesKey("email")
    private val SHOST = stringPreferencesKey("shost")
    private val SPORT = intPreferencesKey("sport")
    private val SUSER = stringPreferencesKey("suser")
    private val SPASS = stringPreferencesKey("spass")
    private val DHOUR = intPreferencesKey("dhour")

    fun flow(ctx: Context): Flow<AppSettings> = ctx.ds.data.map { p ->
        val rawModel = p[GMODEL] ?: "gemini-3.1-flash-lite"
        val model = if (rawModel.contains("1.5") || rawModel.contains("2.0") || rawModel.contains("2.5-flash-lite") || rawModel.isBlank()) "gemini-3.1-flash-lite" else rawModel
        AppSettings(
            mode = p[MODE]?.let { Mode.valueOf(it) } ?: Mode.AUTO,
            privacyLock = p[PRIVACY] ?: false,
            wifiOnly = p[WIFI] ?: true,
            useGemma = p[USE_GEMMA] ?: true,
            whisperThreads = p[THREADS] ?: 6,
            keepModelWarm = p[WARM] ?: true,
            warmMinutes = p[WARM_MIN] ?: 5,
            stagedDisplay = p[STAGED] ?: true,
            whisperChunkSec = p[CHUNK] ?: 30,
            whisperOverlapSec = p[OVERLAP] ?: 2,
            summaryBlockChars = p[SUMBLOCK] ?: 2000,
            audioRetentionMin = p[AUDIORET] ?: 30,
            geminiApiKey = p[GKEY] ?: "",
            geminiModel = model,
            email = p[EMAIL] ?: "",
            smtpHost = p[SHOST] ?: "smtp.gmail.com",
            smtpPort = p[SPORT] ?: 587,
            smtpUser = p[SUSER] ?: "",
            smtpPass = p[SPASS] ?: "",
            digestHour = p[DHOUR] ?: 18
        )
    }

    suspend fun get(ctx: Context): AppSettings = flow(ctx).first()

    suspend fun save(ctx: Context, s: AppSettings) {
        ctx.ds.edit { p ->
            p[MODE] = s.mode.name; p[PRIVACY] = s.privacyLock; p[WIFI] = s.wifiOnly
            p[USE_GEMMA] = s.useGemma
            p[THREADS] = s.whisperThreads; p[WARM] = s.keepModelWarm; p[WARM_MIN] = s.warmMinutes
            p[STAGED] = s.stagedDisplay; p[CHUNK] = s.whisperChunkSec; p[OVERLAP] = s.whisperOverlapSec
            p[SUMBLOCK] = s.summaryBlockChars; p[AUDIORET] = s.audioRetentionMin
            p[GKEY] = s.geminiApiKey; p[GMODEL] = s.geminiModel
            p[EMAIL] = s.email; p[SHOST] = s.smtpHost; p[SPORT] = s.smtpPort
            p[SUSER] = s.smtpUser; p[SPASS] = s.smtpPass; p[DHOUR] = s.digestHour
        }
    }
}
