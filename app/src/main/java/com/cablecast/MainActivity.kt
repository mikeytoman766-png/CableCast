package com.cablecast

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.*
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private val usb by lazy { getSystemService(UsbManager::class.java) }
    private val permAction = "com.cablecast.ACC_PERM"
    private val h = Handler(Looper.getMainLooper())
    private lateinit var tv: TextView

    private val tick = object : Runnable {
        override fun run() {
            val n = usb.accessoryList?.size ?: 0
            tv.text = "USB link: ${if (n > 0) "FOUND" else "not found"}\nStream: ${Status.text}"
            h.postDelayed(this, 500)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 120, 48, 48) }
        l.addView(Button(this).apply {
            text = "SEND\n(this phone shares its screen)"
            setOnClickListener { startSender() }
        })
        l.addView(Button(this).apply {
            text = "RECEIVE\n(this phone shows the other screen)"
            setOnClickListener { startActivity(Intent(this@MainActivity, ReceiverActivity::class.java)) }
        })
        l.addView(Button(this).apply {
            text = "STOP SENDING"
            setOnClickListener { stopService(Intent(this@MainActivity, SenderService::class.java)); Status.text = "Stopped" }
        })
        tv = TextView(this).apply { textSize = 16f; setPadding(0, 48, 0, 0) }
        l.addView(tv)
        setContentView(l)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
    }

    override fun onResume() { super.onResume(); h.post(tick) }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }

    private fun startSender() {
        val acc = usb.accessoryList?.firstOrNull()
        if (acc == null) {
            Toast.makeText(this, "No USB link yet. Connect the cable and open RECEIVE on the other phone first.", Toast.LENGTH_LONG).show()
            return
        }
        if (usb.hasPermission(acc)) { startCapture(); return }
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                unregisterReceiver(this)
                if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) startCapture()
            }
        }
        ContextCompat.registerReceiver(this, rx, IntentFilter(permAction), ContextCompat.RECEIVER_NOT_EXPORTED)
        val pi = PendingIntent.getBroadcast(this, 0, Intent(permAction).setPackage(packageName), PendingIntent.FLAG_MUTABLE)
        usb.requestPermission(acc, pi)
    }

    private fun startCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), 1)
    }

    @Deprecated("simple demo")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        if (req == 1 && res == RESULT_OK && data != null) {
            Status.text = "Starting..."
            startForegroundService(Intent(this, SenderService::class.java).putExtra("code", res).putExtra("data", data))
        }
    }
}
