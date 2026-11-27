package com.goodload.videodl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import kotlin.concurrent.thread

/**
 * 다운로드를 "포그라운드 서비스"로 실행.
 * 앱 화면을 벗어나거나 화면이 꺼져도 안드로이드가 이 앱의 네트워크를 끊지 않음.
 * (화면 밖에서 네트워크가 끊기면 "Errno 7 No address associated with hostname" 발생)
 */
class DownloadService : Service() {

    data class State(
        val text: String = "",
        val progress: Int = 0,
        val running: Boolean = false,
        val done: Boolean = false,
        val failed: Boolean = false,
        val log: String = "",
    )

    companion object {
        private const val CHANNEL = "download"
        private const val NOTI_RUN = 1
        private const val NOTI_DONE = 2
        @Volatile var state = State()
            private set
        var listener: ((State) -> Unit)? = null
        private val main = Handler(Looper.getMainLooper())
        private fun publish(s: State) { state = s; main.post { listener?.invoke(s) } }
    }

    private lateinit var nm: NotificationManager
    private var lastNoti = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "다운로드", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTI_RUN, noti("준비 중…", 0, true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        val url = intent?.getStringExtra("url")
        if (url == null || state.running) return START_NOT_STICKY
        val audio = intent.getBooleanExtra("audio", false)

        publish(State("준비 중…", 0, running = true))
        val wl = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VideoDL:download")
            .apply { acquire(2 * 60 * 60 * 1000L) }

        thread {
            val dir = File(cacheDir, "dl").apply { deleteRecursively(); mkdirs() }
            var result: State
            try {
                Downloader.ensureInit(applicationContext) { update(it, 0) }
                update("영상 정보 확인 중…", 0)
                val req = Downloader.buildRequest(applicationContext, url, audio, dir)
                YoutubeDL.getInstance().execute(req, "job") { p, eta, _ ->
                    update("다운로드 중 ${p.toInt()}%  (남은 시간 약 ${eta}초)", p.toInt())
                }
                update("저장 중…", 100)
                val saved = dir.listFiles()?.filter { it.isFile }
                    ?.map { Downloader.saveToDownloads(applicationContext, it) } ?: emptyList()
                result = if (saved.isEmpty()) State("파일을 찾지 못했어요", done = true, failed = true)
                else State("완료! 내 파일 > 다운로드 > VideoDL\n\n" + saved.joinToString("\n"), 100, done = true)
            } catch (e: Exception) {
                val msg = e.message ?: e.toString()
                val log = msg + "\n\n" + Downloader.diagnose(applicationContext, msg)
                val tail = log.lines().filter { !it.startsWith("[debug] ") }.takeLast(25).joinToString("\n")
                result = State("실패 ('로그 복사' 눌러서 보내주세요)\n\n$tail", done = true, failed = true, log = log)
            } finally {
                dir.deleteRecursively()
                if (wl.isHeld) wl.release()
            }
            publish(result)
            stopForeground(STOP_FOREGROUND_REMOVE)
            nm.notify(NOTI_DONE, noti(if (result.failed) "다운로드 실패" else "다운로드 완료",
                if (result.failed) 0 else 100, false, result.text.lines().getOrNull(2) ?: ""))
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun update(text: String, p: Int) {
        publish(State(text, p, running = true))
        val now = SystemClock.elapsedRealtime()
        if (now - lastNoti > 700) { lastNoti = now; nm.notify(NOTI_RUN, noti(text, p, true)) }
    }

    private fun noti(title: String, p: Int, ongoing: Boolean, sub: String = ""): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(sub)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .apply { if (ongoing) setProgress(100, p, p == 0) }
            .build()
    }
}
