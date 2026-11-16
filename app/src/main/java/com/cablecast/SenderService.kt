package com.cablecast

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.usb.UsbManager
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.*
import kotlin.concurrent.thread

object Status { @Volatile var text: String = "Idle" }

/** Captures the screen, encodes H.264, and streams it to the receiver. Reconnects automatically. */
class SenderService : Service() {
    @Volatile private var running = false
    private var proj: MediaProjection? = null
    private var enc: MediaCodec? = null
    private var vd: VirtualDisplay? = null
    @Volatile private var pfd: ParcelFileDescriptor? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("cc", "CableCast", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "cc").setContentTitle("Sharing screen over USB")
            .setSmallIcon(android.R.drawable.ic_menu_share).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)

        try {
            val code = i?.getIntExtra("code", 0) ?: return fail("No start data")
            @Suppress("DEPRECATION") val data = i.getParcelableExtra<Intent>("data") ?: return fail("No capture permission")

            proj = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
            proj!!.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, null)

            val dm = Resources.getSystem().displayMetrics
            val sc = minOf(1f, 1920f / maxOf(dm.widthPixels, dm.heightPixels))
            val w = (dm.widthPixels * sc).toInt() / 16 * 16
            val h = (dm.heightPixels * sc).toInt() / 16 * 16

            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 60)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
            }
            val e = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            e.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surf = e.createInputSurface()
            e.start()
            enc = e
            vd = proj!!.createVirtualDisplay("cc", w, h, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surf, null, null)
            Status.text = "Encoder running ${w}x$h"
            running = true
            thread(name = "usb-writer") { pump(e, w, h) }
        } catch (ex: Exception) {
            return fail("Setup failed: ${ex.message}")
        }
        return START_NOT_STICKY
    }

    private fun pump(e: MediaCodec, w: Int, h: Int) {
        val usb = getSystemService(UsbManager::class.java)
        val info = MediaCodec.BufferInfo()
        var out: DataOutputStream? = null
        var cfg: ByteArray? = null
        var needKey = true
        var sent = 0L
        var lastUi = 0L
        while (running) {
            if (out == null) {
                val acc = usb.accessoryList?.firstOrNull()
                if (acc == null || !usb.hasPermission(acc)) {
                    Status.text = "Waiting for USB link (${if (acc == null) "accessory not found" else "no permission"})"
                } else try {
                    pfd = usb.openAccessory(acc) ?: throw IOException("openAccessory returned null")
                    val o = DataOutputStream(BufferedOutputStream(FileOutputStream(pfd!!.fileDescriptor), 65536))
                    o.writeInt(w); o.writeInt(h)
                    cfg?.let { o.writeInt(it.size); o.writeInt(MediaCodec.BUFFER_FLAG_CODEC_CONFIG); o.write(it) }
                    o.flush()
                    needKey = true
                    e.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                    out = o
                    Status.text = "USB connected, waiting for first frame"
                } catch (ex: Exception) {
                    Status.text = "USB open failed: ${ex.message}"
                    runCatching { pfd?.close() }; pfd = null
                    Thread.sleep(500)
                }
            }
            val idx = e.dequeueOutputBuffer(info, 100_000)
            if (idx < 0) continue
            val buf = e.getOutputBuffer(idx)!!
            val arr = ByteArray(info.size)
            buf.position(info.offset); buf.limit(info.offset + info.size); buf.get(arr)
            val fl = info.flags
            e.releaseOutputBuffer(idx, false)
            val isCfg = (fl and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
            val isKey = (fl and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
            if (isCfg) cfg = arr
            val o = out ?: continue
            if (needKey && !isKey && !isCfg) continue
            if (isKey) needKey = false
            try {
                o.writeInt(arr.size); o.writeInt(fl); o.write(arr); o.flush()
                sent += arr.size
                val now = System.currentTimeMillis()
                if (now - lastUi > 1000) { Status.text = "Streaming: ${sent / 1024} KB sent"; lastUi = now }
            } catch (ex: IOException) {
                Status.text = "USB write failed (${ex.message}); reconnecting"
                out = null; needKey = true
                runCatching { pfd?.close() }; pfd = null
            }
        }
    }

    private fun fail(msg: String): Int { Status.text = msg; stopSelf(); return START_NOT_STICKY }

    override fun onDestroy() {
        running = false
        runCatching { vd?.release() }; runCatching { enc?.stop(); enc?.release() }
        runCatching { proj?.stop() }; runCatching { pfd?.close() }
    }
}
