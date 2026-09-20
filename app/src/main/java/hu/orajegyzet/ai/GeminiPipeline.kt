package hu.orajegyzet.ai

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Online mód: a hangfájl a Gemini Files API-ra kerül, megvárja az ACTIVE állapotot,
 * majd egyetlen generateContent hívás készíti el a leiratot + jegyzetet + összefoglalót.
 * A feldolgozás után a fájl törlődik a felhőből.
 */
class GeminiPipeline(
    private val apiKey: String,
    private val model: String = "gemini-3.1-flash-lite"
) : NotePipeline {

    private val http = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()

    override suspend fun process(audioPath: String, subject: String, dateLabel: String, isLesson: Boolean): NoteResult {
        validateApiKey()
        val file = File(audioPath)
        // 15 MB alatti fájlnál közvetlen inline Base64-gyel hívjuk meg (gyorsabb és nincs Files API dependencia)
        val raw = if (file.length() in 1..(15 * 1024 * 1024L)) {
            generateInlineWithFallback(file, isLesson)
        } else {
            val fileUri = uploadFile(file)
            try {
                waitForFileActive(fileUri)
                generateWithFallback(fileUri, isLesson)
            } finally {
                runCatching { deleteRemote(fileUri) }
            }
        }
        return parseModelJson(raw)
    }

    private fun validateApiKey() {
        val trimmed = apiKey.trim()
        if (trimmed.isBlank()) {
            throw RuntimeException("Hiányzó Gemini API-kulcs! Add meg a Beállítások képernyőn.")
        }
    }

    private fun generateInlineWithFallback(file: File, isLesson: Boolean): String {
        val modelsToTry = listOf(model, "gemini-3.1-flash-lite", "gemini-3.5-flash-lite", "gemini-3.5-flash", "gemini-2.5-flash").distinct()
        val b64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        var lastError: Exception? = null

        for (m in modelsToTry) {
            try {
                val parts = JSONArray()
                parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/mp4").put("data", b64)))
                parts.put(JSONObject().put("text", "Ez egy hangfelvétel. Készítsd el a JSON-választ a megadott séma szerint."))

                val userContent = JSONObject()
                userContent.put("role", "user")
                userContent.put("parts", parts)

                val sysInst = JSONObject()
                sysInst.put("parts", JSONArray().put(JSONObject().put("text", buildNotePrompt(isLesson))))

                val body = JSONObject()
                body.put("systemInstruction", sysInst)
                body.put("contents", JSONArray().put(userContent))
                body.put("generationConfig", JSONObject().put("responseMimeType", "application/json"))

                val reqBuilder = Request.Builder()
                    .url("$BASE/v1beta/models/$m:generateContent?key=$apiKey")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                if (apiKey.startsWith("AQ.")) reqBuilder.header("Authorization", "Bearer $apiKey")
                val req = reqBuilder.build()

                return http.newCall(req).execute().use { r ->
                    val text = r.body?.string() ?: ""
                    if (!r.isSuccessful) {
                        throw errorForHttpCode(r.code, text, "generateContent", m)
                    }
                    parseCandidateText(text)
                }
            } catch (e: Exception) {
                lastError = e
                if (e.message?.contains("404") == true || e.message?.contains("nem érhető el") == true) {
                    continue
                }
                throw e
            }
        }
        throw lastError ?: RuntimeException("A Gemini modellek egyike sem érhető el.")
    }

    override suspend fun resummarize(transcript: String, subject: String, dateLabel: String, isLesson: Boolean): NoteResult {
        val raw = generateFromTextWithFallback(transcript, isLesson)
        return parseModelJson(raw, fallbackTranscript = transcript).copy(transcript = transcript)
    }

    private fun generateWithFallback(fileUri: String, isLesson: Boolean): String {
        val modelsToTry = listOf(model, "gemini-3.1-flash-lite", "gemini-3.5-flash-lite", "gemini-3.5-flash", "gemini-2.5-flash").distinct()
        var lastError: Exception? = null
        for (m in modelsToTry) {
            try {
                return generate(fileUri, isLesson, m)
            } catch (e: Exception) {
                lastError = e
                if (e.message?.contains("404") == true || e.message?.contains("nem érhető el") == true) {
                    continue
                }
                throw e
            }
        }
        throw lastError ?: RuntimeException("A Gemini modellek egyike sem érhető el (404).")
    }

    private fun generateFromTextWithFallback(transcript: String, isLesson: Boolean): String {
        val modelsToTry = listOf(model, "gemini-3.1-flash-lite", "gemini-3.5-flash-lite", "gemini-3.5-flash", "gemini-2.5-flash").distinct()
        var lastError: Exception? = null
        for (m in modelsToTry) {
            try {
                return generateFromText(transcript, isLesson, m)
            } catch (e: Exception) {
                lastError = e
                if (e.message?.contains("404") == true || e.message?.contains("nem érhető el") == true) {
                    continue
                }
                throw e
            }
        }
        throw lastError ?: RuntimeException("A Gemini modellek egyike sem érhető el (404).")
    }

    private fun generateFromText(transcript: String, isLesson: Boolean, targetModel: String = model): String {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("text", buildNotePrompt(isLesson)))))
            .put("contents", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("parts", JSONArray()
                    .put(JSONObject().put("text",
                        "Ez egy meglévő leirat. Készíts belőle ÚJ, jobb összefoglalót és jegyzetet a megadott séma szerint. A \"transcript\" mezőbe másold vissza változatlanul a leiratot.\n\nLeirat:\n$transcript")))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))

        val reqBuilder = Request.Builder()
            .url("$BASE/v1beta/models/$targetModel:generateContent?key=$apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (apiKey.startsWith("AQ.")) reqBuilder.header("Authorization", "Bearer $apiKey")
        val req = reqBuilder.build()

        return http.newCall(req).execute().use { r ->
            val text = r.body?.string() ?: ""
            if (!r.isSuccessful) {
                throw errorForHttpCode(r.code, text, "generateContent", targetModel)
            }
            parseCandidateText(text)
        }
    }

    /** Resumable feltöltés a Files API-ra; a file_uri-t adja vissza. */
    private fun uploadFile(file: File): String {
        val meta = JSONObject().put("file", JSONObject().put("display_name", file.name))
        val startReq = Request.Builder()
            .url("$BASE/upload/v1beta/files?key=$apiKey")
            .header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", file.length().toString())
            .header("X-Goog-Upload-Header-Content-Type", "audio/mp4")
            .post(meta.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val uploadUrl = http.newCall(startReq).execute().use { r ->
            val text = r.body?.string() ?: ""
            if (!r.isSuccessful) {
                throw errorForHttpCode(r.code, text, "Files API indítás")
            }
            r.header("X-Goog-Upload-URL") ?: error("Hiányzó upload URL a Google-től")
        }

        val upReq = Request.Builder()
            .url(uploadUrl)
            .header("X-Goog-Upload-Command", "upload, finalize")
            .header("X-Goog-Upload-Offset", "0")
            .post(file.asRequestBody("audio/mp4".toMediaType()))
            .build()

        return http.newCall(upReq).execute().use { r ->
            val text = r.body?.string() ?: ""
            if (!r.isSuccessful) {
                throw errorForHttpCode(r.code, text, "Files API feltöltés")
            }
            JSONObject(text).getJSONObject("file").getString("uri")
        }
    }

    /** Megvárja, amíg a feltöltött fájl állapota ACTIVE lesz a Google szerverén. */
    private fun waitForFileActive(fileUri: String) {
        val name = fileUri.substringAfter("/files/").let { "files/$it" }
        val req = Request.Builder()
            .url("$BASE/v1beta/$name?key=$apiKey")
            .get()
            .build()

        var attempts = 0
        while (attempts < 30) {
            runCatching {
                http.newCall(req).execute().use { r ->
                    if (r.isSuccessful) {
                        val state = JSONObject(r.body?.string() ?: "").optString("state")
                        if (state == "ACTIVE") return
                        if (state == "FAILED") error("A fájl feldolgozása meghiúsult a Gemini szerverén.")
                    }
                }
            }
            Thread.sleep(1000)
            attempts++
        }
    }

    private fun generate(fileUri: String, isLesson: Boolean, targetModel: String = model): String {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("text", buildNotePrompt(isLesson)))))
            .put("contents", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("parts", JSONArray()
                    .put(JSONObject().put("fileData", JSONObject()
                        .put("mimeType", "audio/mp4").put("fileUri", fileUri)))
                    .put(JSONObject().put("text",
                        "Ez egy hangfelvétel. Készítsd el a JSON-választ a megadott séma szerint.")))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))

        val reqBuilder = Request.Builder()
            .url("$BASE/v1beta/models/$targetModel:generateContent?key=$apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (apiKey.startsWith("AQ.")) reqBuilder.header("Authorization", "Bearer $apiKey")
        val req = reqBuilder.build()

        return http.newCall(req).execute().use { r ->
            val text = r.body?.string() ?: ""
            if (!r.isSuccessful) {
                throw errorForHttpCode(r.code, text, "generateContent", targetModel)
            }
            parseCandidateText(text)
        }
    }

    private fun parseCandidateText(text: String): String {
        val json = JSONObject(text)
        val candidates = json.optJSONArray("candidates")
            ?: error("A Gemini nem adott vissza választ (candidates hiányzik).")
        if (candidates.length() == 0) error("Üres válasz érkezett a Geminitől.")
        val cand = candidates.getJSONObject(0)
        val content = cand.optJSONObject("content")
            ?: error("A Gemini válasz nem tartalmaz tartalmat (lehet, hogy a biztonsági szűrő letiltotta).")
        val parts = content.optJSONArray("parts")
            ?: error("A Gemini válasz nem tartalmaz szöveges elemeket.")
        return parts.getJSONObject(0).getString("text")
    }

    private fun deleteRemote(fileUri: String) {
        val name = fileUri.substringAfter("/files/").let { "files/$it" }
        val req = Request.Builder()
            .url("$BASE/v1beta/$name?key=$apiKey")
            .delete().build()
        runCatching { http.newCall(req).execute().close() }
    }

    private fun errorForHttpCode(code: Int, bodyText: String, action: String, targetModel: String = model): Exception {
        val msg = when (code) {
            400, 401, 403 -> "Gemini API-kulcs / engedély hiba ($code). Ellenőrizd az API-kulcsot a Beállításokban!"
            404 -> "Gemini modell hiba ($code). A megadott modell ($targetModel) nem érhető el."
            429 -> "Gemini API kvóta túllépés ($code). Próbáld újra kicsit később!"
            else -> "$action hiba ($code): $bodyText"
        }
        return RuntimeException(msg)
    }

    companion object {
        private const val BASE = "https://generativelanguage.googleapis.com"
    }
}
