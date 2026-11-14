package com.smartplug.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.smartplug.app.data.prefs.AppPrefs
import com.smartplug.app.domain.gateway.Clock
import com.smartplug.app.domain.gateway.PlugGateway
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Widget de tela inicial: um toque alterna o plug.
 *
 * O trabalho roda aqui mesmo, com `goAsync()` e corrotina de IO, em vez de ir
 * para o WorkManager: o toque precisa de resposta imediata e nao pode ficar
 * preso atras de um trabalho unico pendente.
 */
@AndroidEntryPoint
class PlugWidgetProvider : AppWidgetProvider() {

    @Inject lateinit var gateway: PlugGateway
    @Inject lateinit var prefs: AppPrefs
    @Inject lateinit var clock: Clock

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val view = PlugWidgetRenderer.build(context, PlugWidgetState.read(context))
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, view) }
        runAsync(context, refresh = true)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        appWidgetManager.updateAppWidget(
            appWidgetId,
            PlugWidgetRenderer.build(context, PlugWidgetState.read(context)),
        )
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        runAsync(context, refresh = true)
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Sempre passa pelo super: e ele que dispara a injecao do Hilt e o
        // despacho normal do AppWidgetProvider (onUpdate/onEnabled).
        super.onReceive(context, intent)

        when (intent.action) {
            PlugWidgetRenderer.ACTION_TOGGLE -> runAsync(context, refresh = false)
            PlugWidgetRenderer.ACTION_REFRESH -> runAsync(context, refresh = true)
        }
    }

    /** `goAsync()` mantem o processo vivo ate a corrotina terminar (janela do broadcast). */
    private fun runAsync(context: Context, refresh: Boolean) {
        val appContext = context.applicationContext
        if (PlugWidgetRenderer.widgetIds(appContext).isEmpty()) return

        val pending = goAsync()
        val actions = WidgetActions(gateway, prefs, clock)

        scope.launch {
            try {
                if (refresh) actions.refresh(appContext) else actions.toggle(appContext)
            } catch (t: Throwable) {
                // Nunca deixar o widget "Alternando..." para sempre.
                PlugWidgetState.write(
                    appContext,
                    PlugWidgetState.read(appContext).copy(
                        phase = PlugWidgetPhase.ERROR,
                        message = "Falha inesperada",
                    ),
                )
                PlugWidgetRenderer.updateAll(appContext)
            } finally {
                runCatching { pending.finish() }
            }
        }
    }
}
