package com.goodload.videodl

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var urlInput: EditText
    private lateinit var audioOnly: CheckBox
    private lateinit var downloadBtn: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

    @Volatile private var lastLog = ""
    @Volatile private var ready = false
    @Volatile private var busy = false
    private var pendingUrl: String? = null

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
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("VideoDL log", lastLog.ifEmpty { status.text }))
            toast("로그를 복사했어요")
        }
        downloadBtn.isEnabled = false
        status.text = "준비 중… (처음 실행은 30초 정도 걸려요)"

        // yt-dlp + python + ffmpeg 초기화 (백그라운드)
        thread {
            try {
                YoutubeDL.getInstance().init(applicationContext)
                FFmpeg.getInstance().init(applicationContext)
                ui { status.text = "yt-dlp 최신 버전 확인 중…" }
                try {
                    YoutubeDL.getInstance().updateYoutubeDL(applicationContext)
                } catch (_: Exception) { /* 오프라인이면 내장 버전 사용 */ }
                ready = true
                ui {
                    downloadBtn.isEnabled = true
                    status.text = "링크를 붙여넣거나, YouTube에서 공유 → VideoDL 을 누르세요"
                    pendingUrl?.let { pendingUrl = null; startDownload(it) }
                }
            } catch (e: Exception) {
                ui { status.text = "초기화 실패: ${e.message}" }
            }
        }

        handleShare(intent)
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
        if (ready) startDownload(url) else pendingUrl = url
    }

    private fun startDownload(raw: String) {
        val url = raw.trim()
        if (url.isEmpty()) { toast("링크를 입력하세요"); return }
        if (busy) { toast("이미 다운로드 중입니다"); return }
        busy = true
        downloadBtn.isEnabled = false
        progress.progress = 0
        progress.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val audio = audioOnly.isChecked
        status.text = "영상 정보 확인 중…"

        thread {
            val dir = File(cacheDir, "dl").apply { deleteRecursively(); mkdirs() }
            try {
                val req = YoutubeDLRequest(url)
                req.addOption("-o", dir.absolutePath + "/%(title).150B.%(ext)s")
                req.addOption("--no-playlist")
                // 일부 망(IPv6/사설 DNS)에서 googlevideo 주소 조회 실패(Errno 7) 방지
                req.addOption("--force-ipv4")
                req.addOption("--retries", "3")
                req.addOption("--fragment-retries", "3")
                req.addOption("--verbose")
                if (audio) {
                    req.addOption("-x")
                    req.addOption("--audio-format", "mp3")
                } else {
                    req.addOption("-f", "bv*[ext=mp4][height<=1080]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b")
                    req.addOption("--merge-output-format", "mp4")
                }

                YoutubeDL.getInstance().execute(req, "job") { p, eta, _ ->
                    ui {
                        progress.progress = p.toInt().coerceIn(0, 100)
                        status.text = "다운로드 중 ${p.toInt()}%  (남은 시간 약 ${eta}초)"
                    }
                }

                ui { status.text = "저장 중…" }
                val saved = dir.listFiles()?.filter { it.isFile }?.map { saveToDownloads(it) } ?: emptyList()
                ui {
                    status.text = if (saved.isEmpty()) "파일을 찾지 못했어요"
                    else "완료! 내 파일 > 다운로드 > VideoDL\n\n" + saved.joinToString("\n")
                }
            } catch (e: Exception) {
                val log = e.message ?: e.toString()
                lastLog = log + "\n\n" + dnsCheck(log)
                val tail = lastLog.lines().filter { !it.startsWith("[debug] ") || "Invoking" in it || "Request Handlers" in it }
                    .takeLast(25).joinToString("\n")
                ui { status.text = "실패 (아래 '로그 복사' 눌러서 보내주세요)\n\n$tail" }
            } finally {
                dir.deleteRecursively()
                busy = false
                ui {
                    downloadBtn.isEnabled = true
                    progress.visibility = View.GONE
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }

    /** 앱 캐시에 받은 파일을 공용 Download/VideoDL 폴더로 복사 (권한 불필요, Android 10+) */
    private fun saveToDownloads(f: File): String {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase())
            ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VideoDL")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw Exception("저장 위치를 만들 수 없어요")
        contentResolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
        return f.name
    }

    /** 로그에 나온 서버 주소를 안드로이드 시스템 DNS로 직접 조회해서 결과를 붙임 */
    private fun dnsCheck(log: String): String {
        val hosts = Regex("https?://([A-Za-z0-9.-]+)").findAll(log).map { it.groupValues[1] }
            .filter { "googlevideo" in it || "youtube" in it }.distinct().take(5).toList()
        val net = try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val lp = cm.getLinkProperties(cm.activeNetwork)
            "DNS 서버: ${lp?.dnsServers?.joinToString { it.hostAddress ?: "" }}, 개인DNS: ${lp?.privateDnsServerName ?: (if (lp?.isPrivateDnsActive == true) "자동" else "꺼짐")}"
        } catch (e: Exception) { "네트워크 정보 없음" }
        val sb = StringBuilder("==== 진단 ====\n$net\n")
        for (h in hosts) {
            sb.append(h).append(" -> ")
            try { sb.append(java.net.InetAddress.getAllByName(h).joinToString { it.hostAddress ?: "" }) }
            catch (e: Exception) { sb.append("조회 실패: ${e.message}") }
            sb.append("\n")
        }
        return sb.toString()
    }

    private fun ui(block: () -> Unit) = runOnUiThread { block() }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
