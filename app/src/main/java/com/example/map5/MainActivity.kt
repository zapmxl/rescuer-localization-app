package com.example.map5

import android.os.Bundle
import android.app.AlertDialog
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Polyline
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.json.JSONObject
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.ImageViewCompat
import java.text.SimpleDateFormat
import java.util.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.google.android.gms.maps.model.BitmapDescriptor


class MainActivity : AppCompatActivity(), OnMapReadyCallback,GoogleMap.OnMarkerClickListener {

    private var mostRecentLatLng: LatLng? = null
    private lateinit var mMap: GoogleMap
    private lateinit var tileCacheManager: TileCacheManager
    private var mqttHelper: MqttHelper? = null
    private val toolPaths: MutableMap<String, MutableList<LatLng>> = mutableMapOf()
    private val toolMarkers: MutableMap<String, Marker?> = mutableMapOf()
    private val toolPolylines: MutableMap<String, Polyline> = mutableMapOf()
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var customInfoPanel: FrameLayout
    private lateinit var infoContent: TextView
    private lateinit var closeButton: ImageButton
    private val markerDataMap = mutableMapOf<Marker, MarkerData>()
    private val bitmapCache = mutableMapOf<String, BitmapDescriptor>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPreferences = getSharedPreferences("map5_preferences", Context.MODE_PRIVATE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)  // Keep screen on
        tileCacheManager = TileCacheManager(this)

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this) // Initialize Google Map

        val connectButton: Button = findViewById(R.id.button)

        val visualSwitch: Switch = findViewById(R.id.switch1)
        val inertioSwitch: Switch = findViewById(R.id.switch2)
        val galileoSwitch: Switch = findViewById(R.id.switch3)
        val fusionSwitch: Switch = findViewById(R.id.switch4)

        val focusLastMarkerButton: Button = findViewById(R.id.focusLastMarkerButton)

        customInfoPanel = findViewById(R.id.custom_info_panel)
        infoContent = findViewById(R.id.info_content)
        closeButton = findViewById(R.id.close_info_panel)
        closeButton.setOnClickListener {
            customInfoPanel.visibility = View.GONE
        }

        focusLastMarkerButton.setOnClickListener {
            focusOnMostRecentMarker()
            val sourceId = sharedPreferences.getString("sourceId", "") ?: "Unknown"

            // Get the last topic from toolMarkers
            val lastTopic = toolMarkers.keys.lastOrNull() ?: "Unknown Topic"

            Toast.makeText(
                this@MainActivity,
                "Focusing on SourceID: $sourceId, Topic: $lastTopic",
                Toast.LENGTH_SHORT
            ).show()
        }
        connectButton.setOnClickListener {
            showConnectDialog()
        }

        visualSwitch.setOnCheckedChangeListener { _, isChecked ->
            Log.d("SwitchToggle", "visualSwitch toggled: $isChecked")
            toolMarkers["fromtool-loc-self"]?.isVisible = isChecked
            toolPolylines["fromtool-loc-self"]?.isVisible = isChecked
        }

        inertioSwitch.setOnCheckedChangeListener { _, isChecked ->
            Log.d("SwitchToggle", "InertioSwitch toggled: $isChecked")
            toolMarkers["fromtool-loc-ibl"]?.isVisible = isChecked
            toolPolylines["fromtool-loc-ibl"]?.isVisible = isChecked
        }

        galileoSwitch.setOnCheckedChangeListener { _, isChecked ->
            Log.d("SwitchToggle", "galileoSwitch toggled: $isChecked")
            toolMarkers["fromtool-loc-glt"]?.isVisible = isChecked
            toolPolylines["fromtool-loc-glt"]?.isVisible = isChecked
        }

        fusionSwitch.setOnCheckedChangeListener { _, isChecked ->
            Log.d("SwitchToggle", "fusionSwitch toggled: $isChecked")
            toolMarkers["fromtool-loc-fusion"]?.isVisible = isChecked
            toolPolylines["fromtool-loc-fusion"]?.isVisible = isChecked
        }
        val remoteButton: Button = findViewById(R.id.remoteButton)

        remoteButton.setOnClickListener {
            val items = arrayOf("1", "2","3")

            val builder = AlertDialog.Builder(this)
            builder.setTitle("Choose QR Code")
            builder.setItems(items) { dialog, which ->
                val selectedQR = items[which]
                val qrMessage = "$selectedQR"

                mqttHelper?.sendQRMessage(qrMessage)
                Toast.makeText(this@MainActivity, "QR $selectedQR detected", Toast.LENGTH_SHORT).show()


            }
            builder.show()
        }
        val infoButton: Button = findViewById(R.id.infoButton)
        infoButton.setOnClickListener {
            showInfoDialog()
        }
        val clearButton: Button = findViewById(R.id.clearButton)
        clearButton.setOnClickListener {
            clearPolylines()
        }
        val showDebugButton: Button = findViewById(R.id.showDebugInfoButton)
        val debugInfoContainer: FrameLayout = findViewById(R.id.debugInfoContainer)

        showDebugButton.setOnClickListener {
            debugInfoContainer.visibility = if (debugInfoContainer.visibility == View.GONE) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }
    }

    private fun getColorByTopic(topic: String): Int {
        return when (topic) {
            "fromtool-loc-self" -> Color.RED
            "fromtool-loc-ibl" -> Color.GREEN
            "fromtool-loc-glt" -> Color.BLUE
            "fromtool-loc-fusion" -> Color.YELLOW
            else -> Color.GRAY
        }
    }
    private fun clearPolylines() {
        for ((key, polyline) in toolPolylines) {
            // Get the last point of each polyline
            val lastPoint = polyline.points.last()

            // Clear the current path of this tool
            toolPaths[key]?.clear()

            // Start a new path with only the last point
            toolPaths[key]?.add(lastPoint)

            // Update the polyline on the map
            polyline.points = listOf(lastPoint)
        }
        Toast.makeText(this@MainActivity,"Paths Cleared",Toast.LENGTH_SHORT).show()

    }
    private fun showConnectDialog() {
        val builder = AlertDialog.Builder(this)
        val inflater = layoutInflater
        val dialogLayout = inflater.inflate(R.layout.dialog_add_broker, null)
        val editTextIp = dialogLayout.findViewById<EditText>(R.id.brokerIpEditText)
        val editTextId = dialogLayout.findViewById<EditText>(R.id.sourceIdEditText)

        val savedBrokerIp = sharedPreferences.getString("brokerIp", "")
        val savedSourceId = sharedPreferences.getString("sourceId", "")
        editTextIp.setText(savedBrokerIp)
        editTextId.setText(savedSourceId)

        builder.setView(dialogLayout)
        if (mqttHelper?.isConnected() == true) {
            // If connected, show the Disconnect button
            builder.setNegativeButton("Disconnect") { _, _ ->
                mqttHelper?.allowAutomaticReconnect = false
                mqttHelper?.disconnect()
                Toast.makeText(this@MainActivity, "Disconnected from Broker", Toast.LENGTH_SHORT).show()
            }
        } else {
            // If not connected, show the Cancel button
            builder.setNegativeButton("Cancel") { _, _ -> }
        }
        builder.setPositiveButton("OK") { _, _ ->
            val brokerIp = editTextIp.text.toString()
            val sourceId = editTextId.text.toString()
            val connectionIndicator: ImageView = findViewById(R.id.connectionIndicator)

            if (brokerIp.isNotEmpty() && sourceId.isNotEmpty()) {
                val timestamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault()).format(Date())
                val clientId = "Client_$timestamp"
                with(sharedPreferences.edit()) {
                    putString("brokerIp", brokerIp)
                    putString("sourceId", sourceId)
                    apply()
                }
                mqttHelper = MqttHelper(this@MainActivity, "tcp://$brokerIp:1883", clientId, sourceId)

                mqttHelper?.let {
                    if (it.initialConnectionSuccess == true) {
                        it.startCheckingConnectionStatus { isConnected ->
                            Log.d("MQTT_STATUS", "Connected: $isConnected")
                            val color = if (isConnected) Color.GREEN else Color.RED
                            ImageViewCompat.setImageTintList(connectionIndicator, ColorStateList.valueOf(color))
                        }
                    }
                }

                mqttHelper?.subscribe("fromtool-loc-self")
                mqttHelper?.subscribe("fromtool-loc-ibl")
                mqttHelper?.subscribe("fromtool-loc-glt")
                mqttHelper?.subscribe("fromtool-loc-fusion")
                mqttHelper?.subscribe("fromtool-loc-self-debug")

                mqttHelper?.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        Log.d("MQTT", "Connection lost: ${cause?.message}")
                        cause?.printStackTrace()
                        Toast.makeText(this@MainActivity, "Disconnected from Broker ${cause?.message}", Toast.LENGTH_SHORT).show()
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        Log.d("MQTT", "Message arrived from topic: $topic")
                        val msgString = String(message!!.payload)
                        val jsonObject = JSONObject(msgString)

                        if (topic != null && message != null) {
                            if (jsonObject.optString("sourceID") == mqttHelper?.obtainSourceId()) {
                                handleIncomingMessage(topic, message)
                            }
                        }
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {
                        Log.d("MQTT", "Delivery complete")
                    }
                })

                val switchIds = arrayOf(R.id.switch1, R.id.switch2, R.id.switch3, R.id.switch4)
                for (id in switchIds) {
                    val switch = findViewById<Switch>(id)
                    val currentState = switch.isChecked
                    switch.isChecked = !currentState
                    switch.isChecked = currentState
                }
            }
        }

        // Check the connection status
        if (mqttHelper?.isConnected() == true) {
            // If connected, show the Disconnect button
            builder.setNegativeButton("Disconnect") { _, _ ->
                mqttHelper?.allowAutomaticReconnect = false
                mqttHelper?.disconnect()
                Toast.makeText(this@MainActivity, "Disconnected from Broker", Toast.LENGTH_SHORT).show()
            }
        } else {
            // If not connected, show the Cancel button
            builder.setNegativeButton("Cancel") { _, _ -> }
        }

        builder.show()
    }
    private fun showInfoDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Credits")

        val inflater = this.layoutInflater
        val dialogView = inflater.inflate(R.layout.chat_dialog_layout, null)
        builder.setView(dialogView)

        val emailTextView: TextView = dialogView.findViewById(R.id.tvEmail)
        emailTextView.paint.isUnderlineText = true
        emailTextView.setOnClickListener {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:michmall@iti.gr")
                putExtra(Intent.EXTRA_SUBJECT, "Subject of your email")
                putExtra(Intent.EXTRA_TEXT, "Body of your email")
            }
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            }
        }
        builder.setPositiveButton("OK") { _, _ ->
            // Handle positive button click here
        }

        val alertDialog = builder.create()
        alertDialog.show()
    }
    private fun focusOnMostRecentMarker() {
        // Check if there are any markers at all
        if (toolMarkers.isNotEmpty()) {
            // Find the most recently added marker (last entry in the toolMarkers map)
            val mostRecentMarker = toolMarkers.values.lastOrNull { it != null }

            // If found, move the camera to that marker
            mostRecentMarker?.let { marker ->
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(marker.position, 19f))
            }
        }
    }

    private fun rotateBitmap(source: Bitmap, angle: Float): BitmapDescriptor {
        val matrix = Matrix().apply { postRotate(angle) }
        return BitmapDescriptorFactory.fromBitmap(Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true))
    }
    private fun getCustomIcon(topic: String, heading: Double): BitmapDescriptor? {
        val cacheKey = "$topic-$heading"
        bitmapCache[cacheKey]?.let {
            return it // Return cached bitmap if available
        }

        // If not in cache, create a new bitmap
        val context = this@MainActivity
        val angle = heading.toFloat()

        val iconResource = when (topic) {
            "fromtool-loc-self" -> R.drawable.ic_visual_loc
            "fromtool-loc-ibl" -> R.drawable.ic_inertial_loc
            "fromtool-loc-glt" -> R.drawable.ic_galileo_loc
            "fromtool-loc-fusion" -> R.drawable.ic_fusion_loc
            else -> null
        }

        val bitmap = iconResource?.let { BitmapFactory.decodeResource(context.resources, it) }
        val rotatedBitmap = bitmap?.let { rotateBitmap(it, angle) }

        rotatedBitmap?.let {
            bitmapCache[cacheKey] = it
            return it
        }

        return null
    }

    private fun handleIncomingMessage(topic: String, message: MqttMessage) {
        if (!::mMap.isInitialized) {
            return
        }
        if (topic == "fromtool-loc-self-debug") {
            val msgString = String(message.payload)
            runOnUiThread {
                val debugTextView: TextView = findViewById(R.id.debugTextView)
                debugTextView.text = msgString
            }
        }
        val payload = String(message.payload)
        val jsonObject = JSONObject(payload)
        val infoprioPayload = jsonObject.getJSONObject("infoprioPayload")
        val toolData = infoprioPayload.getJSONArray("toolData").getJSONObject(0)

        val lat = toolData.getDouble("latitude")
        val lon = toolData.getDouble("longitude")
        val latLng = LatLng(lat, lon)
        val sourceId = jsonObject.optString("sourceID")
        val toolID = jsonObject.optString("toolID")
        val heading = toolData.optDouble("heading", 0.0)
        val altitude = toolData.optDouble("altitude", 0.0)
        val mounting = toolData.optString("mounting", null)
        val speed = if (toolData.has("speed")) toolData.optDouble("speed") else null
        val distance = if (toolData.has("distance")) toolData.optDouble("distance") else null
        val quality = if (toolData.has("quality")) toolData.optInt("quality") else null
        val qualityHeading = if (toolData.has("qualityHeading")) toolData.optInt("qualityHeading") else null
        val outdoor = if (toolData.has("outdoor")) toolData.optBoolean("outdoor") else null

        val markerData = MarkerData(
            sourceId,
            toolID,
            lat,
            lon,
            heading,
            altitude,
            mounting,
            speed,
            distance,
            quality,
            qualityHeading,
            outdoor
        )
        val path = toolPaths.getOrPut(topic) { mutableListOf() }
        path.add(latLng)

        runOnUiThread {
            // Remove old marker if it exists
            toolMarkers[topic]?.remove()

            val markerIcon = when (topic) {
                "fromtool-loc-self" -> rotateBitmap(BitmapFactory.decodeResource(resources, R.drawable.ic_visual_loc), heading.toFloat())
                "fromtool-loc-ibl" -> rotateBitmap(BitmapFactory.decodeResource(resources, R.drawable.ic_inertial_loc), heading.toFloat())
                "fromtool-loc-glt" -> rotateBitmap(BitmapFactory.decodeResource(resources, R.drawable.ic_galileo_loc), heading.toFloat())
                "fromtool-loc-fusion" -> rotateBitmap(BitmapFactory.decodeResource(resources, R.drawable.ic_fusion_loc), heading.toFloat())
                else -> null // Use default marker if no custom icon is available
            }

            val markerOptions = MarkerOptions()
                .position(latLng)
                .title("$sourceId - $topic")

            if (markerIcon != null) {
                markerOptions.icon(markerIcon)
                // Set the anchor point to the center of the icon
                markerOptions.anchor(0.5f, 0.5f)
            } else {
                // Use the default marker with a color hue based on the topic
                markerOptions.icon(BitmapDescriptorFactory.defaultMarker(getHueByTopic(topic)))
            }

            val marker = mMap.addMarker(markerOptions)
            marker?.let {
                it.isVisible = getSwitchStateBasedOnTopic(topic)
                markerDataMap[it] = markerData // Associate data with marker
            }

            toolMarkers[topic] = marker // Update marker reference in the map

            // Update polyline
            val polyline = toolPolylines[topic]
            if (polyline != null) {
                polyline.points = path
            } else {
                val newPolyline = mMap.addPolyline(PolylineOptions().addAll(path).color(getColorByTopic(topic)))
                toolPolylines[topic] = newPolyline
            }
        }
    }
    private fun getHueByTopic(topic: String): Float {
        return when (topic) {
            "fromdso-loc-self" -> BitmapDescriptorFactory.HUE_RED
            "fromdso-loc-ibl" -> BitmapDescriptorFactory.HUE_GREEN
            "fromdso-loc-glt" -> BitmapDescriptorFactory.HUE_BLUE
            "fromtool-fusion-debug" -> BitmapDescriptorFactory.HUE_YELLOW
            else -> BitmapDescriptorFactory.HUE_VIOLET // Default hue
        }
    }

    private fun getSwitchStateBasedOnTopic(topic: String): Boolean {
        val switchId = when (topic) {
            "fromtool-loc-self" -> R.id.switch1
            "fromtool-loc-ibl" -> R.id.switch2
            "fromtool-loc-glt" -> R.id.switch3
            "fromtool-loc-fusion" -> R.id.switch4
            else -> null
        }
        return switchId?.let { findViewById<Switch>(it).isChecked } ?: true
    }

    private fun showMapTypeSelectorDialog() {
        val mapTypeIds = arrayOf(
            GoogleMap.MAP_TYPE_NORMAL,
            GoogleMap.MAP_TYPE_SATELLITE,
            GoogleMap.MAP_TYPE_TERRAIN,
            GoogleMap.MAP_TYPE_HYBRID,
        )
        val mapTypeNames = arrayOf(
            "Normal",
            "Satellite",
            "Terrain",
            "Hybrid"
        )

        val builder = AlertDialog.Builder(this)
        builder.setTitle("Select Map Type")
        builder.setSingleChoiceItems(mapTypeNames, -1) { dialog, which ->
            mMap.mapType = mapTypeIds[which]
            dialog.dismiss()
        }
        builder.setNegativeButton("Cancel") { dialog, _ ->
            dialog.dismiss()
        }
        builder.show()
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mMap.setInfoWindowAdapter(CustomInfoWindowAdapter(this))
        mMap.setOnMarkerClickListener(this)

        mMap.mapType = GoogleMap.MAP_TYPE_SATELLITE // Initial map type
        val introMark = LatLng(45.195836810455404, 6.667267626055969)
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(introMark, 16.5f))
        googleMap.addMarker(MarkerOptions()
            .position(introMark)
            .title("Welcome")
            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
        )


        val mapTypeButton: Button = findViewById(R.id.mapTypeButton)

        mapTypeButton.setOnClickListener {
            showMapTypeSelectorDialog()
        }
    }
    override fun onMarkerClick(marker: Marker): Boolean {
        markerDataMap[marker]?.let { data ->
            val info = StringBuilder()
            info.append("Source ID: ").append(data.sourceId).append("\n")
            info.append("Latitude: ").append(data.latitude).append("\n")
            info.append("Longitude: ").append(data.longitude).append("\n")
            data.heading?.let { info.append("Heading: ").append(it).append("\n") }
            data.altitude?.let { info.append("Altitude: ").append(it).append("\n") }
            // Append other data fields as needed

            infoContent.text = info.toString()
            customInfoPanel.visibility = View.VISIBLE
        }
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        tileCacheManager.persistCacheToData()
        mqttHelper?.disconnect()
        mqttHelper?.stopCheckingConnectionStatus()  // Stop checking connection status
    }
}