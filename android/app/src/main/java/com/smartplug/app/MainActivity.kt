package com.smartplug.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.smartplug.app.ui.AppNavigation
import com.smartplug.app.ui.theme.SmartPlugTheme
import com.smartplug.app.widget.PlugWidgetProvider
import com.smartplug.app.widget.PlugWidgetRenderer
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SmartPlugTheme { AppNavigation() }
        }
    }

    override fun onStart() {
        super.onStart()
        // Mantem o widget alinhado com o app (o provider ignora se nao ha widget).
        sendBroadcast(
            Intent(this, PlugWidgetProvider::class.java)
                .setAction(PlugWidgetRenderer.ACTION_REFRESH),
        )
    }
}
