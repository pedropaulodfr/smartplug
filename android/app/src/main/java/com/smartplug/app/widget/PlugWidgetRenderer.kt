package com.smartplug.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.smartplug.app.R
import java.util.Locale

/** Desenha o widget nos estados desligado / ligado / alternando / erro. */
object PlugWidgetRenderer {

    const val ACTION_TOGGLE = "com.smartplug.app.action.WIDGET_TOGGLE"
    const val ACTION_REFRESH = "com.smartplug.app.action.WIDGET_REFRESH"

    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = widgetIds(context, manager)
        if (ids.isEmpty()) return

        val ui = PlugWidgetState.read(context)
        val view = build(context, ui)
        ids.forEach { manager.updateAppWidget(it, view) }
    }

    fun widgetIds(context: Context, manager: AppWidgetManager = AppWidgetManager.getInstance(context)): IntArray =
        manager.getAppWidgetIds(ComponentName(context, PlugWidgetProvider::class.java))

    fun build(context: Context, ui: PlugWidgetUi): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_plug)

        val background: Int
        val iconColor: Int
        val titleColor: Int
        val title: String
        val subtitle: String

        when {
            ui.phase == PlugWidgetPhase.ERROR -> {
                background = R.drawable.widget_bg_error
                iconColor = R.color.widget_icon_off
                titleColor = R.color.widget_title_error
                title = context.getString(R.string.widget_title_error)
                subtitle = ui.message ?: context.getString(R.string.widget_subtitle_unknown)
            }

            ui.phase == PlugWidgetPhase.PENDING -> {
                background = R.drawable.widget_bg_pending
                iconColor = R.color.widget_icon_pending
                titleColor = R.color.widget_title_pending
                title = context.getString(R.string.widget_title_pending)
                subtitle = context.getString(R.string.widget_subtitle_unknown)
            }

            ui.on == true -> {
                background = R.drawable.widget_bg_on
                iconColor = R.color.widget_icon_on
                titleColor = R.color.widget_title_on
                title = context.getString(R.string.widget_title_on)
                subtitle = measurements(ui) ?: context.getString(R.string.widget_subtitle_hint_on)
            }

            ui.on == false -> {
                background = R.drawable.widget_bg_off
                iconColor = R.color.widget_icon_off
                titleColor = R.color.widget_title_off
                title = context.getString(R.string.widget_title_off)
                subtitle = context.getString(R.string.widget_subtitle_hint_off)
            }

            else -> {
                background = R.drawable.widget_bg_off
                iconColor = R.color.widget_icon_off
                titleColor = R.color.widget_title_off
                title = context.getString(R.string.widget_title_off)
                subtitle = context.getString(R.string.widget_subtitle_unknown)
            }
        }

        rv.setInt(R.id.widget_root, "setBackgroundResource", background)
        rv.setInt(R.id.widget_icon, "setColorFilter", ContextCompat.getColor(context, iconColor))
        rv.setTextViewText(R.id.widget_title, title)
        rv.setTextColor(R.id.widget_title, ContextCompat.getColor(context, titleColor))
        rv.setTextViewText(R.id.widget_subtitle, subtitle)
        rv.setTextColor(R.id.widget_subtitle, ContextCompat.getColor(context, R.color.widget_subtitle))
        rv.setContentDescription(R.id.widget_root, "$title. $subtitle")
        rv.setViewVisibility(R.id.widget_subtitle, if (subtitle.isBlank()) View.GONE else View.VISIBLE)

        rv.setOnClickPendingIntent(R.id.widget_root, toggleIntent(context))
        return rv
    }

    /** "238,9 V · 49,8 W" quando ha leitura; senao null. */
    private fun measurements(ui: PlugWidgetUi): String? {
        val voltage = ui.voltageV?.takeIf { it > 1.0 }
        val power = ui.powerW?.takeIf { it >= 0.0 }
        val parts = buildList {
            if (voltage != null) add(String.format(BR, "%.1f V", voltage))
            if (power != null && power > 0.5) add(String.format(BR, "%.0f W", power))
        }
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }

    private fun toggleIntent(context: Context): PendingIntent {
        val intent = Intent(context, PlugWidgetProvider::class.java).setAction(ACTION_TOGGLE)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private val BR: Locale = Locale.forLanguageTag("pt-BR")
}
