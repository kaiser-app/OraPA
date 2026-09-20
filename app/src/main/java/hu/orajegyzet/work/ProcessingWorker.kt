package hu.orajegyzet.work

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.*
import hu.orajegyzet.App
import hu.orajegyzet.MainActivity
import hu.orajegyzet.ai.GeminiPipeline
import hu.orajegyzet.ai.ModeSelector
import hu.orajegyzet.ai.OfflinePipeline
import hu.orajegyzet.data.Db
import hu.orajegyzet.data.NoteStatus
import hu.orajegyzet.data.Settings
import java.io.File

/**
 * Egy felvétel feldolgozása: mód kiválasztása (offline/online), pipeline
 * futtatása, eredmény mentése, majd a nyers hangfájl AZONNALI törlése
 * (adatminimalizálás). Hiba esetén a hang megmarad és a worker újrapróbál.
 */
class ProcessingWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n = Notification.Builder(applicationContext, App.CH_REC)
            .setSmallIcon(hu.orajegyzet.R.drawable.ic_stat_note)
            .setContentTitle("Jegyzet feldolgozása")
            .setContentText("A felvétel átírása és összefoglalása folyamatban…")
            .setOngoing(true)
            .build()
        return if (android.os.Build.VERSION.SDK_INT >= 34)
            ForegroundInfo(FG_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else
            ForegroundInfo(FG_ID, n)
    }

    override suspend fun doWork(): Result {
        val noteId = inputData.getLong(KEY_NOTE_ID, -1)
        if (noteId < 0) return Result.failure()
        // előtérbe tesszük, hogy a rendszer (HyperOS) ne lője ki feldolgozás közben
        runCatching { setForeground(getForegroundInfo()) }
        val db = Db.get(applicationContext)
        val dao = db.noteDao()
        val note = dao.byId(noteId) ?: return Result.failure()
        val settings = Settings.get(applicationContext)
        val online = ModeSelector.choose(applicationContext, settings)
        val dateLabel = "${note.dateIso} %d:%02d".format(note.startMin / 60, note.startMin % 60)
        val resummarize = inputData.getBoolean(KEY_RESUMMARIZE, false)

        // ÚJRA-ÖSSZEFOGLALÁS: nem kell hangfájl, a meglévő leiratból dolgozik
        if (resummarize) {
            if (note.transcript.isBlank()) {
                dao.update(note.copy(status = NoteStatus.ERROR, error = "Nincs leirat az újra-összefoglaláshoz"))
                return Result.failure()
            }
            dao.update(note.copy(status = NoteStatus.PROCESSING))
            return try {
                val pipeline = if (online)
                    GeminiPipeline(settings.geminiApiKey, settings.geminiModel)
                else OfflinePipeline(applicationContext, settings.useGemma)
                val r = kotlinx.coroutines.withTimeout(8 * 60_000L) {
                    pipeline.resummarize(note.transcript, note.subject, dateLabel, note.lessonId != null)
                }
                dao.update(note.copy(status = NoteStatus.DONE, summary = r.summary,
                    structured = r.structured, keywords = r.keywords.joinToString(", "), error = ""))
                Result.success()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                dao.update(note.copy(status = NoteStatus.DONE, error = "Újra-összefoglalás hiba: ${e.message}"))
                Result.failure()
            }
        }

        val audio = note.audioPath?.let { File(it) }
        if (audio == null || !audio.exists()) {
            dao.update(note.copy(status = NoteStatus.ERROR, error = "Hiányzó hangfájl"))
            return Result.failure()
        }

        dao.update(note.copy(status = NoteStatus.PROCESSING))

        return try {
            val r = if (online) {
                GeminiPipeline(settings.geminiApiKey, settings.geminiModel)
                    .process(audio.absolutePath, note.subject, dateLabel, note.lessonId != null)
            } else {
                val pipeline = OfflinePipeline(
                    applicationContext, settings.useGemma, settings.whisperThreads,
                    settings.keepModelWarm, settings.warmMinutes, settings.summaryBlockChars,
                    settings.whisperChunkSec, settings.whisperOverlapSec)
                if (settings.stagedDisplay) {
                    // 1) előbb a leirat — darabonként élőben frissül
                    val transcript = kotlinx.coroutines.withTimeout(30 * 60_000L) {
                        pipeline.transcribeOnly(audio.absolutePath) { partial ->
                            dao.update(note.copy(transcript = partial, processedOnline = false,
                                summary = "(leirat készül…)", error = ""))
                        }
                    }
                    dao.update(note.copy(transcript = transcript, processedOnline = false,
                        summary = "(összefoglaló készül…)", error = ""))
                    // 2) majd az összefoglaló — utólag csordul be
                    kotlinx.coroutines.withTimeout(10 * 60_000L) {
                        pipeline.finishSummary(transcript, note.subject, dateLabel, note.lessonId != null)
                    }
                } else {
                    kotlinx.coroutines.withTimeout(30 * 60_000L) {
                        pipeline.process(audio.absolutePath, note.subject, dateLabel, note.lessonId != null)
                    }
                }
            }
            dao.update(
                note.copy(
                    status = NoteStatus.DONE,
                    transcript = r.transcript,
                    summary = r.summary,
                    structured = r.structured,
                    keywords = r.keywords.joinToString(", "),
                    processedOnline = online,
                    error = ""
                )
            )
            // Kinyert döntések és feladatok mentése az aktív projekthez
            val targetProjectId = note.projectId ?: "prj-01"
            r.decisions.forEach { dec ->
                db.projectEventDao().insert(dec.copy(projectId = targetProjectId, sourceMeetingId = note.id.toString()))
            }
            r.tasks.forEach { task ->
                db.ganttTaskDao().insert(task.copy(projectId = targetProjectId))
            }
            // A nyers hang nem törlődik azonnal: a beállított ideig megmarad
            // (visszahallgatás, mentés, újra-feldolgozás), utána egy késleltetett worker törli.
            AudioCleanupWorker.schedule(applicationContext, audio.absolutePath, noteId, settings.audioRetentionMin)
            notifyDone(note.subject, noteId)
            Result.success()
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            dao.update(note.copy(status = NoteStatus.ERROR,
                error = "Offline feldolgozás időtúllépés (8 perc). Próbáld a kisebb q5 Whisper modellt, vagy kapcsold ki a Gemmát a Beállításokban."))
            Result.failure()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < 2) {
                dao.update(note.copy(status = NoteStatus.QUEUED, error = e.message ?: "ismeretlen hiba"))
                Result.retry()
            } else {
                dao.update(note.copy(status = NoteStatus.ERROR, error = e.message ?: "ismeretlen hiba"))
                Result.failure()
            }
        }
    }

    private fun notifyDone(subject: String, noteId: Long) {
        val pi = PendingIntent.getActivity(
            applicationContext, noteId.toInt(),
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(applicationContext, App.CH_DONE)
            .setSmallIcon(hu.orajegyzet.R.drawable.ic_stat_note)
            .setContentTitle("Kész a jegyzet: $subject")
            .setContentText("Koppints a megnyitáshoz")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        applicationContext.getSystemService(NotificationManager::class.java)
            .notify(1000 + noteId.toInt(), n)
    }

    companion object {
        const val KEY_NOTE_ID = "noteId"
        const val KEY_RESUMMARIZE = "resummarize"
        const val FG_ID = 4242

        fun resummarize(ctx: Context, noteId: Long) {
            val req = OneTimeWorkRequestBuilder<ProcessingWorker>()
                .setInputData(workDataOf(KEY_NOTE_ID to noteId, KEY_RESUMMARIZE to true))
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork("resummarize_$noteId", ExistingWorkPolicy.REPLACE, req)
        }

        fun enqueue(ctx: Context, noteId: Long) {
            val req = OneTimeWorkRequestBuilder<ProcessingWorker>()
                .setInputData(workDataOf(KEY_NOTE_ID to noteId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork("process_$noteId", ExistingWorkPolicy.KEEP, req)
        }
    }
}
