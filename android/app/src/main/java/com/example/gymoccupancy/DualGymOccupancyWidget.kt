package com.example.gymoccupancy

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
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
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
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


private object DualWidgetKeys {
    val OccupancyJson1 = stringPreferencesKey("occupancy_json_1")
    val OccupancyJson2 = stringPreferencesKey("occupancy_json_2")
    val GymName1 = stringPreferencesKey("gym_name_1")
    val GymName2 = stringPreferencesKey("gym_name_2")
    val LogoPath1 = stringPreferencesKey("logo_path_1")
    val LogoPath2 = stringPreferencesKey("logo_path_2")
    val LastUpdated = stringPreferencesKey("last_updated")
}

suspend fun loadDualOccupancyIntoState(context: Context, appWidgetId: Int) = coroutineScope {
    val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)

    val slot1Deferred = async { fetchGymSlotData(context, appWidgetId, slot = 1) }
    val slot2Deferred = async { fetchGymSlotData(context, appWidgetId, slot = 2) }
    val slot1 = slot1Deferred.await()
    val slot2 = slot2Deferred.await()

    updateAppWidgetState(context, glanceId) { prefs ->
        if (slot1.json != null) prefs[DualWidgetKeys.OccupancyJson1] = slot1.json else prefs.remove(DualWidgetKeys.OccupancyJson1)
        if (slot2.json != null) prefs[DualWidgetKeys.OccupancyJson2] = slot2.json else prefs.remove(DualWidgetKeys.OccupancyJson2)
        if (slot1.gymName != null) prefs[DualWidgetKeys.GymName1] = slot1.gymName else prefs.remove(DualWidgetKeys.GymName1)
        if (slot2.gymName != null) prefs[DualWidgetKeys.GymName2] = slot2.gymName else prefs.remove(DualWidgetKeys.GymName2)
        if (slot1.logoFile != null) prefs[DualWidgetKeys.LogoPath1] = slot1.logoFile.absolutePath else prefs.remove(DualWidgetKeys.LogoPath1)
        if (slot2.logoFile != null) prefs[DualWidgetKeys.LogoPath2] = slot2.logoFile.absolutePath else prefs.remove(DualWidgetKeys.LogoPath2)
        prefs[DualWidgetKeys.LastUpdated] = currentTimeLabel()
    }
}

class DualRefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val appWidgetId = parameters[AppWidgetIdKey] ?: return
        if (!isRefreshAllowed(appWidgetId)) return
        loadDualOccupancyIntoState(context, appWidgetId)
        DualGymOccupancyWidget().update(context, glanceId)
    }
}

class DualGymOccupancyWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(
        setOf(WidgetSizes.size1x4, WidgetSizes.size2x4, WidgetSizes.size2x2)
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)

        loadDualOccupancyIntoState(context, appWidgetId)

        provideContent {
            val prefs = currentState<Preferences>()
            val json1 = prefs[DualWidgetKeys.OccupancyJson1]
            val json2 = prefs[DualWidgetKeys.OccupancyJson2]
            val data1 = remember(json1) { json1?.let { parseOccupancyJson(it) } }
            val data2 = remember(json2) { json2?.let { parseOccupancyJson(it) } }

            val gymName1 = prefs[DualWidgetKeys.GymName1]
            val gymName2 = prefs[DualWidgetKeys.GymName2]

            val logoPath1 = prefs[DualWidgetKeys.LogoPath1]
            val logoPath2 = prefs[DualWidgetKeys.LogoPath2]
            val logoFile1 = remember(logoPath1) { logoPath1?.let { File(it) } }
            val logoFile2 = remember(logoPath2) { logoPath2?.let { File(it) } }

            val lastUpdated = prefs[DualWidgetKeys.LastUpdated]
            val size = LocalSize.current

            DualWidgetContent(
                appWidgetId = appWidgetId,
                gymName1 = gymName1,
                dayUtilization1 = data1,
                logoFile1 = logoFile1,
                gymName2 = gymName2,
                dayUtilization2 = data2,
                logoFile2 = logoFile2,
                lastUpdated = lastUpdated,
                size = size
            )
        }
    }
}

@SuppressLint("RestrictedApi")
@Composable
private fun DualWidgetContent(
    appWidgetId: Int,
    gymName1: String?,
    dayUtilization1: DayUtilization?,
    logoFile1: File?,
    gymName2: String?,
    dayUtilization2: DayUtilization?,
    logoFile2: File?,
    lastUpdated: String? = null,
    size: DpSize,
) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density

    val configSlot1Intent = Intent(context, DualWidgetConfigActivity::class.java).apply {
        putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        putExtra("target_slot", 1)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val configSlot2Intent = Intent(context, DualWidgetConfigActivity::class.java).apply {
        putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        putExtra("target_slot", 2)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val refreshAction = actionRunCallback<DualRefreshAction>(actionParametersOf(AppWidgetIdKey to appWidgetId))
    val configSlot1Action = actionStartActivity(configSlot1Intent)
    val configSlot2Action = actionStartActivity(configSlot2Intent)
    val lastUpdatedText = if (lastUpdated != null) "↻ $lastUpdated" else "↻"

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(R.color.widget_background)
            .padding(10.dp)
    ) {
        when (size) {
            WidgetSizes.size2x4 -> {
                Row(
                    modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                    verticalAlignment = Alignment.Vertical.CenterVertically
                ) {
                    Column(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .fillMaxHeight()
                            .clickable(configSlot1Action),
                        verticalAlignment = Alignment.Vertical.Top
                    ) {
                        GymPanel(gymName1, dayUtilization1, logoFile1, size, density, hasGraph = true)
                    }
                    Spacer(modifier = GlanceModifier.width(8.dp))
                    Spacer(
                        modifier = GlanceModifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(ColorProvider(R.color.widget_text_secondary))
                    )
                    Spacer(modifier = GlanceModifier.width(8.dp))
                    Column(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .fillMaxHeight()
                            .clickable(configSlot2Action),
                        verticalAlignment = Alignment.Vertical.Top
                    ) {
                        GymPanel(gymName2, dayUtilization2, logoFile2, size, density, hasGraph = true)
                    }
                    RefreshButton(lastUpdatedText, refreshAction, 11.sp, 6.dp, 3.dp)
                }
            }
            WidgetSizes.size1x4 -> {
                Column(
                    modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                    verticalAlignment = Alignment.Vertical.Top
                ) {
                    Column(
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .defaultWeight()
                            .clickable(configSlot1Action)
                    ) {
                        GymPanel(gymName1, dayUtilization1, logoFile1, size, density, hasGraph = false)
                    }

                    Spacer(modifier = GlanceModifier.height(6.dp))
                    Spacer(
                        modifier = GlanceModifier
                            .height(1.dp)
                            .fillMaxWidth()
                            .background(ColorProvider(R.color.widget_text_secondary))
                    )
                    Spacer(modifier = GlanceModifier.height(6.dp))

                    Column(
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .defaultWeight()
                            .clickable(configSlot2Action)
                    ) {
                        GymPanel(gymName2, dayUtilization2, logoFile2, size, density, hasGraph = false)
                    }
                }
                RefreshButton(lastUpdatedText, refreshAction, 11.sp, 6.dp, 3.dp)
            }
            WidgetSizes.size2x2 -> {
                Column(
                        modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                        verticalAlignment = Alignment.Vertical.Top
                    ) {
                        Column(
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .defaultWeight()
                                .clickable(configSlot1Action)
                        ) {
                            GymPanel(gymName1, dayUtilization1, logoFile1, size, density, hasGraph = true)
                        }

                        Spacer(modifier = GlanceModifier.height(6.dp))
                        Spacer(
                            modifier = GlanceModifier
                                .height(1.dp)
                                .fillMaxWidth()
                                .background(ColorProvider(R.color.widget_text_secondary))
                        )
                        Spacer(modifier = GlanceModifier.height(6.dp))

                        Column(
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .defaultWeight()
                                .clickable(configSlot2Action)
                        ) {
                            GymPanel(gymName2, dayUtilization2, logoFile2, size, density, hasGraph = true)
                        }
                    }
                RefreshButton(lastUpdatedText, refreshAction, 11.sp, 6.dp, 3.dp)
            }
        }
    }
}

@Composable
private fun GymPanel(
    gymName: String?,
    dayUtilization: DayUtilization?,
    logoFile: File?,
    size: androidx.compose.ui.unit.DpSize,
    density: Float,
    hasGraph: Boolean = true,
) {
    val occupancyText = occupancyText(dayUtilization)

    val logoBitmap = if (logoFile != null && logoFile.exists()) {
        BitmapFactory.decodeFile(logoFile.absolutePath)
    } else null

    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.Vertical.Top
    ) {
        // Top row: gym name | logo
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.Vertical.CenterVertically
        ) {
            GymName(gymName, fontSize = 13.sp)
            // here something
            Spacer(modifier = GlanceModifier.width(4.dp))
            Logo(logoBitmap, gymName, 20.dp, 20.dp)
        }
        Spacer(modifier = GlanceModifier.height(4.dp))
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.Vertical.CenterVertically
        ) {
            OccupancyPercentage(occupancyText, 20.sp)
        }
        if (hasGraph) {
            Column(modifier = GlanceModifier.defaultWeight().fillMaxSize()) {
                OccupancyChart(dayUtilization, 0.45f, 0.65f, 3f, 2.5f, size, density, GlanceModifier.defaultWeight())
                TimeRangeRow(dayUtilization, 9.sp)
            }
        }
    }
}

class DualGymOccupancyReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DualGymOccupancyWidget()
}
