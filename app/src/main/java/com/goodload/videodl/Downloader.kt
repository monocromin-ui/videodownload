package com.goodload.videodl

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

/** 앱·서비스·테스트가 같은 로직을 쓰도록 한 곳에 모아둠 */
object Downloader {

    @Volatile private var inited = false

    /** yt-dlp + python + ffmpeg 준비 (한 번만 실행, 여러 곳에서 불러도 안전) */
    @Synchronized
    fun ensureInit(ctx: Context, onStatus: (String) -> Unit = {}) {
        if (inited) return
        YoutubeDL.getInstance().init(ctx)
        FFmpeg.getInstance().init(ctx)
        onStatus("yt-dlp 최신 버전 확인 중…")
        try { YoutubeDL.getInstance().updateYoutubeDL(ctx) } catch (_: Exception) { }
        inited = true
    }

    /** 앱에 같이 들어있는 QuickJS(JS 엔진) 경로. YouTube 서명/n값 해독용 */
    fun qjsPath(ctx: Context): String? {
        val f = File(ctx.applicationInfo.nativeLibraryDir, "libqjs.so")
        return if (f.exists()) f.absolutePath else null
    }

    fun buildRequest(ctx: Context, url: String, audio: Boolean, dir: File): YoutubeDLRequest {
        val req = YoutubeDLRequest(url)
        req.addOption("-o", dir.absolutePath + "/%(title).150B.%(ext)s")
        req.addOption("--no-playlist")
        req.addOption("--no-mtime")
        req.addOption("--retries", "5")
        req.addOption("--fragment-retries", "5")
        req.addOption("--verbose")
        qjsPath(ctx)?.let { req.addOption("--js-runtimes", "quickjs:$it") }
        if (audio) {
            req.addOption("-x")
            req.addOption("--audio-format", "mp3")
        } else {
            req.addOption("-f", "bv*[ext=mp4][height<=1080]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b")
            req.addOption("--merge-output-format", "mp4")
        }
        return req
    }

    /** 앱 캐시에 받은 파일을 공용 Download/VideoDL 폴더로 복사 (권한 불필요, Android 10+) */
    fun saveToDownloads(ctx: Context, f: File): String {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase())
            ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VideoDL")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw Exception("저장 위치를 만들 수 없어요")
        ctx.contentResolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
        return f.name
    }

    /** 실패 시 네트워크 상태 + 로그에 나온 서버 주소 DNS 조회 결과 */
    fun diagnose(ctx: Context, log: String): String {
        val sb = StringBuilder("==== 진단 ====\n")
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val an = cm.activeNetwork
            val caps = an?.let { cm.getNetworkCapabilities(it) }
            val type = when {
                an == null -> "없음 (이 앱의 네트워크가 차단된 상태)"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "모바일 데이터"
                else -> "기타"
            }
            val saver = when (cm.restrictBackgroundStatus) {
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "켜짐(이 앱 제한됨)"
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "켜짐(이 앱 허용)"
                else -> "꺼짐"
            }
            val lp = an?.let { cm.getLinkProperties(it) }
            sb.append("활성 네트워크: $type\n데이터 절약 모드: $saver\n")
            sb.append("DNS 서버: ${lp?.dnsServers?.joinToString { it.hostAddress ?: "" } ?: "-"}\n")
        } catch (e: Exception) { sb.append("네트워크 정보 없음: $e\n") }
        val hosts = Regex("https?://([A-Za-z0-9.-]+)").findAll(log).map { it.groupValues[1] }
            .filter { "googlevideo" in it || "youtube" in it }.distinct().take(3).toList()
        for (h in hosts) {
            sb.append(h).append(" -> ")
            try { sb.append(java.net.InetAddress.getAllByName(h).joinToString { it.hostAddress ?: "" }) }
            catch (e: Exception) { sb.append("조회 실패: ${e.message}") }
            sb.append("\n")
        }
        return sb.toString()
    }
}
