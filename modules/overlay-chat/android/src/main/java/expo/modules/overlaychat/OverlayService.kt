package expo.modules.overlaychat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs

private class Seg(
  val x0: Float,
  val y0: Float,
  val x1: Float,
  val y1: Float,
  val color: Int,
  val t: Long
)

// Full-screen transparent view that shows finger strokes and fades them out.
private class DrawView(c: Context, private val showMs: Long) : View(c) {
  private val segs = ArrayList<Seg>()
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

  init {
    paint.style = Paint.Style.STROKE
    paint.strokeCap = Paint.Cap.ROUND
    paint.strokeWidth = 5f * c.resources.displayMetrics.density
  }

  fun addSeg(x0: Float, y0: Float, x1: Float, y1: Float, color: Int) {
    segs.add(Seg(x0, y0, x1, y1, color, SystemClock.uptimeMillis()))
    postInvalidateOnAnimation()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val now = SystemClock.uptimeMillis()
    val w = width.toFloat()
    val h = height.toFloat()
    val fade = 2000L
    val iter = segs.iterator()
    var any = false
    while (iter.hasNext()) {
      val s = iter.next()
      val age = now - s.t
      if (age > showMs) {
        iter.remove()
        continue
      }
      val a = if (age > showMs - fade) (showMs - age).toFloat() / fade else 1f
      paint.color = s.color
      paint.alpha = (255 * a).toInt().coerceIn(0, 255)
      canvas.drawLine(s.x0 * w, s.y0 * h, s.x1 * w, s.y1 * h, paint)
      any = true
    }
    if (any) postInvalidateOnAnimation()
  }
}

class OverlayService : Service() {

  companion object {
    const val ACTION_START = "expo.modules.overlaychat.START"
    const val ACTION_STOP = "expo.modules.overlaychat.STOP"
    const val EXTRA_DB = "db"
    const val EXTRA_ROOM = "room"
    private const val CHANNEL_ID = "secret_chat_sharing"
    private const val NOTIF_ID = 4107
    private const val SHOW_MS = 10000L
    private const val CLEANUP_MS = 30000L
    private const val FLUSH_MS = 150L
    private const val POLL_MS = 400L
    private const val MATCH = WindowManager.LayoutParams.MATCH_PARENT
    private val MINE = Color.parseColor("#4F8CFF")
    private val OTHER = Color.parseColor("#FF6FAE")

    @Volatile
    var running = false

    @Volatile
    var lastError = ""
  }

  private val main = Handler(Looper.getMainLooper())
  private val io = Executors.newSingleThreadExecutor()
  private lateinit var wm: WindowManager

  private var dbUrl = ""
  private var room = ""
  private val me = UUID.randomUUID().toString().substring(0, 8)
  private val seen = HashSet<String>()

  @Volatile
  private var alive = false
  private var poller: Thread? = null

  private var drawView: DrawView? = null
  private var button: TextView? = null
  private var inputLayer: FrameLayout? = null

  private val pending = ArrayList<Float>()
  private var carryX = -1f
  private var carryY = -1f
  private var lastFx = 0f
  private var lastFy = 0f
  private var penDown = false

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      stopSelf()
      return START_NOT_STICKY
    }
    val db = (intent?.getStringExtra(EXTRA_DB) ?: "").trim().trimEnd('/')
    val r = (intent?.getStringExtra(EXTRA_ROOM) ?: "").trim()

    // Must be called quickly after startForegroundService.
    showNotification(r)

    if (db.isEmpty() || r.isEmpty() || !Settings.canDrawOverlays(this)) {
      stopSelf()
      return START_NOT_STICKY
    }
    if (alive) return START_NOT_STICKY

    dbUrl = db
    room = r
    lastError = ""
    try {
      addDisplayLayer()
      addButton()
    } catch (e: Exception) {
      lastError = "overlay: " + (e.message ?: e.javaClass.simpleName)
      stopSelf()
      return START_NOT_STICKY
    }
    alive = true
    running = true
    startPolling()
    return START_NOT_STICKY
  }

  private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

  private fun fmt(v: Float): String = String.format(Locale.US, "%.3f", v)

  @Suppress("DEPRECATION")
  private fun overlayType(): Int {
    return if (Build.VERSION.SDK_INT >= 26) {
      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
      WindowManager.LayoutParams.TYPE_PHONE
    }
  }

  @Suppress("DEPRECATION")
  private fun showNotification(roomName: String) {
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= 26) {
      nm.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Secret Chat sharing", NotificationManager.IMPORTANCE_LOW)
      )
    }
    val immutable = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
    val stopPi = PendingIntent.getService(
      this,
      1,
      Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
      PendingIntent.FLAG_UPDATE_CURRENT or immutable
    )
    val launch = packageManager.getLaunchIntentForPackage(packageName)
    val builder = if (Build.VERSION.SDK_INT >= 26) {
      Notification.Builder(this, CHANNEL_ID)
    } else {
      Notification.Builder(this)
    }
    builder
      .setSmallIcon(android.R.drawable.stat_notify_chat)
      .setContentTitle("Sharing ON")
      .setContentText("Room: " + roomName + ". Band karne ke liye Stop dabao.")
      .setOngoing(true)
      .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
    if (launch != null) {
      builder.setContentIntent(
        PendingIntent.getActivity(this, 2, launch, PendingIntent.FLAG_UPDATE_CURRENT or immutable)
      )
    }
    val n = builder.build()
    if (Build.VERSION.SDK_INT >= 34) {
      startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    } else {
      startForeground(NOTIF_ID, n)
    }
  }

  private fun fullScreenParams(touchable: Boolean): WindowManager.LayoutParams {
    var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
      WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    if (!touchable) {
      flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    }
    val lp = WindowManager.LayoutParams(MATCH, MATCH, overlayType(), flags, PixelFormat.TRANSLUCENT)
    lp.gravity = Gravity.TOP or Gravity.START
    if (Build.VERSION.SDK_INT >= 28) {
      lp.layoutInDisplayCutoutMode =
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
    return lp
  }

  private fun addDisplayLayer() {
    val v = DrawView(this, SHOW_MS)
    val lp = fullScreenParams(false)
    // Android 12+ blocks touches through overlays that are more than 80 percent opaque.
    lp.alpha = 0.8f
    wm.addView(v, lp)
    drawView = v
  }

  private fun addButton() {
    val size = dp(56)
    val btn = TextView(this)
    btn.text = "Draw"
    btn.textSize = 13f
    btn.gravity = Gravity.CENTER
    btn.setTextColor(Color.WHITE)
    val bg = GradientDrawable()
    bg.shape = GradientDrawable.OVAL
    bg.setColor(0xF04F8CFF.toInt())
    btn.background = bg

    val lp = WindowManager.LayoutParams(
      size,
      size,
      overlayType(),
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
      PixelFormat.TRANSLUCENT
    )
    lp.gravity = Gravity.TOP or Gravity.START
    lp.x = resources.displayMetrics.widthPixels - dp(72)
    lp.y = dp(160)

    var downX = 0f
    var downY = 0f
    var startX = 0
    var startY = 0
    var moved = false
    btn.setOnTouchListener { _, ev ->
      when (ev.action) {
        MotionEvent.ACTION_DOWN -> {
          downX = ev.rawX
          downY = ev.rawY
          startX = lp.x
          startY = lp.y
          moved = false
          true
        }
        MotionEvent.ACTION_MOVE -> {
          val dx = ev.rawX - downX
          val dy = ev.rawY - downY
          if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
          if (moved) {
            lp.x = startX + dx.toInt()
            lp.y = startY + dy.toInt()
            wm.updateViewLayout(btn, lp)
          }
          true
        }
        MotionEvent.ACTION_UP -> {
          if (!moved) openInput()
          true
        }
        else -> false
      }
    }
    wm.addView(btn, lp)
    button = btn
  }

  private fun openInput() {
    if (inputLayer != null) return
    val layer = FrameLayout(this)
    layer.setBackgroundColor(0x22000000)

    val touch = View(this)
    layer.addView(touch, FrameLayout.LayoutParams(MATCH, MATCH))
    touch.setOnTouchListener { v, ev ->
      handleTouch(v, ev)
      true
    }

    val hint = TextView(this)
    hint.text = "Ungli se screen par likho ya banao"
    hint.setTextColor(Color.WHITE)
    hint.textSize = 16f
    val hlp = FrameLayout.LayoutParams(
      FrameLayout.LayoutParams.WRAP_CONTENT,
      FrameLayout.LayoutParams.WRAP_CONTENT
    )
    hlp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
    hlp.topMargin = dp(56)
    layer.addView(hint, hlp)

    val done = TextView(this)
    done.text = "Done"
    done.setTextColor(Color.WHITE)
    done.textSize = 16f
    done.setPadding(dp(18), dp(10), dp(18), dp(10))
    val dbg = GradientDrawable()
    dbg.cornerRadius = dp(18).toFloat()
    dbg.setColor(0xCCFF4D6D.toInt())
    done.background = dbg
    done.setOnClickListener { closeInput() }
    val dlp = FrameLayout.LayoutParams(
      FrameLayout.LayoutParams.WRAP_CONTENT,
      FrameLayout.LayoutParams.WRAP_CONTENT
    )
    dlp.gravity = Gravity.TOP or Gravity.END
    dlp.topMargin = dp(40)
    dlp.rightMargin = dp(16)
    layer.addView(done, dlp)

    wm.addView(layer, fullScreenParams(true))
    inputLayer = layer
  }

  private fun closeInput() {
    val layer = inputLayer ?: return
    penDown = false
    flushNow()
    try {
      wm.removeView(layer)
    } catch (e: Exception) {
    }
    inputLayer = null
  }

  private fun handleTouch(v: View, ev: MotionEvent) {
    val w = v.width.coerceAtLeast(1).toFloat()
    val h = v.height.coerceAtLeast(1).toFloat()
    val fx = (ev.x / w).coerceIn(0f, 1f)
    val fy = (ev.y / h).coerceIn(0f, 1f)
    when (ev.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        penDown = true
        carryX = -1f
        carryY = -1f
        lastFx = fx
        lastFy = fy
        pending.clear()
        pending.add(fx)
        pending.add(fy)
        drawView?.addSeg(fx, fy, fx, fy, MINE)
        scheduleFlush()
      }
      MotionEvent.ACTION_MOVE -> {
        drawView?.addSeg(lastFx, lastFy, fx, fy, MINE)
        lastFx = fx
        lastFy = fy
        pending.add(fx)
        pending.add(fy)
      }
      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
        penDown = false
        flushNow()
      }
    }
  }

  private fun scheduleFlush() {
    main.postDelayed({
      flushNow()
      if (penDown) scheduleFlush()
    }, FLUSH_MS)
  }

  private fun flushNow() {
    if (pending.size < 2) return
    val sb = StringBuilder()
    if (carryX >= 0f) {
      sb.append(fmt(carryX)).append(',').append(fmt(carryY)).append(';')
    }
    var i = 0
    while (i + 1 < pending.size) {
      if (i > 0) sb.append(';')
      sb.append(fmt(pending[i])).append(',').append(fmt(pending[i + 1]))
      i += 2
    }
    carryX = pending[pending.size - 2]
    carryY = pending[pending.size - 1]
    pending.clear()
    sendChunk(sb.toString())
  }

  private fun http(method: String, url: String, body: String?): String {
    val c = URL(url).openConnection() as HttpURLConnection
    try {
      c.requestMethod = method
      c.connectTimeout = 8000
      c.readTimeout = 8000
      if (body != null) {
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        OutputStreamWriter(c.outputStream, "UTF-8").use { it.write(body) }
      }
      val code = c.responseCode
      val stream = if (code in 200..299) c.inputStream else c.errorStream
      val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
      if (code !in 200..299) throw RuntimeException("HTTP " + code + " " + text.take(120))
      return text
    } finally {
      c.disconnect()
    }
  }

  private fun sendChunk(p: String) {
    val base = dbUrl + "/rooms/" + room + "/msgs"
    io.execute {
      try {
        val body = JSONObject().put("from", me).put("p", p)
        val resp = http("POST", base + ".json", body.toString())
        val key = JSONObject(resp).optString("name")
        if (key.isNotEmpty()) {
          // Keep the database tidy: remove the chunk after a short while.
          main.post {
            if (alive) {
              main.postDelayed({
                io.execute {
                  try {
                    http("DELETE", base + "/" + key + ".json", null)
                  } catch (e: Exception) {
                  }
                }
              }, CLEANUP_MS)
            }
          }
        }
      } catch (e: Exception) {
        lastError = "send: " + (e.message ?: e.javaClass.simpleName)
      }
    }
  }

  private fun handleRemote(m: JSONObject) {
    val p = m.optString("p")
    if (p.isEmpty()) return
    val pts = ArrayList<Float>()
    for (s in p.split(';')) {
      val xy = s.split(',')
      if (xy.size != 2) continue
      val x = xy[0].toFloatOrNull() ?: continue
      val y = xy[1].toFloatOrNull() ?: continue
      pts.add(x.coerceIn(0f, 1f))
      pts.add(y.coerceIn(0f, 1f))
    }
    if (pts.size < 2) return
    main.post {
      if (!alive) return@post
      val v = drawView ?: return@post
      if (pts.size == 2) v.addSeg(pts[0], pts[1], pts[0], pts[1], OTHER)
      var i = 2
      while (i + 1 < pts.size) {
        v.addSeg(pts[i - 2], pts[i - 1], pts[i], pts[i + 1], OTHER)
        i += 2
      }
    }
  }

  private fun startPolling() {
    val base = dbUrl + "/rooms/" + room + "/msgs.json?orderBy=%22%24key%22"
    val t = Thread {
      var baselined = false
      var lastKey = ""
      while (alive) {
        try {
          val url = if (!baselined) {
            base + "&limitToLast=1"
          } else if (lastKey.isEmpty()) {
            base + "&limitToLast=50"
          } else {
            base + "&startAt=%22" + lastKey + "%22"
          }
          val raw = http("GET", url, null)
          if (raw.isNotEmpty() && raw != "null") {
            val obj = JSONObject(raw)
            val keys = ArrayList<String>()
            val iter = obj.keys()
            while (iter.hasNext()) keys.add(iter.next())
            keys.sort()
            if (!baselined) {
              if (keys.isNotEmpty()) {
                lastKey = keys[keys.size - 1]
                seen.add(lastKey)
              }
            } else {
              for (k in keys) {
                if (!seen.add(k)) continue
                if (k > lastKey) lastKey = k
                val m = obj.optJSONObject(k) ?: continue
                if (m.optString("from") == me) continue
                handleRemote(m)
              }
            }
          }
          baselined = true
          lastError = ""
        } catch (e: Exception) {
          lastError = "poll: " + (e.message ?: e.javaClass.simpleName)
        }
        try {
          Thread.sleep(POLL_MS)
        } catch (e: InterruptedException) {
          break
        }
      }
    }
    t.isDaemon = true
    t.start()
    poller = t
  }

  override fun onDestroy() {
    alive = false
    running = false
    poller?.interrupt()
    main.removeCallbacksAndMessages(null)
    io.shutdown()
    closeInput()
    try {
      drawView?.let { wm.removeView(it) }
    } catch (e: Exception) {
    }
    try {
      button?.let { wm.removeView(it) }
    } catch (e: Exception) {
    }
    drawView = null
    button = null
    stopForeground(STOP_FOREGROUND_REMOVE)
    super.onDestroy()
  }
}
