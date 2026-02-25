package com.example.gymoccupancy

import android.app.PendingIntent
import androidx.core.content.edit
import android.content.Intent
import android.graphics.Bitmap
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Color
import android.widget.RemoteViews

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

// comments for my own personal understanding

// A reusable HTTP client for making network requests
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

fun getOccupancyColor(occupancy: Int): Int {
    return when {
        occupancy < 40 -> Color.parseColor("#10B981")  // Green
        occupancy < 50 -> blendColors("#10B981", "#F59E0B", (occupancy - 40) / 10f)  // Green to Orange
        occupancy < 70 -> Color.parseColor("#F59E0B")  // Orange
        occupancy < 85 -> blendColors("#F59E0B", "#EF4444", (occupancy - 70) / 15f)  // Orange to Red
        else -> Color.parseColor("#EF4444")  // Red
    }
}

fun blendColors(color1: String, color2: String, ratio: Float): Int {
    val c1 = Color.parseColor(color1)
    val c2 = Color.parseColor(color2)

    val r = (Color.red(c1) * (1 - ratio) + Color.red(c2) * ratio).toInt()
    val g = (Color.green(c1) * (1 - ratio) + Color.green(c2) * ratio).toInt()
    val b = (Color.blue(c1) * (1 - ratio) + Color.blue(c2) * ratio).toInt()

    return Color.rgb(r, g, b)
}


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
            null
        }
    }

// Create a Bitmap to draw the chart on
private fun createOccupancyChart(
    context: Context,
    dayUtilization: DayUtilization,
    width: Int,
    height: Int
): Bitmap? {

    if (width <= 0 || height <= 0 || dayUtilization.totalSlots == 0) {
        return null
    }

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Background
    canvas.drawColor(Color.parseColor("#353535"))

    val labelHeight = 24f
    val chartHeight = height - labelHeight

    // Increased spacing between bars
    val barSpacing = 3f
    val barWidth = (width.toFloat() / dayUtilization.totalSlots) - barSpacing

    // Minimum bar height for visibility
    val minBarHeight = 4f
    val cornerRadius = 3f

    for (slot in dayUtilization.slots) {
        val left = slot.index * (barWidth + barSpacing)
        val right = left + barWidth

        val barHeight = maxOf(
            (slot.occupancy * chartHeight / 100).toFloat(),
            minBarHeight
        )
        val top = chartHeight - barHeight

        val baseColor = if (slot.isCurrent) {
            getOccupancyColor(slot.occupancy)
        } else {
            Color.parseColor("#6B6B6B")
        }

        // Extract RGB from baseColor to build transparent version
        val r = Color.red(baseColor)
        val g = Color.green(baseColor)
        val b = Color.blue(baseColor)

        val paint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
            shader = LinearGradient(
                0f, top,                          // start: top of bar
                0f, chartHeight,                  // end: bottom of bar
                Color.argb(200, r, g, b),         // ~80% opacity at top
                Color.argb(40, r, g, b),          // ~15% opacity at bottom
                Shader.TileMode.CLAMP
            )
        }
        // rounded bar (all corners)
        canvas.drawRoundRect(left, top, right, chartHeight, cornerRadius, cornerRadius, paint)
    }

    // Draw time labels with higher quality
    val timeLabelPaint = Paint().apply {
        color = Color.parseColor("#999999")
        textSize = 22f  // Increased size
        isAntiAlias = true
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT,
            android.graphics.Typeface.NORMAL
        )
        // These flags help with text rendering quality
        flags = Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG
    }

    val startTimeFormatted = dayUtilization.earliestStartTime.take(5)
    val endTimeFormatted = dayUtilization.latestEndTime.take(5)

    timeLabelPaint.textAlign = Paint.Align.LEFT
    canvas.drawText(startTimeFormatted, 4f, height - 4f, timeLabelPaint)

    timeLabelPaint.textAlign = Paint.Align.RIGHT
    canvas.drawText(endTimeFormatted, width - 4f, height - 4f, timeLabelPaint)

    return bitmap
}

class GymOccupancy : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // There may be multiple widgets active, so update all of them
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            deleteGymId(context, appWidgetId)
            deleteGymName(context, appWidgetId)
            deleteOperatorId(context, appWidgetId)
        }
    }

    override fun onEnabled(context: Context) {
        // Enter relevant functionality for when the first widget is created
    }

    override fun onDisabled(context: Context) {
        // Enter relevant functionality for when the last widget is disabled
    }
}
// args: appWidgetId (each widget has its own id!) and appWidgetManager is ...
internal fun updateAppWidget(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetId: Int
) {
    // this checks SharedPreferences to see which gymId was saved for this widget
    val gymId = getGymId(context, appWidgetId)
    val operatorId = getOperatorId(context, appWidgetId)

    // if no gym was saved, just create a default view
    // it loads the widget layout XML file and displays without data
    // the default text should be smth like "Select a gym"
    if (gymId == null || operatorId == null) {
        val views = RemoteViews(context.packageName, R.layout.gym_occupancy)
        appWidgetManager.updateAppWidget(appWidgetId, views)
        return
    }

    val gymName = getGymName(context, appWidgetId)

    // launch a coroutine that runs on the Main/UI thread (so we can update UI after fetching data)
    // the main thread can only do UI work (update text, buttons etc)
    // coroutine = a function that can be paused and resumed without blocking the thread it's running on
    // coroutine scope = the container where coroutines live :) and die :(
    CoroutineScope(Dispatchers.Main).launch {
        // this function internally switches to the the IO thread ()
        // and returns the result from the API back
        val dayUtilization = fetchOccupancyData(operatorId, gymId)
        // back on Main thread

        // RemoteViews is a special view system for widgets
        // A View is any visual element in an Android app (TextView, Button etc.)
        // kinda like HTML elements (<p>, <button> etc.)
        // The widget layout XML defines which Views to use and how they are arranged
        val views = RemoteViews(context.packageName, R.layout.gym_occupancy)

        // This updates the text (gym name)
        views.setTextViewText(R.id.gym_name_text, gymName)

        // update all views based on data availability
        if (dayUtilization != null) {
            views.setTextViewText(R.id.current_occupancy, "${dayUtilization.currentOccupancy}%")

            // Increased dimensions for better quality (2x or more)
            val bitmap = createOccupancyChart(context, dayUtilization, 800, 200)
            if (bitmap != null) {
                views.setImageViewBitmap(R.id.occupancy_chart, bitmap)
            } else {
                views.setViewVisibility(R.id.occupancy_chart, android.view.View.GONE)
            }
        } else {
            views.setTextViewText(R.id.current_occupancy, "—")
        }
        // This creates a refresh button action
        // An intent = a massage to say "do this action"
        val refreshIntent = Intent(context, GymOccupancy::class.java).apply<Intent> {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId))
        }

        // you wrap this refreshintent in a PendedingIntent
        // this is a "future intent", do it later
        // getBroadcast is for sending a broadcast message when triggered
        val refreshPendingIntent = PendingIntent.getBroadcast(
            context,
            appWidgetId,
            refreshIntent,
            // FLAG_IMMUTABLE -> PendingIntent cannot be changed after creation (security feature)
            // FLAG_UPDATE_CURRENT -> if PendingIntent already exists, update it
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // attach Pending INtent to button, so it can be clicked
        views.setOnClickPendingIntent(R.id.refresh_button, refreshPendingIntent)

        // Display the updated widget.
        // not to confuse with updateAppWidget you're in now
        // this is a function on the AppWidgetManager provided by Android
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}