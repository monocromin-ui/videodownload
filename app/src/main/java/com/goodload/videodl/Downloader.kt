package com.goodload.videodl

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

/** 앱과 테스트가 같은 다운로드 옵션을 쓰도록 한 곳에 모아둠 */
object Downloader {

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
        req.addOption("--retries", "3")
        req.addOption("--fragment-retries", "3")
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
}
