package com.example.jiofiwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.regex.Pattern

/**
 * JioFi Battery Widget Provider
 * 
 * A lightweight, robust widget that displays JioFi router battery status.
 * Features:
 * - Minimal resource usage (no heavy dependencies)
 * - Graceful error handling with visual feedback
 * - Manual refresh via tap
 * - Auto-update every 30 minutes (OS scheduled)
 */
class JioFiWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.example.jiofiwidget.ACTION_REFRESH"
        
        // JioFi router addresses (try multiple)
        private val JIOFI_URLS = arrayOf(
            "http://jiofi.local.html/",
            "http://192.168.225.1/"
        )
        
        // Connection timeouts
        private const val CONNECT_TIMEOUT_MS = 4000
        private const val READ_TIMEOUT_MS = 4000
        
        // Background executor for network operations
        private val executor = Executors.newSingleThreadExecutor()
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val widgetIds = appWidgetManager.getAppWidgetIds(
                ComponentName(context, JioFiWidgetProvider::class.java)
            )
            onUpdate(context, appWidgetManager, widgetIds)
        }
    }

    private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_jiofi)
        
        // Set up refresh button click
        val refreshIntent = Intent(context, JioFiWidgetProvider::class.java).apply {
            action = ACTION_REFRESH
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 
            0, 
            refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.btn_refresh, pendingIntent)
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
        
        // Show loading state
        views.setTextViewText(R.id.text_status, "Updating...")
        views.setTextViewText(R.id.text_percentage, "...")
        appWidgetManager.updateAppWidget(widgetId, views)
        
        // Fetch battery status in background
        executor.execute {
            val result = fetchBatteryStatus()
            updateUI(context, appWidgetManager, widgetId, result)
        }
    }

    private fun fetchBatteryStatus(): BatteryResult {
        for (url in JIOFI_URLS) {
            try {
                val result = tryFetchFromUrl(url)
                if (result.success) return result
            } catch (e: Exception) {
                // Try next URL
            }
        }
        return BatteryResult(success = false, level = 0, isCharging = false, error = "Offline")
    }

    private fun tryFetchFromUrl(urlString: String): BatteryResult {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "text/html")
            
            if (connection.responseCode != 200) {
                return BatteryResult(success = false, level = 0, isCharging = false, error = "HTTP ${connection.responseCode}")
            }
            
            val html = BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                reader.readText()
            }
            
            return parseHtmlForBattery(html)
            
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseHtmlForBattery(html: String): BatteryResult {
        // Multiple regex patterns to handle different JioFi firmware versions
        val patterns = arrayOf(
            Pattern.compile("battery\\s*level\\s*[:=]?\\s*(\\d+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("capacity\\s*[:=]?\\s*(\\d+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("batteryLevel\\s*[:=]?\\s*[\"']?(\\d+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\"battery\"\\s*:\\s*(\\d+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile(">(\\d+)\\s*%\\s*</", Pattern.CASE_INSENSITIVE)
        )
        
        for (pattern in patterns) {
            val matcher = pattern.matcher(html)
            if (matcher.find()) {
                val level = matcher.group(1)?.toIntOrNull()
                if (level != null && level in 0..100) {
                    val isCharging = html.contains("charging", ignoreCase = true) ||
                                     html.contains("plugged", ignoreCase = true)
                    return BatteryResult(success = true, level = level, isCharging = isCharging, error = null)
                }
            }
        }
        
        return BatteryResult(success = false, level = 0, isCharging = false, error = "Parse Error")
    }

    private fun updateUI(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int, result: BatteryResult) {
        val views = RemoteViews(context.packageName, R.layout.widget_jiofi)
        
        // Re-set click listeners (required for RemoteViews)
        val refreshIntent = Intent(context, JioFiWidgetProvider::class.java).apply {
            action = ACTION_REFRESH
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.btn_refresh, pendingIntent)
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
        
        if (result.success) {
            // Success: Show battery level
            views.setTextViewText(R.id.text_percentage, "${result.level}%")
            views.setProgressBar(R.id.progress_battery, 100, result.level, false)
            
            if (result.isCharging) {
                views.setTextViewText(R.id.text_status, "Charging")
                views.setImageViewResource(R.id.icon_battery, R.drawable.ic_battery_charging)
            } else {
                views.setTextViewText(R.id.text_status, "On Battery")
                views.setImageViewResource(R.id.icon_battery, R.drawable.ic_battery_normal)
            }
        } else {
            // Error: Show warning
            views.setTextViewText(R.id.text_percentage, "--")
            views.setTextViewText(R.id.text_status, result.error ?: "Offline")
            views.setProgressBar(R.id.progress_battery, 100, 0, false)
            views.setImageViewResource(R.id.icon_battery, R.drawable.ic_warning)
        }
        
        appWidgetManager.updateAppWidget(widgetId, views)
    }

    data class BatteryResult(
        val success: Boolean,
        val level: Int,
        val isCharging: Boolean,
        val error: String?
    )
}
