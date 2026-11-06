package expo.modules.overlaychat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.math.abs

class OverlayService : Service() {

  companion object {
    const val ACTION_START = "expo.modules.overlaychat.START"
    const val ACTION_STOP = "expo.modules.overlaychat.STOP"
    const val EXTRA_DB = "db"
    const val EXTRA_ROOM = "room"
    private const val CHANNEL_ID = "secret_chat_sharing"
    private const val NOTIF_ID = 4107
    private const val SHOW_MS = 10000L
    private const val CLEANUP_MS = 60000L
    private const val MATCH = WindowManager.LayoutParams.MATCH_PARENT

    @Volatile
    var running = false
  }

  private val main = Handler(Looper.getMainLooper())
  private lateinit var wm: WindowManager

  private var dbUrl = ""
  private var room = ""
  private val me = UUID.randomUUID().toString().substring(0, 8)
  private val seen = HashSet<String>()

  @Volatile
  private var alive = false
  private var poller: Thread? = null

  private var displayLayer: FrameLayout? = null
  private var button: TextView? = null
  private var inputLayer: FrameLayout? = null
  private var editor: EditText? = null
  private var editorFx = 0f
  private var editorFy = 0f

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
    try {
      addDisplayLayer()
      addButton()
    } catch (e: Exception) {
      stopSelf()
      return START_NOT_STICKY
    }
    alive = true
    running = true
    startPolling()
    return START_NOT_STICKY
  }

  private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

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
      WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    if (!touchable) {
      flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    }
    val lp = WindowManager.LayoutParams(MATCH, MATCH, overlayType(), flags, PixelFormat.TRANSLUCENT)
    lp.gravity = Gravity.TOP or Gravity.START
    if (Build.VERSION.SDK_INT >= 28) {
      lp.layoutInDisplayCutoutMode =
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
    if (touchable) {
      lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or
        WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
    }
    return lp
  }

  private fun addDisplayLayer() {
    val layer = FrameLayout(this)
    val lp = fullScreenParams(false)
    // Android 12+ blocks touches through overlays that are more than 80 percent opaque.
    lp.alpha = 0.8f
    wm.addView(layer, lp)
    displayLayer = layer
  }

  private fun addButton() {
    val size = dp(56)
    val btn = TextView(this)
    btn.text = "Aa"
    btn.textSize = 18f
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
          if (!moved) toggleInput()
          true
        }
        else -> false
      }
    }
    wm.addView(btn, lp)
    button = btn
  }

  private fun toggleInput() {
    if (inputLayer == null) openInput() else closeInput()
  }

  private fun openInput() {
    if (inputLayer != null) return
    val layer = FrameLayout(this)
    layer.setBackgroundColor(0x66000000)

    val hint = TextView(this)
    hint.text = "Jahan likhna hai wahan tap karo"
    hint.setTextColor(Color.WHITE)
    hint.textSize = 16f
    val hlp = FrameLayout.LayoutParams(
      FrameLayout.LayoutParams.WRAP_CONTENT,
      FrameLayout.LayoutParams.WRAP_CONTENT
    )
    hlp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
    hlp.topMargin = dp(56)
    layer.addView(hint, hlp)

    val cancel = TextView(this)
    cancel.text = "Cancel"
    cancel.setTextColor(Color.WHITE)
    cancel.textSize = 16f
    cancel.setPadding(dp(16), dp(10), dp(16), dp(10))
    val cbg = GradientDrawable()
    cbg.cornerRadius = dp(18).toFloat()
    cbg.setColor(0xCCFF4D6D.toInt())
    cancel.background = cbg
    cancel.setOnClickListener { closeInput() }
    val clp = FrameLayout.LayoutParams(
      FrameLayout.LayoutParams.WRAP_CONTENT,
      FrameLayout.LayoutParams.WRAP_CONTENT
    )
    clp.gravity = Gravity.TOP or Gravity.END
    clp.topMargin = dp(40)
    clp.rightMargin = dp(16)
    layer.addView(cancel, clp)

    layer.setOnTouchListener { _, ev ->
      if (ev.action == MotionEvent.ACTION_UP) {
        placeEditor(layer, ev.x, ev.y)
      }
      true
    }

    wm.addView(layer, fullScreenParams(true))
    inputLayer = layer
  }

  private fun placeEditor(layer: FrameLayout, tapX: Float, tapY: Float) {
    val w = dp(240)
    val h = dp(52)
    val x = tapX.toInt().coerceIn(0, (layer.width - w).coerceAtLeast(0))
    val y = tapY.toInt().coerceIn(0, (layer.height - h).coerceAtLeast(0))
    editorFx = x.toFloat() / layer.width.coerceAtLeast(1)
    editorFy = y.toFloat() / layer.height.coerceAtLeast(1)

    val lp = FrameLayout.LayoutParams(w, FrameLayout.LayoutParams.WRAP_CONTENT)
    lp.leftMargin = x
    lp.topMargin = y

    val existing = editor
    if (existing != null) {
      existing.layoutParams = lp
      existing.requestFocus()
      return
    }

    val et = EditText(this)
    et.hint = "Likho..."
    et.setTextColor(Color.WHITE)
    et.setHintTextColor(0x99FFFFFF.toInt())
    et.textSize = 18f
    et.setPadding(dp(14), dp(10), dp(14), dp(10))
    et.inputType = InputType.TYPE_CLASS_TEXT
    et.imeOptions = EditorInfo.IME_ACTION_SEND
    et.setSingleLine(true)
    et.filters = arrayOf(InputFilter.LengthFilter(140))
    val ebg = GradientDrawable()
    ebg.cornerRadius = dp(14).toFloat()
    ebg.setColor(0xEE101F4D.toInt())
    ebg.setStroke(dp(2), 0xFF4F8CFF.toInt())
    et.background = ebg
    et.setOnEditorActionListener { v, actionId, _ ->
      if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
        val t = v.text.toString().trim()
        val fx = editorFx
        val fy = editorFy
        closeInput()
        if (t.isNotEmpty()) sendMessage(fx, fy, t)
        true
      } else {
        false
      }
    }
    layer.addView(et, lp)
    editor = et
    et.requestFocus()
    et.postDelayed({
      try {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(et, 0)
      } catch (e: Exception) {
      }
    }, 150)
  }

  private fun closeInput() {
    val layer = inputLayer ?: return
    try {
      val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
      imm.hideSoftInputFromWindow(layer.windowToken, 0)
    } catch (e: Exception) {
    }
    try {
      wm.removeView(layer)
    } catch (e: Exception) {
    }
    inputLayer = null
    editor = null
  }

  private fun showText(fx: Float, fy: Float, text: String, mine: Boolean) {
    val layer = displayLayer ?: return
    val tv = TextView(this)
    tv.text = text
    tv.setTextColor(Color.WHITE)
    tv.textSize = 18f
    tv.maxWidth = dp(260)
    tv.setPadding(dp(12), dp(8), dp(12), dp(8))
    val bg = GradientDrawable()
    bg.cornerRadius = dp(14).toFloat()
    bg.setColor(if (mine) 0xF04F8CFF.toInt() else 0xF0FF6FAE.toInt())
    tv.background = bg
    tv.visibility = View.INVISIBLE
    layer.addView(
      tv,
      FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT
      )
    )
    tv.post {
      val maxX = (layer.width - tv.width).coerceAtLeast(0).toFloat()
      val maxY = (layer.height - tv.height).coerceAtLeast(0).toFloat()
      tv.x = (fx * layer.width).coerceIn(0f, maxX)
      tv.y = (fy * layer.height).coerceIn(0f, maxY)
      tv.visibility = View.VISIBLE
    }
    main.postDelayed({ layer.removeView(tv) }, SHOW_MS)
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
      if (code !in 200..299) throw RuntimeException("HTTP " + code)
      return text
    } finally {
      c.disconnect()
    }
  }

  private fun sendMessage(fx: Float, fy: Float, text: String) {
    showText(fx, fy, text, true)
    val base = dbUrl + "/rooms/" + room + "/msgs"
    Thread {
      try {
        val body = JSONObject()
          .put("from", me)
          .put("x", fx.toDouble())
          .put("y", fy.toDouble())
          .put("t", text)
          .put("ts", JSONObject().put(".sv", "timestamp"))
        val resp = http("POST", base + ".json", body.toString())
        val key = JSONObject(resp).optString("name")
        if (key.isNotEmpty()) {
          // Keep the database tidy: remove the message after a minute.
          main.postDelayed({
            Thread {
              try {
                http("DELETE", base + "/" + key + ".json", null)
              } catch (e: Exception) {
              }
            }.start()
          }, CLEANUP_MS)
        }
      } catch (e: Exception) {
      }
    }.start()
  }

  private fun startPolling() {
    val url = dbUrl + "/rooms/" + room + "/msgs.json?orderBy=%22%24key%22&limitToLast=30"
    val t = Thread {
      var first = true
      while (alive) {
        try {
          val raw = http("GET", url, null)
          if (raw.isNotEmpty() && raw != "null") {
            val obj = JSONObject(raw)
            val keys = ArrayList<String>()
            val iter = obj.keys()
            while (iter.hasNext()) keys.add(iter.next())
            keys.sort()
            for (k in keys) {
              if (!seen.add(k)) continue
              if (first) continue
              val m = obj.optJSONObject(k) ?: continue
              if (m.optString("from") == me) continue
              val fx = m.optDouble("x", 0.0).toFloat()
              val fy = m.optDouble("y", 0.0).toFloat()
              val text = m.optString("t")
              if (text.isNotEmpty()) {
                main.post { if (alive) showText(fx, fy, text, false) }
              }
            }
          }
          first = false
        } catch (e: Exception) {
          // Network problem: try again on the next round.
        }
        try {
          Thread.sleep(1200)
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
    closeInput()
    try {
      displayLayer?.let { wm.removeView(it) }
    } catch (e: Exception) {
    }
    try {
      button?.let { wm.removeView(it) }
    } catch (e: Exception) {
    }
    displayLayer = null
    button = null
    stopForeground(STOP_FOREGROUND_REMOVE)
    super.onDestroy()
  }
}
