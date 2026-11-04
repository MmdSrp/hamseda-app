package ir.vibracenter.hamseda

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

/** Remembers the colours of the site's current theme (light / dark) between launches. */
object ThemeStore {
    private const val PREFS = "hamseda_theme"

    /** bg = page background, top = colour under the status bar, bottom = colour above the navigation bar. */
    data class Theme(val bg: Int, val top: Int, val bottom: Int, val accent: Int)

    fun default(ctx: Context): Theme {
        val bg = ContextCompat.getColor(ctx, R.color.bg)
        return Theme(bg, bg, bg, ContextCompat.getColor(ctx, R.color.brand))
    }

    fun load(ctx: Context): Theme {
        val d = default(ctx)
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains("bg")) return d
        return Theme(
            p.getInt("bg", d.bg),
            p.getInt("top", d.top),
            p.getInt("bottom", d.bottom),
            p.getInt("accent", d.accent)
        )
    }

    fun save(ctx: Context, t: Theme) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("bg", t.bg)
            .putInt("top", t.top)
            .putInt("bottom", t.bottom)
            .putInt("accent", t.accent)
            .apply()
    }

    fun accent(ctx: Context): Int = load(ctx).accent

    /** An accent colour that is neither almost black nor almost white (it must stay visible in the notification shade). */
    fun safeAccent(ctx: Context, candidate: Int): Int {
        val l = ColorUtils.calculateLuminance(candidate)
        return if (l >= 0.06 && l <= 0.92) candidate else ContextCompat.getColor(ctx, R.color.brand)
    }
}
