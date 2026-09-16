package com.example.gymoccupancy

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.currentState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
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
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width

private object SingleWidgetKeys {
    val OccupancyJson = stringPreferencesKey("occupancy_json")
    val LastUpdated = stringPreferencesKey("last_updated")
    val GymName = stringPreferencesKey("gym_name")
    val LogoPath = stringPreferencesKey("logo_path")
}

suspend fun loadSingleOccupancyIntoState(context: Context, appWidgetId: Int) {
    val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
    val slot = fetchGymSlotData(context, appWidgetId)
    updateAppWidgetState(context, glanceId) { prefs ->
        if (slot.json != null) prefs[SingleWidgetKeys.OccupancyJson] = slot.json else prefs.remove(SingleWidgetKeys.OccupancyJson)
        prefs[SingleWidgetKeys.LastUpdated] = currentTimeLabel()
        if (slot.gymName != null) prefs[SingleWidgetKeys.GymName] = slot.gymName else prefs.remove(SingleWidgetKeys.GymName)
        if (slot.logoFile != null) prefs[SingleWidgetKeys.LogoPath] = slot.logoFile.absolutePath else prefs.remove(SingleWidgetKeys.LogoPath)
    }
}

class SingleRefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val appWidgetId = parameters[AppWidgetIdKey] ?: return
        if (!isRefreshAllowed(appWidgetId)) return
        loadSingleOccupancyIntoState(context, appWidgetId)
        SingleGymOccupancyWidget().update(context, glanceId)
    }
}

class SingleGymOccupancyWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(
        setOf(WidgetSizes.size1x4, WidgetSizes.size2x4)
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)

        loadSingleOccupancyIntoState(context, appWidgetId)

        provideContent {
            val prefs = currentState<Preferences>()
            val json = prefs[SingleWidgetKeys.OccupancyJson]
            val data = remember(json) { json?.let { parseOccupancyJson(it) } }
            val lastUpdated = prefs[SingleWidgetKeys.LastUpdated]
            val gymName = prefs[SingleWidgetKeys.GymName]
            val logoPath = prefs[SingleWidgetKeys.LogoPath]
            val logoFile = remember(logoPath) { logoPath?.let { java.io.File(it) } }
            val size = LocalSize.current
            SingleWidgetContent(appWidgetId, gymName, dayUtilization = data, logoFile, lastUpdated = lastUpdated, size = size)
        }
    }
}

@SuppressLint("RestrictedApi")
@Composable
private fun SingleWidgetContent(
    appWidgetId: Int,
    gymName: String?,
    dayUtilization: DayUtilization?,
    logoFile: java.io.File?,
    lastUpdated: String? = null,
    size: DpSize
) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val occupancyText = occupancyText(dayUtilization)
    val logoBitmap = if (logoFile != null && logoFile.exists()) {
        android.graphics.BitmapFactory.decodeFile(logoFile.absolutePath)
    } else null
    val configIntent = Intent(context, SingleWidgetConfigActivity::class.java).apply {
        putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val refreshAction = actionRunCallback<SingleRefreshAction>(actionParametersOf(AppWidgetIdKey to appWidgetId))
    val configAction = actionStartActivity(configIntent)
    val lastUpdatedText = if (lastUpdated != null) "↻ $lastUpdated" else "↻"

    if (WidgetSizes.size1x4 == size) {
        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(R.color.widget_background)
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clickable(configAction),
            verticalAlignment = Alignment.Vertical.CenterVertically
        ) {
            OccupancyPercentage(occupancyText, 24.sp)
            Spacer(modifier = GlanceModifier.width(12.dp))
            GymName(gymName, 16.sp)
            Spacer(modifier = GlanceModifier.width(20.dp))
            Logo(logoBitmap, gymName, 36.dp, 36.dp)
            Spacer(modifier = GlanceModifier.width(8.dp))
            RefreshButton(text = lastUpdatedText, onClick = refreshAction, fontSize = 14.sp, horizontalPadding = 8.dp, verticalPadding = 4.dp)
        }
    } else if (WidgetSizes.size2x4 == size) {
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
                GymName(gymName, 18.sp)
                Spacer(modifier = GlanceModifier.width(20.dp)) // or is it 12.dp?
                Logo(logoBitmap, gymName, 38.dp, 38.dp)
            }
            Spacer(modifier = GlanceModifier.height(4.dp))
            Row(
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                // Left: % stacked above ↻ time
                Column(verticalAlignment = Alignment.Vertical.CenterVertically) {
                    OccupancyPercentage(occupancyText, 32.sp)
                    Spacer(modifier = GlanceModifier.height(16.dp))
                    RefreshButton(text = lastUpdatedText, onClick = refreshAction)
                }
                Spacer(modifier = GlanceModifier.width(12.dp))
                // Right: chart above gym opening time/closing time (like: 07:00 / 22:00)
                Column(modifier = GlanceModifier.defaultWeight().fillMaxSize()) {
                    OccupancyChart(dayUtilization, 0.65f, 0.55f, 6f, 3f, size, density, GlanceModifier.defaultWeight())
                    TimeRangeRow(dayUtilization, 11.sp)
                }
            }
        }
    }
}

class SingleGymOccupancyReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SingleGymOccupancyWidget()
}
