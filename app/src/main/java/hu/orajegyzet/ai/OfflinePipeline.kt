package hu.orajegyzet.ai

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.redravencomputing.whispercore.Whisper
import com.redravencomputing.whispercore.WhisperCpuConfig
import com.redravencomputing.whispercore.WhisperDelegate
import com.redravencomputing.whispercore.WhisperOperationError
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Offline mód — a hang soha nem hagyja el a készüléket.
 *  1. M4A → 16 kHz mono PCM dekódolás (MediaCodec)
 *  2. Beszédfelismerés:
 *       - elsődleges: Whisper (large-v3-turbo) a whisper.cpp wrapperrel,
 *         ha a filesDir/models/whisper.bin modell elérhető (magyarra a legjobb);
 *       - tartalék: Vosk magyar modell (filesDir/models/vosk-hu).
 *  3. Összefoglaló: Gemma a MediaPipe LLM Inference API-val
 *     (filesDir/models/gemma.task — pl. Gemma 3 4B int4)
 *
 * A modellfájlok letöltését és helyét a README írja le.
 */
class OfflinePipeline(
    private val ctx: Context,
    private val useGemma: Boolean = true,
    private val whisperThreads: Int = 6,
    private val keepWarm: Boolean = true,
    private val warmMinutes: Int = 5,
    private val summaryBlockChars: Int = 2000,
    private val chunkSec: Int = 30,
    private val overlapSec: Int = 2
) : NotePipeline {

    override suspend fun process(audioPath: String, subject: String, dateLabel: String, isLesson: Boolean): NoteResult {
        val transcript = runTranscription(audioPath, null)
        return finishSummary(transcript, subject, dateLabel, isLesson)
    }

    /** Csak a leirat — a szakaszos megjelenítéshez (előbb a leirat, az összefoglaló utólag).
     *  Az onPartial darabonként megkapja az eddigi leiratot (élő frissítéshez). */
    suspend fun transcribeOnly(audioPath: String, onPartial: (suspend (String) -> Unit)? = null): String =
        runTranscription(audioPath, onPartial)

    /** Összefoglaló a kész leiratból (a worker hívja a szakaszos megjelenítéshez). */
    suspend fun finishSummary(transcript: String, subject: String, dateLabel: String, isLesson: Boolean): NoteResult =
        buildOfflineResult(transcript)

    /** Kivonatos alap (sosem hallucinál) + opcionális Gemma-átfogalmazás. */
    private fun buildOfflineResult(transcript: String): NoteResult {
        val clean = transcript.trim()
        if (clean.isBlank()) return NoteResult(transcript, "(Üres leirat.)", "", emptyList())
        val extractive = Extractive.summarize(clean, 8)
        val notes = Extractive.bulletNotes(clean, 6)
        val keywords = Extractive.keywords(clean, 8)
        val gemmaFile = File(ctx.filesDir, "models/gemma.task")
        val summary = if (useGemma && gemmaFile.exists() && extractive.isNotBlank()) {
            System.gc()
            // A Gemma a KIVONATOT fogalmazza át (rövid, valós mondatok) — kis hallucináció-esély.
            runCatching { gemmaRephrase(gemmaFile, extractive) }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: extractive
        } else extractive
        return NoteResult(transcript, summary, notes, keywords)
    }

    private suspend fun runTranscription(audioPath: String, onPartial: (suspend (String) -> Unit)?): String {
        val whisperModel = File(ctx.filesDir, "models/whisper.bin")
        val voskDir = File(ctx.filesDir, "models/vosk-hu")
        return when {
            whisperModel.isFile && whisperModel.length() > 0 -> {
                val bigWav = File(ctx.cacheDir, "tr_${System.currentTimeMillis()}.wav")
                try {
                    try { m4aToWav(audioPath, bigWav) }
                    catch (e: Exception) { throw RuntimeException("[Hang→WAV] ${e.message}", e) }
                    if (!bigWav.exists() || bigWav.length() <= 44L)
                        error("[Hang→WAV] üres WAV keletkezett")
                    try { transcribeChunked(bigWav, whisperModel.absolutePath, onPartial) }
                    catch (e: Exception) {
                        if (e is RuntimeException && e.message?.startsWith("[Whisper]") == true) throw e
                        throw RuntimeException("[Whisper] ${e.message}", e)
                    }
                } finally {
                    bigWav.delete()
                }
            }
            voskDir.isDirectory && (voskDir.list()?.isNotEmpty() == true) ->
                transcribeVosk(audioPath, voskDir)
            else -> error("Nincs offline beszédfelismerő modell (Whisper vagy Vosk) — lásd Beállítások / README")
        }
    }

    /** A nagy WAV-ot darabokban (chunkSec, átfedéssel) írja át — így a memória mindig
     *  alacsony marad (bármilyen hosszú felvételnél), és a leirat darabonként épül. */
    private suspend fun transcribeChunked(bigWav: File, modelPath: String, onPartial: (suspend (String) -> Unit)?): String {
        val sr = 16_000
        val chunkBytes = (chunkSec.coerceIn(5, 300)) * sr * 2
        val overlapBytes = (overlapSec.coerceIn(0, 30)) * sr * 2
        val sb = StringBuilder()
        java.io.FileInputStream(bigWav).use { fis ->
            var skipped = 0L
            while (skipped < 44L) { val s = fis.skip(44L - skipped); if (s <= 0) break; skipped += s }
            var tail = ByteArray(0)
            val buf = ByteArray(chunkBytes)
            while (true) {
                val read = readFully(fis, buf)
                if (read <= 0) break
                val pcm = if (tail.isEmpty()) buf.copyOf(read) else tail + buf.copyOf(read)
                val chunkWav = File(ctx.cacheDir, "chunk_${System.nanoTime()}.wav")
                try {
                    writeWav(chunkWav, pcm, sampleRate = sr, channels = 1, bitsPerSample = 16)
                    val text = try { transcribeWhisper(chunkWav, modelPath) }
                        catch (e: Exception) { throw RuntimeException("[Whisper] ${e.message}", e) }
                    if (text.isNotBlank()) {
                        if (sb.isNotEmpty()) sb.append(' ')
                        sb.append(text)
                        onPartial?.invoke(sb.toString())
                    }
                } finally {
                    chunkWav.delete()
                }
                tail = if (overlapBytes in 1 until read) buf.copyOfRange(read - overlapBytes, read) else ByteArray(0)
                if (read < buf.size) break // utolsó (rövidebb) darab
            }
        }
        return sb.toString().trim()
    }

    /** Beolvas a pufferbe, amíg tele nem lesz vagy a fájl véget nem ér. */
    private fun readFully(ins: java.io.InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = ins.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    // ---------- 1a) STT (Whisper, large-v3-turbo) ----------

    /** A callback-alapú WhisperCore API coroutine-híd. Ha a "melegen tartás" be van
     *  kapcsolva, a betöltött modellt a feldolgozások közt megtartjuk (nem töltjük
     *  újra a ~547 MB-ot), és csak inaktivitás után engedjük el. A Mutex egyúttal
     *  egyszerre csak egy átírást enged (memóriavédelem). */
    private suspend fun transcribeWhisper(wav: File, modelPath: String): String {
        WhisperCpuConfig.override = whisperThreads.coerceIn(2, 8)
        return warmMutex.withLock {
            val whisper = acquireWhisper(modelPath)
            try {
                kotlinx.coroutines.withTimeout(6 * 60_000L) {
                    suspendCancellableCoroutine { cont ->
                        whisper.delegate = object : WhisperDelegate {
                            override fun didTranscribe(text: String) { if (cont.isActive) cont.resume(text.trim()) }
                            override fun failedToTranscribe(error: WhisperOperationError) { if (cont.isActive) cont.resumeWithException(error) }
                            override fun recordingFailed(error: WhisperOperationError) {}
                            override fun permissionRequestNeeded() {}
                            override fun didStartRecording() {}
                            override fun didStopRecording() {}
                        }
                        whisper.transcribeAudioFile(wav)
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                throw RuntimeException("átírás időtúllépés (a modell betöltése vagy a feldolgozás elakadt)", e)
            } finally {
                if (keepWarm) scheduleRelease(warmMinutes) else releaseWarmNow()
            }
        }
    }

    private suspend fun acquireWhisper(modelPath: String): Whisper {
        if (keepWarm && warmWhisper != null && warmModelPath == modelPath) {
            cancelRelease()
            return warmWhisper!!
        }
        runCatching { warmWhisper?.cleanup() }
        warmWhisper = null; warmModelPath = null
        val w = Whisper(ctx.applicationContext)
        kotlinx.coroutines.withTimeout(6 * 60_000L) { w.initializeModel(modelPath) }
        warmWhisper = w; warmModelPath = modelPath
        return w
    }

    private fun cancelRelease() { releaseJob?.cancel(); releaseJob = null }

    private fun scheduleRelease(mins: Int) {
        releaseJob?.cancel()
        releaseJob = warmScope.launch {
            kotlinx.coroutines.delay(mins.coerceAtLeast(1) * 60_000L)
            warmMutex.withLock {
                runCatching { warmWhisper?.cleanup() }
                warmWhisper = null; warmModelPath = null
            }
        }
    }

    private fun releaseWarmNow() {
        runCatching { warmWhisper?.cleanup() }
        warmWhisper = null; warmModelPath = null
    }

    /** Offline újra-összefoglalás ugyanazzal a kivonatos logikával. */
    override suspend fun resummarize(transcript: String, subject: String, dateLabel: String, isLesson: Boolean): NoteResult =
        buildOfflineResult(transcript)

    /** M4A (AAC) → 16 kHz mono 16-bit WAV, a meglévő MediaCodec dekóderrel. */
    private fun m4aToWav(srcPath: String, wavOut: File) {
        // Streamelve írjuk a lemezre (nem tartjuk a teljes PCM-et a memóriában).
        RandomAccessFile(wavOut, "rw").use { f ->
            f.setLength(0)
            writeWavHeader(f, dataLen = 0, sampleRate = 16_000, channels = 1, bitsPerSample = 16)
            var dataLen = 0
            decodePcm16k(srcPath) { bytes, len -> f.write(bytes, 0, len); dataLen += len }
            // a fejléc méret-mezőinek utólagos javítása
            f.seek(4); f.writeIntLE(36 + dataLen)
            f.seek(40); f.writeIntLE(dataLen)
        }
    }

    private fun writeWavHeader(f: RandomAccessFile, dataLen: Int, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        f.writeBytes("RIFF"); f.writeIntLE(36 + dataLen); f.writeBytes("WAVE")
        f.writeBytes("fmt "); f.writeIntLE(16); f.writeShortLE(1)
        f.writeShortLE(channels); f.writeIntLE(sampleRate); f.writeIntLE(byteRate)
        f.writeShortLE(blockAlign); f.writeShortLE(bitsPerSample)
        f.writeBytes("data"); f.writeIntLE(dataLen)
    }

    private fun writeWav(out: File, pcm: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        RandomAccessFile(out, "rw").use { f ->
            f.setLength(0)
            writeWavHeader(f, pcm.size, sampleRate, channels, bitsPerSample)
            f.write(pcm)
        }
    }

    private fun RandomAccessFile.writeIntLE(v: Int) {
        write(v and 0xff); write((v shr 8) and 0xff); write((v shr 16) and 0xff); write((v shr 24) and 0xff)
    }
    private fun RandomAccessFile.writeShortLE(v: Int) {
        write(v and 0xff); write((v shr 8) and 0xff)
    }

    // ---------- 1b) STT (Vosk, tartalék) ----------

    private fun transcribeVosk(audioPath: String, modelDir: File): String {
        val model = Model(modelDir.absolutePath)
        val rec = Recognizer(model, 16_000f)
        val sb = StringBuilder()
        decodePcm16k(audioPath) { pcm, len ->
            if (rec.acceptWaveForm(pcm, len)) {
                sb.append(JSONObject(rec.result).optString("text")).append(' ')
            }
        }
        sb.append(JSONObject(rec.finalResult).optString("text"))
        rec.close(); model.close()
        return sb.toString().trim()
    }

    /** M4A (AAC) → 16-bit PCM darabok. A felvétel eleve 16 kHz mono, így nincs újramintavételezés. */
    private fun decodePcm16k(path: String, onChunk: (ByteArray, Int) -> Unit) {
        val extractor = MediaExtractor().apply { setDataSource(path) }
        var trackFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                extractor.selectTrack(i); trackFormat = f; break
            }
        }
        val format = trackFormat ?: error("Nincs hangsáv a felvételben")
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var produced = 0L
        val deadline = System.currentTimeMillis() + 120_000L // max 2 perc dekódolásra
        try {
            while (!outputDone) {
                if (System.currentTimeMillis() > deadline)
                    error("Hang dekódolása túl sokáig tartott (időtúllépés)")
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outIdx = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_FORMAT_CHANGED,
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> { /* várunk tovább */ }
                    else -> if (outIdx >= 0) {
                        val out = codec.getOutputBuffer(outIdx)!!
                        if (info.size > 0) {
                            out.order(ByteOrder.LITTLE_ENDIAN)
                            val bytes = ByteArray(info.size)
                            out.get(bytes)
                            produced += bytes.size
                            onChunk(bytes, bytes.size)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
        }
        if (produced == 0L) error("A felvételből nem sikerült hangot kinyerni (üres dekódolás)")
    }

    // ---------- 2) Összefoglaló (Gemma / MediaPipe) ----------

    /** Opcionális: a kivonatot folyamatos prózává fogalmazza a Gemma (rövid, valós
     *  mondatokból álló bemenet → kicsi a hallucináció esélye). Hiba esetén a hívó
     *  visszaesik a tiszta kivonatra. */
    private fun gemmaRephrase(gemmaFile: File, extractive: String): String {
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(gemmaFile.absolutePath)
            .setMaxTokens(2048)
            .build()
        LlmInference.createFromOptions(ctx, options).use { llm ->
            return gemmaGenerate(llm, """Fogalmazd át az alábbi, kulcsmondatokból álló vázlatot folyamatos, gördülékeny 8-10 mondatos magyar összefoglalóvá. KIZÁRÓLAG az alábbi mondatok tartalmát használd — ne tegyél hozzá új információt, ne találj ki semmit. Csak az összefoglalót add vissza.

Kulcsmondatok:
$extractive""")
        }
    }

    /** Egy már betöltött Gemma modellen futtat egy promptot — chat-sablon +
     *  session-mintavételezés (a mohó dekódolás ismétlésbe ragad a kis modellnél). */
    private fun gemmaGenerate(llm: LlmInference, instruction: String): String {
        // Összefoglaláshoz a HŰSÉG a fontos: alacsony hőmérséklet (ne találjon ki),
        // de enyhe topK/topP, hogy az alacsony hőmérséklet ellenére se ragadjon ismétlésbe.
        val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(20)
            .setTopP(0.9f)
            .setTemperature(0.2f)
            .build()
        val session = LlmInferenceSession.createFromOptions(llm, sessionOptions)
        return try {
            val prompt = "<start_of_turn>user\n$instruction<end_of_turn>\n<start_of_turn>model\n"
            session.addQueryChunk(prompt)
            session.generateResponse()
                .replace("<end_of_turn>", "").replace("<start_of_turn>", "").trim()
        } finally {
            runCatching { session.close() }
        }
    }

    companion object {
        private const val MAX_TRANSCRIPT_CHARS = 24_000
        private val warmMutex = kotlinx.coroutines.sync.Mutex()
        private val warmScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        private var warmWhisper: Whisper? = null
        private var warmModelPath: String? = null
        private var releaseJob: kotlinx.coroutines.Job? = null
    }
}
