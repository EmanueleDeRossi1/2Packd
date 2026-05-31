package com.example.gymoccupancy

import androidx.core.content.edit
import android.content.Context

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

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

fun deleteGymId(context: Context, appWidgetId: Int) {
    val prefs = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
    prefs.edit {remove("gym_id_$appWidgetId")}
}

fun deleteOperatorId(context: Context, appWidgetId: Int) {
    val prefs = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
    prefs.edit {remove("operator_id_$appWidgetId")}
}

fun deleteGymName(context: Context, appWidgetId: Int) {
    val prefs = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
    prefs.edit {remove("gym_name_$appWidgetId")}
}

data class UtilizationSlot(
    val startTime: String,
    val endTime: String,
    val occupancy: Int,
    val isCurrent: Boolean,
    val index: Int
    )

data class DayUtilization(
    val slots: List<UtilizationSlot>,
    val currentOccupancy: Int,
    val earliestStartTime: String,
    val latestEndTime: String,
    val totalSlots: Int
)

// suspend marks this as a coroutine function (can be paused/resumend)
// without blocking the thread
// = is the "single-expression function" syntax
suspend fun fetchOccupancyData(operatorId: String, gymId: String): DayUtilization? =
// this switches the coroutin to a backgroud thread for I/O operations
// where Dispatchers.IO is a thread pool for I/O
// a thread pool is a collection of pre-created worker threads
// that are reused for tasks. So you dont need to create a thread each time
    // a thread is just a separate path of exeuction in your program (multiple threads=your app can do multiple things at once)
    withContext(Dispatchers.IO) {

        try {
            val request = Request.Builder()
                .url("https://gym-occupancy-proxy.ederossi.workers.dev/$operatorId/$gymId/occupancy")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext null
                }

                val body = response.body?.string()
                    ?: return@withContext null

                val jsonArray = JSONArray(body)
                val allSlots = mutableListOf<UtilizationSlot>()
                var currentOccupancy = 0

                for (i in 0 until jsonArray.length()) {
                    val jsonSlot = jsonArray.optJSONObject(i) ?: continue

                    val occupancy = jsonSlot.optInt("occupancy", 0)
                    val isCurrent = jsonSlot.optBoolean("isCurrent", false)
                    val startTime = jsonSlot.optString("startTime", "")
                    val endTime = jsonSlot.optString("endTime", "")
                    val index = i
                    val slot = UtilizationSlot(startTime, endTime, occupancy, isCurrent, index)
                    allSlots.add(slot)

                    if (isCurrent) {
                        currentOccupancy = occupancy
                    }
                }
                // what if there is no current (the gym may be closed at this time)
                val currentStartTime = allSlots.find { it.isCurrent }?.startTime

                val earliestStartTime = allSlots.minOf { it.startTime }
                val latestEndTime = allSlots.maxOf { it.endTime }
                val totalSlots = allSlots.size

                // filter out slots where time > currentTime
                val slots = if (currentStartTime != null) {
                    allSlots.filter { it.startTime <= currentStartTime }
                } else {
                    allSlots
                }
                return@withContext DayUtilization(
                    slots = slots,
                    currentOccupancy = currentOccupancy,
                    earliestStartTime = earliestStartTime,
                    latestEndTime = latestEndTime,
                    totalSlots = totalSlots
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("GymWidget", "fetchOccupancyData error: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

