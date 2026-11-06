package expo.modules.overlaychat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class OverlayChatModule : Module() {
  private val ctx: Context
    get() = appContext.reactContext ?: throw IllegalStateException("React context lost")

  override fun definition() = ModuleDefinition {
    Name("OverlayChat")

    Function("canDrawOverlays") {
      Settings.canDrawOverlays(ctx)
    }

    Function("openOverlaySettings") {
      val i = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:" + ctx.packageName)
      )
      i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      ctx.startActivity(i)
    }

    Function("start") { dbUrl: String, room: String ->
      val i = Intent(ctx, OverlayService::class.java)
      i.action = OverlayService.ACTION_START
      i.putExtra(OverlayService.EXTRA_DB, dbUrl)
      i.putExtra(OverlayService.EXTRA_ROOM, room)
      if (Build.VERSION.SDK_INT >= 26) {
        ctx.startForegroundService(i)
      } else {
        ctx.startService(i)
      }
    }

    Function("stop") {
      ctx.stopService(Intent(ctx, OverlayService::class.java))
    }

    Function("isRunning") {
      OverlayService.running
    }
  }
}
