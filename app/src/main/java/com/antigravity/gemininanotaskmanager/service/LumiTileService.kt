package com.antigravity.gemininanotaskmanager.service

import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.antigravity.gemininanotaskmanager.presentation.assistant.AssistantActivity

/** Tile de Ajustes rápidos: abre Lumi escuchando desde cualquier pantalla (desliza hacia abajo → Lumi). */
class LumiTileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "Lumi"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = "Habla o escribe"
            updateTile()
        }
    }

    override fun onClick() {
        val intent = AssistantActivity.intent(this, startListening = true, compact = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: solo se admite con PendingIntent
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
