package com.goodload.videodl

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DownloadTest {

    private fun log(msg: String) = msg.chunked(3000).forEach { Log.i("VideoDLTest", it) }

    private fun run(audio: Boolean) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        YoutubeDL.getInstance().init(ctx)
        FFmpeg.getInstance().init(ctx)
        try {
            log("update: " + YoutubeDL.getInstance().updateYoutubeDL(ctx))
        } catch (e: Exception) { log("update failed: $e") }
        log("yt-dlp version: " + YoutubeDL.getInstance().version(ctx))
        log("qjs: " + Downloader.qjsPath(ctx))

        val dir = File(ctx.cacheDir, "test-$audio").apply { deleteRecursively(); mkdirs() }
        val req = Downloader.buildRequest(ctx, "https://youtu.be/A0h3PbJUCf8", audio, dir)
        try {
            val r = YoutubeDL.getInstance().execute(req, "test-$audio")
            log("OUT:\n" + r.out)
            log("ERR:\n" + r.err)
        } catch (e: Exception) {
            log("EXCEPTION:\n" + e.message)
            throw e
        }
        val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        files.forEach { log("RESULT FILE: ${it.name} ${it.length()} bytes") }
        assertTrue("no output file", files.any { it.length() > 100_000 })
    }

    @Test fun video() = run(false)
    @Test fun audio() = run(true)
}
