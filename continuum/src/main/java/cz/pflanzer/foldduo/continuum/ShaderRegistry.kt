package cz.pflanzer.foldduo.continuum

import android.content.Context
import android.graphics.RuntimeShader
import android.os.SystemClock
import android.util.Log

/**
 * B43 "Výkon jako feature": every AGSL `RuntimeShader` under this module's `res/raw`, discovered
 * at runtime by reflecting over the generated `R.raw` class instead of a hand-kept list — `R.raw`
 * is itself generated at build time from whatever `.agsl` files sit in `res/raw`, so a shader
 * another agent adds there (B37's sheet-bend shader for `DuneWallpaper`, say) is picked up here
 * the moment it lands, with no code change in this file. [FoldShader] keeps its own dedicated,
 * process-wide singleton for `fold_morph` specifically (many call sites already depend on
 * [FoldShader.shared]/[FoldShader.sharedIfReady]); this registry defers to that one for the entry
 * named "fold_morph" and owns the compiled instances for everything else, so nothing is compiled
 * twice.
 */
object ShaderRegistry {
    private const val TAG = "FoldShader"
    private const val FOLD_MORPH_NAME = "fold_morph"

    /** One shader's compiled program plus how long [compileAll] took to compile it, for per-shader logging. */
    data class Compiled(val name: String, val shader: RuntimeShader, val compileMs: Long)

    @Volatile
    private var lastCompiled: Map<String, RuntimeShader> = emptyMap()

    /** Snapshot of whatever [compileAll] has compiled so far in this process; never compiles anything itself. */
    fun compiledIfReady(): Map<String, RuntimeShader> = lastCompiled

    /**
     * Every resource id declared in the generated `R.raw`, name -> id. Reflection over the inner
     * class is the "list the raw dir at build time" the task asks for: `R.raw`'s fields are
     * exactly the files under `res/raw` this module was built with, one int field per basename.
     */
    private fun rawResourceIds(): Map<String, Int> = try {
        R.raw::class.java.fields
            .filter { it.type == Int::class.javaPrimitiveType }
            .associate { it.name to it.getInt(null) }
    } catch (e: Exception) {
        Log.w(TAG, "R.raw reflection failed: ${e.message}")
        emptyMap()
    }

    /**
     * Compiles every raw resource that parses as AGSL, off whatever thread the caller runs this
     * on (each compile is a few ms of SkSL work). A resource that fails to read as text or fails
     * to compile as a shader (there are none today, but a future non-shader `res/raw` file must
     * not be fatal) is logged and skipped, not thrown. `fold_morph` is routed through
     * [FoldShader.shared] so the two entry points into the same shader never diverge.
     */
    fun compileAll(context: Context): List<Compiled> {
        val app = context.applicationContext
        val result = ArrayList<Compiled>()
        for ((name, id) in rawResourceIds()) {
            val startedAt = SystemClock.elapsedRealtime()
            val shader = if (name == FOLD_MORPH_NAME) {
                FoldShader.shared(app)
            } else {
                val src = try {
                    app.resources.openRawResource(id).bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    Log.w(TAG, "raw/$name: not readable as text (${e.message}), skipped"); null
                }
                src?.let {
                    try {
                        RuntimeShader(it)
                    } catch (e: Exception) {
                        Log.w(TAG, "raw/$name: not an AGSL shader (${e.message}), skipped"); null
                    }
                }
            }
            if (shader != null) result += Compiled(name, shader, SystemClock.elapsedRealtime() - startedAt)
        }
        lastCompiled = result.associate { it.name to it.shader }
        return result
    }
}
