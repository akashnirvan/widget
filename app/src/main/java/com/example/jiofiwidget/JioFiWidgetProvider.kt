package com.example.jiofiwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.AsyncTask
import android.view.View
import android.widget.RemoteViews
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

class JioFiWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.example.jiofiwidget.ACTION_REFRESH"
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Update each widget
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    // Handle button clicks
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, JioFiWidgetProvider::class.java))
            onUpdate(context, appWidgetManager, ids)
        }
    }

    private fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        // Construct the RemoteViews object
        val views = RemoteViews(context.packageName, R.layout.widget_jiofi)

        // Show loader/spinner (optional, or just status text)
        views.setViewVisibility(R.id.text_status, View.VISIBLE)
        views.setText(R.id.text_status, "Updating...")

        // Intent for manual refresh
        val intent = Intent(context, JioFiWidgetProvider::class.java).apply {
            action = ACTION_REFRESH
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.btn_refresh, pendingIntent)

        // Trigger Network Task
        FetchBatteryTask(views, appWidgetId, appWidgetManager).execute()
        
        // Push update for "Updating..." state
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    // Async Task for Network (Deprecated in modern Android but perfect for "Minimal Dependencies/Code")
    // For production, WorkManager or Coroutines is better, but this is a single file solution.
    private class FetchBatteryTask(
        val views: RemoteViews,
        val widgetId: Int,
        val manager: AppWidgetManager
    ) : AsyncTask<Void, Void, BatteryResult>() {

        data class BatteryResult(val level: Int?, val isCharging: Boolean, val error: String?)

        override fun doInBackground(vararg params: Void?): BatteryResult {
            var urlConnection: HttpURLConnection? = null
            try {
                // Try fetching
                val url = URL("http://jiofi.local.html/") // Or 192.168.225.1
                urlConnection = url.openConnection() as HttpURLConnection
                urlConnection.connectTimeout = 3000
                urlConnection.readTimeout = 3000
                urlConnection.requestMethod = "GET"

                if (urlConnection.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(urlConnection.inputStream))
                    val sb = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        sb.append(line)
                    }
                    val html = sb.toString()

                    // Regex Match
                    // Matches "Battery Level: 80%" or "capacity: 80"
                    val p = Pattern.compile("battery\\s*level\\s*[:=]\\s*(\\d+)", Pattern.CASE_INSENSITIVE)
                    val m = p.matcher(html)
                    
                    if (m.find()) {
                        val level = m.group(1)?.toIntOrNull()
                        val isCharging = html.contains("charging", ignoreCase = true)
                        return BatteryResult(level, isCharging, null)
                    }
                    
                    // Fallback pattern
                    val p2 = Pattern.compile("capacity\\s*[:=]\\s*(\\d+)", Pattern.CASE_INSENSITIVE)
                    val m2 = p2.matcher(html)
                     if (m2.find()) {
                        val level = m2.group(1)?.toIntOrNull()
                        val isCharging = html.contains("charging", ignoreCase = true)
                        return BatteryResult(level, isCharging, null)
                    }

                    return BatteryResult(null, false, "Parse Err")
                } else {
                    return BatteryResult(null, false, "HTTP ${urlConnection.responseCode}")
                }

            } catch (e: Exception) {
                return BatteryResult(null, false, "Offline")
            } finally {
                urlConnection?.disconnect()
            }
        }

        override fun onPostExecute(result: BatteryResult) {
            if (result.level != null) {
                // Success
                views.setViewVisibility(R.id.text_status, View.GONE)
                views.setText(R.id.text_percentage, "${result.level}%")
                views.setProgressBar(R.id.progress_battery, 100, result.level, false)

                // Charging Status
                if (result.isCharging) {
                    views.setViewVisibility(R.id.icon_charging, View.VISIBLE)
                    // Green color is handled by source drawable tint or setInt
                    views.setInt(R.id.progress_battery, "setProgressTintList", Color.GREEN) 
                } else {
                    views.setViewVisibility(R.id.icon_charging, View.GONE)
                    views.setInt(R.id.progress_battery, "setProgressTintList", Color.LTGRAY) 
                }

            } else {
                // Failure
                views.setViewVisibility(R.id.text_status, View.VISIBLE)
                views.setText(R.id.text_status, result.error ?: "Err")
                views.setText(R.id.text_percentage, "--")
                views.setProgressBar(R.id.progress_battery, 100, 0, false)
                views.setViewVisibility(R.id.icon_charging, View.GONE)
            }

            manager.updateAppWidget(widgetId, views)
        }
    }
}
