package hu.orajegyzet

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import hu.orajegyzet.bell.BellScheduler
import hu.orajegyzet.data.Db
import hu.orajegyzet.work.DigestWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createChannels()
        appScope.launch {
            BellScheduler.rescheduleAll(this@App, Db.get(this@App).lessonDao())
            DigestWorker.schedule(this@App)
            // korábbi futásból "feldolgozás alatt" ragadt jegyzetek feloldása
            runCatching { Db.get(this@App).noteDao().failStuckProcessing() }
            // régi nyers hangfájlok törlése a beállított megtartási idő szerint
            runCatching {
                val min = hu.orajegyzet.data.Settings.get(this@App).audioRetentionMin
                hu.orajegyzet.work.AudioCleanupWorker.sweep(this@App, min)
            }
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_BELL, "Becsengetés", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Értesítés óra kezdetekor a felvétel indításához"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_REC, "Felvétel", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Folyamatban lévő órai felvétel"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "Kész jegyzet", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    companion object {
        const val CH_BELL = "bell"
        const val CH_REC = "rec"
        const val CH_DONE = "done"
    }
}
