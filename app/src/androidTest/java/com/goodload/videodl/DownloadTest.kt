package com.goodload.videodl

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DownloadTest {

    private fun log(msg: String) = msg.chunked(3000).forEach { Log.i("VideoDLTest", it) }

    private fun run(url: String, audio: Boolean) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        YoutubeDL.getInstance().init(ctx)
        FFmpeg.getInstance().init(ctx)
        try {
            log("update: " + YoutubeDL.getInstance().updateYoutubeDL(ctx))
        } catch (e: Exception) { log("update failed: $e") }
        log("yt-dlp version: " + YoutubeDL.getInstance().version(ctx))
        log("qjs: " + Downloader.qjsPath(ctx))

        val dir = File(ctx.cacheDir, "test-$audio").apply { deleteRecursively(); mkdirs() }
        val req = Downloader.buildRequest(ctx, url, audio, dir)
        try {
            val r = YoutubeDL.getInstance().execute(req, "test-$audio")
            log("OUT:\n" + r.out)
            log("ERR:\n" + r.err)
        } catch (e: Exception) {
            log("EXCEPTION:\n" + e.message)
            // GitHub 서버(데이터센터 IP)는 YouTube가 봇으로 차단함 → 폰에서는 해당 없음, 건너뜀
            assumeFalse("YouTube가 CI 서버 IP를 봇으로 차단 (폰과 무관)",
                url.contains("youtu") && (e.message ?: "").contains("not a bot"))
            throw e
        }
        val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        files.forEach { log("RESULT FILE: ${it.name} ${it.length()} bytes") }
        assertTrue("no output file", files.any { it.length() > 100_000 })
    }

    // 1) 실제 샘플 YouTube 영상
    @Test fun youtubeVideo() = run("https://youtu.be/A0h3PbJUCf8", false)
    @Test fun youtubeAudio() = run("https://youtu.be/A0h3PbJUCf8", true)

    // 2) 다운로드 → 저장 → ffmpeg(mp3 변환) 전체 흐름 검증용 일반 영상
    @Test fun genericVideo() = run("https://www.w3schools.com/html/mov_bbb.mp4", false)
    @Test fun genericAudio() = run("https://www.w3schools.com/html/mov_bbb.mp4", true)
}
