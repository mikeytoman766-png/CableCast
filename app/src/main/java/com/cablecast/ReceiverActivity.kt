package com.cablecast

import android.app.Activity
import android.app.PendingIntent
import android.content.*
import android.graphics.Color
import android.hardware.usb.*
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/** Receiver = USB HOST. Switches the sender into AOA mode, reads H.264, decodes onto the screen. */
class ReceiverActivity : Activity(), SurfaceHolder.Callback {
    private val permAction = "com.cablecast.USB_PERM"
    private lateinit var usb: UsbManager
    private lateinit var tv: TextView
    private var surface: Surface? = null
    @Volatile private var streaming = false
    @Volatile private var activeConn: UsbDeviceConnection? = null
    private var lastSwitch = 0L
    private val lines = ArrayDeque<String>()
    private val ui = Handler(Looper.getMainLooper())

    private val rx = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> { log("USB attached"); scan() }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> { log("USB detached"); activeConn?.close() }
                permAction -> {
                    val d = i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (d != null && i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) handle(d)
                    else log("USB permission denied")
                }
            }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        usb = getSystemService(UsbManager::class.java)
        val root = FrameLayout(this)
        val sv = SurfaceView(this); sv.holder.addCallback(this)
        root.addView(sv)
        tv = TextView(this).apply {
            setTextColor(Color.GREEN); textSize = 13f; setPadding(24, 72, 24, 24); setBackgroundColor(0x88000000.toInt())
        }
        root.addView(tv, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        setContentView(root)
        val f = IntentFilter(permAction).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, rx, f, ContextCompat.RECEIVER_EXPORTED)
        log("Receiver ready. Waiting for the other phone...")
    }

    override fun onNewIntent(i: Intent?) { super.onNewIntent(i); scan() }
    override fun surfaceCreated(h: SurfaceHolder) { surface = h.surface; scan() }
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
    override fun surfaceDestroyed(h: SurfaceHolder) { surface = null }
    override fun onDestroy() { super.onDestroy(); streaming = false; activeConn?.close(); unregisterReceiver(rx) }

    private fun log(m: String) = runOnUiThread {
        lines.addLast(m); while (lines.size > 12) lines.removeFirst()
        tv.text = lines.joinToString("\n")
    }

    private fun scan() {
        if (surface == null) return
        val devs = usb.deviceList.values
        log("Scan: ${devs.size} USB device(s)")
        for (d in devs) {
            log("Found %04x:%04x %s".format(d.vendorId, d.productId, d.productName ?: ""))
            if (usb.hasPermission(d)) handle(d)
            else {
                log("Asking USB permission...")
                usb.requestPermission(d, PendingIntent.getBroadcast(this, 0,
                    Intent(permAction).setPackage(packageName), PendingIntent.FLAG_MUTABLE))
            }
        }
    }

    private fun handle(d: UsbDevice) {
        val isAcc = d.vendorId == 0x18D1 && d.productId in 0x2D00..0x2D05
        if (isAcc) startStream(d) else switchToAccessory(d)
    }

    private fun switchToAccessory(d: UsbDevice) {
        val now = System.currentTimeMillis()
        if (now - lastSwitch < 4000) { log("Switch skipped (too soon)"); return }
        lastSwitch = now
        thread {
            val c = usb.openDevice(d) ?: run { log("openDevice failed"); return@thread }
            val v = ByteArray(2)
            val r = c.controlTransfer(0xC0, 51, 0, 0, v, 2, 1000)
            val ver = (v[1].toInt() shl 8) or (v[0].toInt() and 0xFF)
            if (r < 0 || ver < 1) { log("Other phone: AOA not supported (r=$r)"); c.close(); return@thread }
            log("AOA v$ver OK, switching other phone...")
            val ids = listOf("CableCast", "Screen", "Screen share over USB", "1.0", "https://example.com", "0001")
            ids.forEachIndexed { idx, s ->
                val bytes = s.toByteArray() + 0
                c.controlTransfer(0x40, 52, 0, idx, bytes, bytes.size, 1000)
            }
            val r2 = c.controlTransfer(0x40, 53, 0, 0, null, 0, 1000)
            log("Switch sent ($r2). Waiting for reconnect...")
            c.close()
        }
    }

    private class Rd(val c: UsbDeviceConnection, val ep: UsbEndpoint) {
        val chunk = ByteArray(16384); var p = 0; var l = 0
        fun read(dst: ByteArray, n: Int) {
            var o = 0
            while (o < n) {
                if (p == l) { l = c.bulkTransfer(ep, chunk, chunk.size, 0); p = 0; if (l <= 0) throw IOException("usb read $l") }
                val k = minOf(n - o, l - p)
                System.arraycopy(chunk, p, dst, o, k); p += k; o += k
            }
        }
        fun int(): Int { val b = ByteArray(4); read(b, 4); return ByteBuffer.wrap(b).int }
    }

    private fun startStream(d: UsbDevice) {
        if (streaming) return
        streaming = true
        thread(name = "usb-reader") {
            var dec: MediaCodec? = null
            var conn: UsbDeviceConnection? = null
            try {
                conn = usb.openDevice(d) ?: throw IOException("open failed")
                activeConn = conn
                var inEp: UsbEndpoint? = null
                for (i in 0 until d.interfaceCount) {
                    val itf = d.getInterface(i)
                    // skip the ADB interface (class 255, subclass 66, protocol 1)
                    if (itf.interfaceClass == 255 && itf.interfaceSubclass == 66 && itf.interfaceProtocol == 1) continue
                    for (e in 0 until itf.endpointCount) {
                        val ep = itf.getEndpoint(e)
                        if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_IN) {
                            conn.claimInterface(itf, true); inEp = ep
                            log("Using interface $i")
                            break
                        }
                    }
                    if (inEp != null) break
                }
                val rd = Rd(conn, inEp ?: throw IOException("no bulk IN endpoint"))
                log("Accessory open. Waiting for video from sender...")
                val w = rd.int(); val h = rd.int()
                if (w !in 16..4096 || h !in 16..4096) throw IOException("bad size ${w}x$h")
                log("Video size ${w}x$h")
                val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
                if (Build.VERSION.SDK_INT >= 30) fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                dec.configure(fmt, surface, null, 0)
                dec.start()
                val info = MediaCodec.BufferInfo()
                var data = ByteArray(1 shl 20)
                var frames = 0
                var hidden = false
                while (streaming) {
                    val len = rd.int(); val flags = rd.int()
                    if (len == -1) throw IOException("sender stopped")
                    if (len < 0 || len > 16_000_000) throw IOException("bad packet $len")
                    if (len > data.size) data = ByteArray(len)
                    rd.read(data, len)
                    val idx = dec.dequeueInputBuffer(200_000)
                    if (idx >= 0) {
                        dec.getInputBuffer(idx)!!.apply { clear(); put(data, 0, len) }
                        dec.queueInputBuffer(idx, 0, len, System.nanoTime() / 1000, flags)
                    }
                    while (true) {
                        val o = dec.dequeueOutputBuffer(info, 0)
                        if (o >= 0) { dec.releaseOutputBuffer(o, true); frames++ } else break
                    }
                    if (frames >= 5 && !hidden) { hidden = true; log("Video playing"); runOnUiThread { tv.visibility = View.GONE } }
                }
            } catch (e: Exception) {
                log("Stream ended: ${e.message}")
            } finally {
                streaming = false; activeConn = null
                runCatching { dec?.stop(); dec?.release() }; runCatching { conn?.close() }
                runOnUiThread { tv.visibility = View.VISIBLE; ui.postDelayed({ if (!streaming) scan() }, 2000) }
            }
        }
    }
}
