package com.example.gymoccupancy

import androidx.core.content.edit
import android.content.Context
import android.graphics.BitmapFactory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File

private val httpClient = OkHttpClient()

// Functions to save/retrieve gym id and name for each widget


fun saveGymId(context: Context, appWidgetId: Int, gymId: String) {
    // SharedPreferences is Andoid's key-value storage
    // It is stored as XML files in the app's private directory
    // and persists across app restarts
    val prefs = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
    prefs.edit {putString("gym_id_$appWidgetId", gymId)}
}

fun saveOperatorId(context: Context, appWidgetId: Int, operatorId: String) {
    val prefs = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
    prefs.edit { putString("operator_id_$appWidgetId", operatorId) }
}

fun saveGymName(context: Context, appWidgetId: Int, gymName: String) {
    val prefs = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
    prefs.edit {putString("gym_name_$appWidgetId", gymName)}
}

fun getGymId(context: Context, appWidgetId: Int): String? {
    val prefs = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
    return prefs.getString("gym_id_$appWidgetId", null)
}

fun getOperatorId(context: Context, appWidgetId: Int): String? {
    val prefs = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
    return prefs.getString("operator_id_$appWidgetId", null)
}

fun getGymName(context: Context, addWidgetId: Int): String? {
    val prefs = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
    return prefs.getString("gym_name_$addWidgetId", null)
}


fun saveLogoUrl(context: Context, appWidgetId: Int, logoUrl: String?) {
    val prefs = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
    prefs.edit {
        if (logoUrl != null) putString("logo_url_$appWidgetId", logoUrl)
        else remove("logo_url_$appWidgetId")
    }
}

fun getLogoUrl(context: Context, appWidgetId: Int): String? {
    val prefs = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
    return prefs.getString("logo_url_$appWidgetId", null)
}


fun logoFileForWidget(context: Context, appWidgetId: Int): File =
    File(context.filesDir, "logo_$appWidgetId.png")

suspend fun fetchAndCacheLogo(context: Context, appWidgetId: Int): File? =
    withContext(Dispatchers.IO) {
        val logoUrl = getLogoUrl(context, appWidgetId) ?: return@withContext null
        val file = logoFileForWidget(context, appWidgetId)
        try {
            val request = Request.Builder().url(logoUrl).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bytes = response.body?.bytes() ?: return@withContext null
                // Validate it's a real image before writing
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
                file.writeBytes(bytes)
                file
            }
        } catch (e: Exception) {
            android.util.Log.e("GymWidget", "fetchAndCacheLogo error: ${e.message}")
            null
        }
    }

data class UtilizationSlot(
    val startTime: String,
    val endTime: String,
    // null = unknown (future hour with no forecast yet); 0 = genuinely empty
    val occupancy: Int?,
    val isCurrent: Boolean,
    val index: Int
    )

data class DayUtilization(
    val slots: List<UtilizationSlot>,
    val currentOccupancy: Int,
    val earliestStartTime: String,
    val latestEndTime: String,
    val totalSlots: Int,
    val isClosed: Boolean
)

// Fetch the raw occupancy JSON body from the proxy. We store this raw string in
// Glance state and parse it at render time, so the network call is decoupled from
// the widget composition lifecycle.
suspend fun fetchOccupancyRaw(operatorId: String, gymId: String): String? =
    withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://gym-occupancy-proxy.ederossi.workers.dev/$operatorId/$gymId/occupancy")
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.string()
            }
        } catch (e: Exception) {
            android.util.Log.e("GymWidget", "fetchOccupancyRaw error: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

// Parse the proxy's occupancy JSON body into a DayUtilization. Pure/synchronous so
// it can run inside the Glance composition when reading from state.
fun parseOccupancyJson(body: String): DayUtilization? {
    return try {
        val jsonArray = JSONArray(body)
        val allSlots = mutableListOf<UtilizationSlot>()
        var currentOccupancy = 0

        for (i in 0 until jsonArray.length()) {
            val jsonSlot = jsonArray.optJSONObject(i) ?: continue

            // null in JSON = unknown future hour; keep it null (optInt would coerce to 0)
            val occupancy = if (jsonSlot.isNull("occupancy")) null else jsonSlot.optInt("occupancy", 0)
            val isCurrent = jsonSlot.optBoolean("isCurrent", false)
            val startTime = jsonSlot.optString("startTime", "")
            val endTime = jsonSlot.optString("endTime", "")
            val slot = UtilizationSlot(startTime, endTime, occupancy, isCurrent, i)
            allSlots.add(slot)

            if (isCurrent) {
                currentOccupancy = occupancy ?: 0
            }
        }
        if (allSlots.isEmpty()) return null

        // what if there is no current (the gym may be closed at this time)
        val currentStartTime = allSlots.find { it.isCurrent }?.startTime
        val earliestStartTime = allSlots.minOf { it.startTime }
        val latestEndTime = allSlots.maxOf { it.endTime }

        // Keep the full day: future hours arrive with occupancy = null
        // (unknown) and render as empty until forecasts are added.
        DayUtilization(
            slots = allSlots,
            currentOccupancy = currentOccupancy,
            earliestStartTime = earliestStartTime,
            latestEndTime = latestEndTime,
            totalSlots = allSlots.size,
            isClosed = currentStartTime == null
        )
    } catch (e: Exception) {
        android.util.Log.e("GymWidget", "parseOccupancyJson error: ${e.javaClass.simpleName}: ${e.message}", e)
        null
    }
}

suspend fun fetchOccupancyData(operatorId: String, gymId: String): DayUtilization? =
    fetchOccupancyRaw(operatorId, gymId)?.let { parseOccupancyJson(it) }

