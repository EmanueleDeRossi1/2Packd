package com.example.gymoccupancy

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.core.graphics.toColorInt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest
import androidx.core.graphics.createBitmap

private val AppWidgetIdKey = ActionParameters.Key<Int>("appWidgetId")
private val refreshTimestamps = mutableMapOf<Int, ArrayDeque<Long>>()
private const val RATE_LIMIT_MAX = 3
private const val RATE_LIMIT_WINDOW_MS = 60_000L

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val appWidgetId = parameters[AppWidgetIdKey] ?: return
        val now = System.currentTimeMillis()
        val timestamps = refreshTimestamps.getOrPut(appWidgetId) { ArrayDeque() }
        while (timestamps.isNotEmpty() && now - timestamps.first() > RATE_LIMIT_WINDOW_MS) {
            timestamps.removeFirst()
        }
        if (timestamps.size >= RATE_LIMIT_MAX) return
        timestamps.addLast(now)
        GymOccupancyWidget.notifyConfigChanged(appWidgetId)
    }
}

fun getOccupancyColor(occupancy: Int): Int {
    return when {
        occupancy < 50 -> blendColors("#10B981", "#F59E0B", occupancy / 50f)
        else -> blendColors("#F59E0B", "#EF4444", (occupancy - 50) / 50f)
    }
}

fun blendColors(color1: String, color2: String, ratio: Float): Int {
    val c1 = color1.toColorInt()
    val c2 = color2.toColorInt()
    val r = (Color.red(c1) * (1 - ratio) + Color.red(c2) * ratio).toInt()
    val g = (Color.green(c1) * (1 - ratio) + Color.green(c2) * ratio).toInt()
    val b = (Color.blue(c1) * (1 - ratio) + Color.blue(c2) * ratio).toInt()
    return Color.rgb(r, g, b)
}

fun createOccupancyChart(dayUtilization: DayUtilization, width: Int, height: Int): Bitmap? {
    if (width <= 0 || height <= 0 || dayUtilization.totalSlots == 0) return null

    val bitmap = createBitmap(width, height)
    val canvas = Canvas(bitmap)
    canvas.drawColor("#000000".toColorInt())

    val barSpacing = 6f
    val barWidth = (width.toFloat() / dayUtilization.totalSlots) - barSpacing
    val minBarHeight = 4f
    val cornerRadius = 3f

    val currentIndex = dayUtilization.slots.indexOfFirst { it.isCurrent }

    for ((i, slot) in dayUtilization.slots.withIndex()) {
        val left = i * (barWidth + barSpacing)
        val right = left + barWidth
        val barHeight = maxOf((slot.occupancy * height / 100).toFloat(), minBarHeight)
        val top = height - barHeight

        val baseColor = when {
            i < currentIndex -> "#3D3D3D".toColorInt() // dark grey
            i == currentIndex -> getOccupancyColor(slot.occupancy)
            else ->  "#9E9E9E".toColorInt() // light gray
        }

        val r = Color.red(baseColor)
        val g = Color.green(baseColor)
        val b = Color.blue(baseColor)

        val paint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
            shader = LinearGradient(
                0f, top, 0f, height.toFloat(),
                Color.argb(200, r, g, b),
                Color.argb(40, r, g, b),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(left, top, right, height.toFloat(), cornerRadius, cornerRadius, paint)
    }

    return bitmap
}

class GymOccupancyWidget : GlanceAppWidget() {

    companion object {
        private val configUpdates = MutableSharedFlow<Int>(extraBufferCapacity = 8)

        fun notifyConfigChanged(appWidgetId: Int) {
            configUpdates.tryEmit(appWidgetId)
        }
    }

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        android.util.Log.d("GymWidget", "provideGlance: appWidgetId=$appWidgetId")

        data class WidgetState(
            val gymName: String?,
            val dayUtilization: DayUtilization?,
            val logoFile: java.io.File?,
            val isLoading: Boolean = false,
            val lastUpdated: String? = null
        )

        val dataFlow: Flow<WidgetState> = configUpdates
            .onStart { emit(appWidgetId) }
            .filter { it == appWidgetId }
            .transformLatest {
                val gymName = getGymName(context, appWidgetId)
                val cachedLogo = logoFileForWidget(context, appWidgetId)
                val logoFile = if (cachedLogo.exists()) cachedLogo else null
                emit(WidgetState(gymName, null, logoFile, isLoading = true))
                delay(300)

                val gymId = getGymId(context, appWidgetId)
                val operatorId = getOperatorId(context, appWidgetId)
                android.util.Log.d("GymWidget", "fetching data: gymId=$gymId operatorId=$operatorId")
                val data = if (gymId != null && operatorId != null) {
                    fetchOccupancyData(operatorId, gymId).also {
                        android.util.Log.d("GymWidget", "fetchOccupancyData result: $it")
                    }
                } else null

                val freshLogo = if (logoFile == null) fetchAndCacheLogo(context, appWidgetId) else logoFile
                val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                emit(WidgetState(gymName, data, freshLogo, isLoading = false, lastUpdated = time))
            }

        provideContent {
            val state by dataFlow.collectAsState(initial = WidgetState(null, null, null))
            WidgetContent(appWidgetId, state.gymName, state.dayUtilization, state.logoFile, state.isLoading, state.lastUpdated)
        }
    }
}

@SuppressLint("RestrictedApi")
@Composable
private fun WidgetContent(
    appWidgetId: Int,
    gymName: String?,
    dayUtilization: DayUtilization?,
    logoFile: java.io.File?,
    isLoading: Boolean = false,
    lastUpdated: String? = null
) {
    val size = LocalSize.current
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density

    val occupancyText = when {
        isLoading -> "..."
        dayUtilization?.isClosed == true -> "Closed"
        dayUtilization != null -> "${dayUtilization.currentOccupancy}%"
        else -> "—"
    }
    val isWide = size.width > size.height * 1.5f
    val isTall = size.height > 100.dp

    val logoBitmap = if (logoFile != null && logoFile.exists()) {
        android.graphics.BitmapFactory.decodeFile(logoFile.absolutePath)
    } else null

    val configIntent = Intent(context, WidgetConfigActivity::class.java).apply {
        putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val refreshAction = actionRunCallback<RefreshAction>(actionParametersOf(AppWidgetIdKey to appWidgetId))
    val configAction = actionStartActivity(configIntent)
    val lastUpdatedText = if (lastUpdated != null) "↻ $lastUpdated" else "↻"

    if (isWide && !isTall) {
        // 1x4 single-row layout
        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(R.color.widget_background)
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clickable(configAction),
            verticalAlignment = Alignment.Vertical.CenterVertically
        ) {
            Text(
                text = occupancyText,
                style = TextStyle(color = ColorProvider(R.color.widget_text_primary), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            )
            Spacer(modifier = GlanceModifier.width(12.dp))
            if (gymName != null) {
                Text(
                    text = gymName,
                    style = TextStyle(color = ColorProvider(R.color.widget_text_primary), fontSize = 16.sp),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight()
                )
            } else {
                Spacer(modifier = GlanceModifier.defaultWeight())
            }
            Spacer(modifier = GlanceModifier.width(8.dp))
            Text(
                text = lastUpdatedText,
                style = TextStyle(color = ColorProvider(R.color.widget_text_secondary), fontSize = 11.sp),
                modifier = GlanceModifier
                    .background(ImageProvider(R.drawable.refresh_button_bg))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
                    .clickable(refreshAction)
            )
            if (logoBitmap != null) {
                Spacer(modifier = GlanceModifier.width(8.dp))
                Image(
                    provider = ImageProvider(logoBitmap),
                    contentDescription = gymName,
                    contentScale = ContentScale.Fit,
                    modifier = GlanceModifier.height(36.dp).width(36.dp)
                )
            }
        }
    } else {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(R.color.widget_background)
                .padding(12.dp)
                .clickable(configAction),
            verticalAlignment = Alignment.Vertical.Top
        ) {
            // Top row: gym name | logo
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                if (gymName != null) {
                    Text(
                        text = gymName,
                        style = TextStyle(color = ColorProvider(R.color.widget_text_primary), fontSize = 18.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        modifier = GlanceModifier.defaultWeight()
                    )
                } else {
                    Spacer(modifier = GlanceModifier.defaultWeight())
                }
                if (logoBitmap != null) {
                    Image(
                        provider = ImageProvider(logoBitmap),
                        contentDescription = gymName,
                        contentScale = ContentScale.Fit,
                        modifier = GlanceModifier.height(44.dp).width(44.dp)
                    )
                }
            }

            Spacer(modifier = GlanceModifier.height(4.dp))

            if (isWide && isTall && dayUtilization != null) {
                Row(
                    modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                    verticalAlignment = Alignment.Vertical.CenterVertically
                ) {
                    // Left: % stacked above ↻ time
                    Column(verticalAlignment = Alignment.Vertical.CenterVertically) {
                        Text(
                            text = occupancyText,
                            style = TextStyle(color = ColorProvider(R.color.widget_text_primary), fontSize = 32.sp, fontWeight = FontWeight.Bold)
                        )
                        Spacer(modifier = GlanceModifier.height(16.dp))
                        Text(
                            text = lastUpdatedText,
                            style = TextStyle(color = ColorProvider(R.color.widget_text_secondary), fontSize = 14.sp),
                            modifier = GlanceModifier
                                .background(ImageProvider(R.drawable.refresh_button_bg))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .clickable(refreshAction)
                        )
                    }

                    Spacer(modifier = GlanceModifier.width(12.dp))

                    // Right: chart above 07:00 / 22:00
                    Column(modifier = GlanceModifier.defaultWeight().fillMaxSize()) {
                        val chartW = (size.width.value * density * 0.65f).toInt()
                        val chartH = (size.height.value * density * 0.55f).toInt()
                        val chartBitmap = createOccupancyChart(dayUtilization, chartW, chartH)
                        if (chartBitmap != null) {
                            Image(
                                provider = ImageProvider(chartBitmap),
                                contentDescription = "Occupancy chart",
                                contentScale = ContentScale.FillBounds,
                                modifier = GlanceModifier.fillMaxWidth().defaultWeight()
                            )
                        }
                        Row(modifier = GlanceModifier.fillMaxWidth()) {
                            Text(
                                text = dayUtilization.earliestStartTime.take(5),
                                style = TextStyle(color = ColorProvider(R.color.widget_text_secondary), fontSize = 11.sp)
                            )
                            Spacer(modifier = GlanceModifier.defaultWeight())
                            Text(
                                text = dayUtilization.latestEndTime.take(5),
                                style = TextStyle(color = ColorProvider(R.color.widget_text_secondary), fontSize = 11.sp)
                            )
                        }
                    }
                }
            } else {
                // Occupancy percentage
                Text(
                    text = occupancyText,
                    style = TextStyle(color = ColorProvider(R.color.widget_text_primary), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                )

                Spacer(modifier = GlanceModifier.height(2.dp))

                Text(
                    text = lastUpdatedText,
                    style = TextStyle(color = ColorProvider(R.color.widget_text_secondary), fontSize = 18.sp),
                    modifier = GlanceModifier
                        .background(ImageProvider(R.drawable.refresh_button_bg))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .clickable(refreshAction)
                )
            }
        }
    }
}

class GymOccupancyReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GymOccupancyWidget()
}
