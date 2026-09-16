package com.example.gymoccupancy

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.background
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val AppWidgetIdKey = ActionParameters.Key<Int>("appWidgetId")

object WidgetSizes {
    val size1x4 = DpSize(280.dp, 100.dp)
    val size2x4 = DpSize(280.dp, 200.dp)
    val size2x2 = DpSize(140.dp, 200.dp)
}

private const val RATE_LIMIT_MAX = 3
private const val RATE_LIMIT_WINDOW_MS = 60_000L
private val refreshTimestamps = mutableMapOf<Int, ArrayDeque<Long>>()

fun isRefreshAllowed(appWidgetId: Int): Boolean {
    val now = System.currentTimeMillis()
    val timestamps = refreshTimestamps.getOrPut(appWidgetId) { ArrayDeque() }
    while (timestamps.isNotEmpty() && now - timestamps.first() > RATE_LIMIT_WINDOW_MS) {
        timestamps.removeFirst()
    }
    if (timestamps.size >= RATE_LIMIT_MAX) return false
    timestamps.addLast(now)
    return true
}

data class GymSlotData(
    val json: String?,
    val gymName: String?,
    val logoFile: File?,
)

suspend fun fetchGymSlotData(context: Context, appWidgetId: Int, slot: Int = 1): GymSlotData = coroutineScope {
    val gymId = getGymId(context, appWidgetId, slot)
    val operatorId = getOperatorId(context, appWidgetId, slot)
    val gymName = getGymName(context, appWidgetId, slot)
    val cachedLogo = logoFileForWidget(context, appWidgetId, slot)

    val jsonDeferred = async { if (gymId != null && operatorId != null) fetchOccupancyRaw(operatorId, gymId) else null }
    val logoDeferred = async { if (cachedLogo.exists()) cachedLogo else fetchAndCacheLogo(context, appWidgetId, slot) }

    GymSlotData(json = jsonDeferred.await(), gymName = gymName, logoFile = logoDeferred.await())
}

fun currentTimeLabel(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

fun occupancyText(dayUtilization: DayUtilization?): String = when {
    dayUtilization?.isClosed == true -> "Closed"
    dayUtilization != null -> "${dayUtilization.currentOccupancy}%"
    else -> "—"
}

@SuppressLint("RestrictedApi")
@Composable
fun RefreshButton(
    text: String,
    onClick: Action,
    fontSize: TextUnit = 14.sp,
    horizontalPadding: Dp = 8.dp,
    verticalPadding: Dp = 4.dp,
    textColor: ColorProvider = ColorProvider(R.color.widget_text_primary),
) {
    Box(
        modifier = GlanceModifier
            .background(ImageProvider(R.drawable.refresh_button_bg))
            .padding(horizontal = horizontalPadding, vertical = verticalPadding)
            .clickable(onClick)
    ) {
        Text(
            text = text,
            style = TextStyle(color = textColor, fontSize = fontSize),
        )
    }
}

@SuppressLint("RestrictedApi")
@Composable
fun RowScope.GymName(
    gymName: String?,
    fontSize: TextUnit = 18.sp,
) {
    if (gymName != null) {
        Text(
            text = gymName,
            style = TextStyle(
                color = ColorProvider(R.color.widget_text_primary),
                fontSize = fontSize,
                fontWeight = FontWeight.Bold
            ),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight()
        )
    } else {
         Spacer(modifier = GlanceModifier.defaultWeight())
    }
}


@SuppressLint("RestrictedApi")
@Composable
fun Logo(
    logoBitmap: android.graphics.Bitmap?,
    gymName: String?,
    height: Dp = 36.dp,
    width: Dp = 36.dp,
) {
    if (logoBitmap != null) {
        Image(
            provider = ImageProvider(logoBitmap),
            contentDescription = gymName,
            contentScale = ContentScale.Fit,
            modifier = GlanceModifier.height(height).width(width)
        )
    } else {
        Spacer(modifier = GlanceModifier.height(height).width(width))
    }
}

@SuppressLint("RestrictedApi")
@Composable
fun OccupancyPercentage(
    text: String,
    fontSize: TextUnit = 24.sp,
) {
    Text(
        text = text,
        style = TextStyle(color = ColorProvider(R.color.widget_text_primary), fontSize = fontSize, fontWeight = FontWeight.Bold)
    )
}

@SuppressLint("RestrictedApi")
@Composable
fun OccupancyChart(
    dayUtilization: DayUtilization?,
    widthPercentage: Float = 0.65f,
    heightPercentage: Float = 0.55f,
    barSpacing: Float = 6f,
    cornerRadius: Float = 3f,
    size: DpSize,
    density: Float,
    modifier: GlanceModifier = GlanceModifier,
) {
    val chartBitmap = dayUtilization?.let {
        val chartW = (size.width.value * density * widthPercentage).toInt()
        val chartH = (size.height.value * density * heightPercentage).toInt()
        createOccupancyChart(dayUtilization, chartW, chartH, barSpacing=barSpacing, cornerRadius=cornerRadius)
    }
    if (chartBitmap != null) {
        Image(
            provider = ImageProvider(chartBitmap),
            contentDescription = "Occupancy chart",
            contentScale = ContentScale.FillBounds,
            modifier = modifier.fillMaxWidth()
        )
    }
}

@SuppressLint("RestrictedApi")
@Composable
fun TimeRangeRow(
    dayUtilization: DayUtilization?,
    fontSize: TextUnit = 11.sp,
) {
    if (dayUtilization != null) {
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Text(
                text = dayUtilization.earliestStartTime.take(5),
                style = TextStyle(
                    color = ColorProvider(R.color.widget_text_secondary),
                    fontSize = fontSize
                )
            )
            Spacer(modifier = GlanceModifier.defaultWeight())
            Text(
                text = dayUtilization.latestEndTime.take(5),
                style = TextStyle(
                    color = ColorProvider(R.color.widget_text_secondary),
                    fontSize = fontSize
                )
            )
        }
    }
}
