package com.goodload.videodl

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var urlInput: EditText
    private lateinit var audioOnly: CheckBox
    private lateinit var downloadBtn: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        urlInput = findViewById(R.id.url)
        audioOnly = findViewById(R.id.audioOnly)
        downloadBtn = findViewById(R.id.download)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)

        downloadBtn.setOnClickListener { startDownload(urlInput.text.toString()) }
        findViewById<Button>(R.id.copyLog).setOnClickListener {
            val s = DownloadService.state
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("VideoDL log", s.log.ifEmpty { status.text }))
            toast("로그를 복사했어요")
        }

        // 진행 상황 알림 표시용 (Android 13+)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)

        // 첫 실행 준비(30초 정도)를 미리 해둠
        if (!DownloadService.state.running) {
            status.text = "준비 중… (처음 실행은 30초 정도 걸려요)"
            thread {
                try {
                    Downloader.ensureInit(applicationContext) { msg -> runOnUiThread { if (!DownloadService.state.running) status.text = msg } }
                    runOnUiThread { if (!DownloadService.state.running && !DownloadService.state.done)
                        status.text = "링크를 붙여넣거나, YouTube에서 공유 → VideoDL 을 누르세요\n\n받는 동안 다른 앱을 쓰거나 화면을 꺼도 됩니다." }
                } catch (e: Exception) { runOnUiThread { status.text = "초기화 실패: ${e.message}" } }
            }
        }
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        DownloadService.listener = { render(it) }
        if (DownloadService.state.running || DownloadService.state.done) render(DownloadService.state)
    }

    override fun onPause() {
        super.onPause()
        DownloadService.listener = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = Regex("https?://\\S+").find(text)?.value ?: return
        urlInput.setText(url)
        startDownload(url)
    }

    private fun startDownload(raw: String) {
        val url = raw.trim()
        if (url.isEmpty()) { toast("링크를 입력하세요"); return }
        if (DownloadService.state.running) { toast("이미 다운로드 중입니다"); return }
        startForegroundService(Intent(this, DownloadService::class.java)
            .putExtra("url", url).putExtra("audio", audioOnly.isChecked))
        render(DownloadService.State("시작하는 중…", 0, running = true))
    }

    private fun render(s: DownloadService.State) {
        status.text = s.text
        progress.visibility = if (s.running) View.VISIBLE else View.GONE
        progress.progress = s.progress
        downloadBtn.isEnabled = !s.running
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
