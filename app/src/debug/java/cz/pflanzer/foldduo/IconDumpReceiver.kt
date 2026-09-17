package cz.pflanzer.foldduo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Process
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Debug-only, manifest-declared (so it works without Home in front): dumps the glass backing-removal stages for one
 * package into `filesDir/icondump/<pkg>/` — `layer.png` (the 144 px foreground buffer [IconPixels.stripBacking]
 * sees), `keyed.png` (after [IconPixels.keyOutBacking]), `glyph.png` (the re-centred glyph when that branch is
 * taken), `glass.png` (the final tile) and `info.json` (backing colour / coverage, bounds, branch). The style is the
 * saved one; `shape` / `effect` extras override it.
 *
 *   adb shell am broadcast -a cz.pflanzer.foldduo.DEBUG_ICON_DUMP --es pkg com.google.android.apps.maps
 *   adb shell run-as cz.pflanzer.foldduo tar -C files -cf - icondump | tar -x
 */
class IconDumpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra("pkg") ?: return
        val saved = runCatching { JSONObject(context.getSharedPreferences("launcher", 0).getString("state", "{}") ?: "{}").iconStyle() }
            .getOrDefault(IconStyle())
        fun key(value: String) = value.lowercase().replace("_", "").replace("-", "")
        val shape = intent.getStringExtra("shape")?.let { raw -> IconShape.entries.firstOrNull { key(it.name) == key(raw) } }
        val effect = intent.getStringExtra("effect")?.let { raw -> IconEffect.entries.firstOrNull { key(it.name) == key(raw) } }
        val style = saved.copy(shape = shape ?: saved.shape, effect = effect ?: saved.effect)
        val pending = goAsync()
        Thread {
            runCatching { dump(context.applicationContext, pkg, style) }.onFailure { Log.e(TAG, "icon dump $pkg failed", it) }
            pending.finish()
        }.start()
    }

    private fun dump(context: Context, pkg: String, style: IconStyle) {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val info = launcherApps.getActivityList(pkg, Process.myUserHandle()).firstOrNull()
        val source = info?.getBadgedIcon(0) ?: context.packageManager.getApplicationIcon(pkg)
        val adaptive = source as? AdaptiveIconDrawable
        val dir = File(context.filesDir, "icondump/$pkg").apply { mkdirs() }
        val json = JSONObject().put("pkg", pkg).put("style", style.toString())
            .put("drawable", source.javaClass.name).put("adaptive", adaptive != null)
            .put("foreground", adaptive?.foreground?.javaClass?.name ?: JSONObject.NULL)
            .put("background", adaptive?.background?.javaClass?.name ?: JSONObject.NULL)
            .put("intrinsic", "${source.intrinsicWidth}x${source.intrinsicHeight}")
        val inset = IconRenderer.safeInset(adaptive)
        val layer = IconRenderer.layerPixels(source, adaptive)
        if (layer != null) {
            writePng(File(dir, "layer.png"), layer)
            json.put("layerBounds", IconPixels.glyphBounds(layer, ICON_PX, ICON_PX)?.toList() ?: JSONObject.NULL)
            json.put("layerOpaque", layer.count { (it ushr 24) >= IconPixels.GLYPH_ALPHA_THRESHOLD })
            val backing = IconPixels.detectOpaqueBacking(layer, ICON_PX, ICON_PX, inset)
            json.put("backing", backing?.let { JSONObject().put("color", "#%08X".format(it.color)).put("coverage", it.coverage)
                .put("ringInset", it.inset).put("circleRing", it.circle) } ?: JSONObject.NULL)
            if (backing != null) {
                val keyed = layer.copyOf()
                val removed = IconPixels.keyOutBacking(keyed, ICON_PX, ICON_PX, backing.color, IconPixels.BACKING_TOLERANCE,
                    backing.inset, backing.circle)
                writePng(File(dir, "keyed.png"), keyed)
                json.put("removed", removed)
                json.put("keyedBounds", IconPixels.glyphBounds(keyed, ICON_PX, ICON_PX)?.toList() ?: JSONObject.NULL)
                json.put("keyedOpaque", keyed.count { (it ushr 24) >= IconPixels.GLYPH_ALPHA_THRESHOLD })
            }
            val stripped = IconPixels.stripBacking(layer, ICON_PX, ICON_PX, inset)
            json.put("branch", stripped.javaClass.simpleName)
            if (stripped is StrippedIcon.Glyph) {
                writePng(File(dir, "glyph.png"), stripped.pixels)
                json.put("glyphBounds", IconPixels.glyphBounds(stripped.pixels, ICON_PX, ICON_PX)?.toList() ?: JSONObject.NULL)
            }
        } else json.put("branch", "no foreground")
        val glass = IconRenderer.glass(source, style.copy(effect = style.effect.takeUnless { it == IconEffect.None } ?: IconEffect.Glass))
        File(dir, "glass.png").outputStream().use { glass.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(dir, "info.json").writeText(json.toString(2))
        Log.i(TAG, "icon dump $pkg -> $dir: $json")
    }

    private fun writePng(file: File, pixels: IntArray) {
        val bitmap = Bitmap.createBitmap(pixels, ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object { const val TAG = "FoldDuoIcons" }
}
