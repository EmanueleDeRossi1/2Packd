package com.example.gymoccupancy

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import androidx.compose.foundation.Image

private val httpClient = OkHttpClient()

data class Gym(
    val operatorId: String,
    val id: String,
    val city: String,
    val gymName: String,
    val location: String,
    val brand: String,
    val logoUrl: String?
) {
    val displayName: String
        get() = "${city.uppercase()} $location"
}

data class Brand(
    val id: String,
    val label: String,
    val logoUrl: String?
)

suspend fun fetchGyms(): List<Gym> =
    withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://gym-occupancy-proxy.ederossi.workers.dev/gyms")
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                val jsonObject = JSONObject(body)
                val jsonArray = jsonObject.optJSONArray("gyms") ?: return@withContext emptyList()
                val gyms = mutableListOf<Gym>()
                for (i in 0 until jsonArray.length()) {
                    val g = jsonArray.optJSONObject(i) ?: continue
                    val operatorId = g.optString("operatorId", "")
                    val gymId = g.optString("gymId", "")
                    val gymName = g.optString("gymName", "")
                    val location = g.optString("location", "").ifEmpty { gymName }
                    val city = g.optString("city", "")
                    val brand = g.optString("brand", "")
                    val logoUrl = g.optString("logoUrl", "").ifEmpty { null }
                    if (gymId.isNotEmpty() && gymName.isNotEmpty() && city.isNotEmpty() && operatorId.isNotEmpty()) {
                        gyms.add(Gym(operatorId, gymId, city, gymName, location, brand, logoUrl))
                    }
                }
                gyms.sortedBy { it.displayName }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

val Background = Color(0xFF1A1A1A)
val Surface = Color(0xFF2A2A2A)
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFF999999)
val Accent = Color(0xFF10B981)

class WidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        enableEdgeToEdge()
        setContent {
            GymPickerFlow(
                onGymSelected = { gym ->
                    saveGymId(this, appWidgetId, gym.id)
                    saveOperatorId(this, appWidgetId, gym.operatorId)
                    saveGymName(this, appWidgetId, gym.location)
                    logoFileForWidget(this, appWidgetId).delete()
                    saveLogoUrl(this, appWidgetId, gym.logoUrl)
                    setResult(Activity.RESULT_OK, Intent().apply {
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    })
                    GymOccupancyWidget.notifyConfigChanged(appWidgetId)
                    finish()
                }
            )
        }
    }
}

@Composable
fun GymPickerFlow(onGymSelected: (Gym) -> Unit) {
    var gyms by remember { mutableStateOf<List<Gym>?>(null) }
    var selectedBrand by remember { mutableStateOf<Brand?>(null) }

    LaunchedEffect(Unit) {
        gyms = fetchGyms()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        when {
            gyms == null -> {
                CircularProgressIndicator(
                    color = Accent,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            selectedBrand == null -> {
                val brands = remember(gyms) {
                    gyms!!
                        .groupBy { it.brand }
                        .map { (brand, list) -> Brand(brand, brandLabel(brand), list.first().logoUrl) }
                        .sortedBy { it.label }
                }
                BrandScreen(brands, onBrandSelected = { selectedBrand = it })
            }
            else -> {
                val filtered = remember(gyms, selectedBrand) {
                    gyms!!.filter { it.brand == selectedBrand!!.id }.sortedBy { it.displayName }
                }
                GymScreen(
                    brand = selectedBrand!!,
                    gyms = filtered,
                    onBack = { selectedBrand = null },
                    onGymSelected = onGymSelected
                )
            }
        }
    }
}

@Composable
fun BrandScreen(brands: List<Brand>, onBrandSelected: (Brand) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Text(
            text = "Select gym",
            color = TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(brands) { brand ->
                BrandCard(brand = brand, onClick = { onBrandSelected(brand) })
            }
        }
    }
}

@Composable
fun BrandCard(brand: Brand, onClick: () -> Unit) {
    var logoBitmap by remember(brand.logoUrl) { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(brand.logoUrl) {
        if (brand.logoUrl != null) {
            logoBitmap = withContext(Dispatchers.IO) { loadBitmapFromUrl(brand.logoUrl) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (logoBitmap != null) {
            Image(
                bitmap = logoBitmap!!.asImageBitmap(),
                contentDescription = brand.label,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(72.dp)
                    .padding(8.dp)
            )
        } else {
            Text(
                text = brand.label,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun GymScreen(brand: Brand, gyms: List<Gym>, onBack: () -> Unit, onGymSelected: (Gym) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "←",
                color = Accent,
                fontSize = 22.sp,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = brand.label,
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
        }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(gyms) { gym ->
                GymRow(gym = gym, onClick = { onGymSelected(gym) })
            }
        }
    }
}

@Composable
fun GymRow(gym: Gym, onClick: () -> Unit) {
    val cityRedundant = gym.location.contains(gym.city, ignoreCase = true)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = gym.location, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (!cityRedundant) {
                Text(text = gym.city, color = TextSecondary, fontSize = 12.sp)
            }
        }
    }
}

fun brandLabel(brand: String): String = when (brand) {
    "mcfit"        -> "McFIT"
    "klub-mcfit"   -> "KLUB McFIT"
    "john-reed"    -> "JOHN REED"
    "john-jane"    -> "JOHN & JANE'S"
    "golds-gym"    -> "Gold's Gym"
    "high5"        -> "High5"
    "aldi-sports"  -> "ALDI Sports"
    "heimat"       -> "HEIMAT"
    "fitness-first"-> "Fitness First"
    "fitx"         -> "FitX"
    "ai-fitness"   -> "Ai Fitness"
    "fit-star"     -> "FIT STAR"
    else           -> brand
}

fun loadBitmapFromUrl(url: String): android.graphics.Bitmap? {
    return try {
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            val bytes = response.body?.bytes() ?: return null
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    } catch (e: Exception) {
        null
    }
}
