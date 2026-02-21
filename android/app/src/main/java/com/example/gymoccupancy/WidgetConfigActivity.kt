package com.example.gymoccupancy

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.widget.Toast
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import android.widget.ListView
import android.widget.ArrayAdapter
import android.content.Intent
import org.json.JSONObject


private val httpClient = OkHttpClient()

data class Gym(
    val operatorId: String,
    val id: String,
    val city: String,
    val gymName: String
) {
    // Display format: "CITY gymName"
    val displayName: String
        get() = "${city.uppercase()} $gymName"
}


/**
 * Fetches list of gyms from the API
 */




suspend fun fetchGyms(): List<Gym> =
    withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://gym-occupancy-proxy.ederossi.workers.dev/gyms")
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext emptyList()
                }

                val body = response.body?.string()
                    ?: return@withContext emptyList()

                val jsonObject = JSONObject(body)
                val jsonArray = jsonObject.optJSONArray("gyms")
                    ?: return@withContext emptyList()

                val gyms = mutableListOf<Gym>()

                for (i in 0 until jsonArray.length()) {
                    val gymJson = jsonArray.optJSONObject(i) ?: continue

                    val operatorId = gymJson.optString("operatorId", "")
                    val gymId = gymJson.optString("gymId", "")
                    val gymName = gymJson.optString("gymName", "")
                    val city = gymJson.optString("city", "") ?: ""

                    if (gymId.isNotEmpty() && gymName.isNotEmpty() && city.isNotEmpty() && operatorId.isNotEmpty()) {
                        gyms.add(Gym(operatorId, gymId, city, gymName))
                    }
                }
                // return ordered list of gyms
                return@withContext gyms.sortedBy { it.displayName }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }


class WidgetConfigActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.widget_config_activity)

        // Get reference to the ListView from your XML
        // A ListView is an Android UI component that shows a verticle list of items
        // r.id.gym_list is a generated id for the view
        val gymListView = findViewById<ListView>(R.id.gym_list)

        // Get Widget ID
        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // Validate Widget ID: exit activity if invalid
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            // RESULT_CANCELED is a status code that
            // indicates the activity could not complete successfully
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            val gyms = fetchGyms()

            if (gyms.isEmpty()) {
                Toast.makeText(this@WidgetConfigActivity, "Failed to load gyms", Toast.LENGTH_SHORT).show()
            } else {
                // Create adapter with gym display names
                // Adapter is the bridge between data and UI list
                // For each item:
                // 1. Takes a String (displayName)
                // 2. Inflates simple_list_item_1
                // 3. Puts the text into the TextView
                // 4. Gives the row to the ListView
                val adapter = ArrayAdapter(
                    this@WidgetConfigActivity,
                    android.R.layout.simple_list_item_1,
                    gyms.map { it.displayName }
                )

                // Set adapter to ListView
                gymListView.adapter = adapter

                // Set click listener for ListView items
                // A listener is an object that wait for an event and reacts when it happens
                gymListView.setOnItemClickListener { _, _, position, _ ->
                    val selectedGymId = gyms[position].id

                    saveGymId(this@WidgetConfigActivity, appWidgetId, selectedGymId)
                    saveOperatorId(this@WidgetConfigActivity, appWidgetId, gyms[position].operatorId)
                    saveGymName(this@WidgetConfigActivity, appWidgetId, gyms[position].gymName)

                    // Create an empty itent object
                    // This will then be populated with the AppWidgetId
                    // EXTRA is just a convention: it indicates it is a key for IntentputExtra()
                    val resultValue = Intent()
                    resultValue.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)

                    // just as before (RESULT_CANCELLED), but now successful
                    setResult(RESULT_OK, resultValue)

                    // AppWidgetManager is the system service that manages
                    // all widget on the device (get infos, update widgets, request updates etc.)
                    val appWidgetManager = AppWidgetManager.getInstance(
                        this@WidgetConfigActivity)

                    updateAppWidget(
                        this@WidgetConfigActivity,
                        appWidgetManager,
                        appWidgetId
                    )
                    finish()
                }

            }
        }
    }
}