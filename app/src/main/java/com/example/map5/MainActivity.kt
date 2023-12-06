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
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import com.google.android.gms.maps.model.BitmapDescriptor


class MainActivity : AppCompatActivity(), OnMapReadyCallback,GoogleMap.OnMarkerClickListener {

    private lateinit var mMap: GoogleMap
    private var mqttHelper: MqttHelper? = null
    private val toolPaths: MutableMap<String, MutableList<LatLng>> = mutableMapOf()
    private val toolMarkers: MutableMap<String, Marker?> = mutableMapOf()
    private val toolPolylines: MutableMap<String, Polyline> = mutableMapOf()
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var customInfoPanel: FrameLayout
    private lateinit var infoContent: TextView
    private lateinit var closeButton: ImageButton
    private val markerDataMap = mutableMapOf<Marker, MarkerData>()
    private var areButtonsVisible = true
    private var selectedMarker: Marker? = null
    private var isDebugInfoVisible = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPreferences = getSharedPreferences("map5_preferences", Context.MODE_PRIVATE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)  // Keep screen on

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this) // Initialize Google Map

        val toggleButton: Button = findViewById(R.id.toggleButton)
        toggleButton.setOnClickListener {
            toggleButtonsVisibility()
        }
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
            toolMarkers["fromdso-loc-self"]?.isVisible = isChecked
            toolPolylines["fromdso-loc-self"]?.isVisible = isChecked
        }

        inertioSwitch.setOnCheckedChangeListener { _, isChecked ->
            Log.d("SwitchToggle", "InertioSwitch toggled: $isChecked")
            toolMarkers["fromdso-loc-ibl"]?.isVisible = isChecked
            toolPolylines["fromdso-loc-ibl"]?.isVisible = isChecked
        }

        galileoSwitch.setOnCheckedChangeListener { _, isChecked ->
            Log.d("SwitchToggle", "galileoSwitch toggled: $isChecked")
            toolMarkers["fromdso-loc-glt"]?.isVisible = isChecked
            toolPolylines["fromdso-loc-glt"]?.isVisible = isChecked
        }

        fusionSwitch.setOnCheckedChangeListener { _, isChecked ->
            Log.d("SwitchToggle", "fusionSwitch toggled: $isChecked")
            toolMarkers["fromdso-loc-fusion"]?.isVisible = isChecked
            toolPolylines["fromdso-loc-fusion"]?.isVisible = isChecked
        }
        val remoteButton: Button = findViewById(R.id.remoteButton)

        remoteButton.setOnClickListener {
            val items = arrayOf("1", "2", "3")

            val builder = AlertDialog.Builder(this)
            builder.setTitle("Choose QR Code")
            builder.setItems(items) { dialog, which ->
                val selectedQR = items[which]
                val qrMessage = "$selectedQR"

                mqttHelper?.sendQRMessage(qrMessage)
                Toast.makeText(this@MainActivity, "QR $selectedQR detected", Toast.LENGTH_SHORT)
                    .show()


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

        val posButton: Button = findViewById(R.id.posButton)
        posButton.setOnClickListener {
            sendPositionDialog()
        }

        val debugInfoContainer: FrameLayout = findViewById(R.id.debugInfoContainer)

        showDebugButton.setOnClickListener {
            isDebugInfoVisible = !isDebugInfoVisible
            debugInfoContainer.visibility = if (isDebugInfoVisible) View.VISIBLE else View.GONE
            showDebugButton.text = if (isDebugInfoVisible) "Hide Debug Info" else "Show Debug Info"
        }
    }

    private fun getColorByTopic(topic: String): Int {
        return when (topic) {
            "fromdso-loc-self" -> Color.RED
            "fromdso-loc-ibl" -> Color.GREEN
            "fromdso-loc-glt" -> Color.BLUE
            "fromdso-loc-fusion" -> Color.YELLOW
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
        Toast.makeText(this@MainActivity, "Paths Cleared", Toast.LENGTH_SHORT).show()

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
                Toast.makeText(this@MainActivity, "Disconnected from Broker", Toast.LENGTH_SHORT)
                    .show()
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
                val timestamp =
                    SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault()).format(Date())
                val newClientId = "Client_$timestamp"
                with(sharedPreferences.edit()) {
                    putString("brokerIp", brokerIp)
                    putString("sourceId", sourceId)
                    apply()
                }

                if (mqttHelper == null || !mqttHelper!!.isConnected()) {
                    mqttHelper = MqttHelper(this@MainActivity, "tcp://$brokerIp:1883", newClientId, sourceId)
                } else {
                    mqttHelper?.reconnectIfNeeded("tcp://$brokerIp:1883", sourceId)
                }

                val topics = listOf(
                    "fromdso-loc-self",
                    "fromdso-loc-ibl",
                    "fromdso-loc-glt",
                    "fromdso-loc-fusion",
                    "fromtool-loc-self-debug"
                )
                mqttHelper?.resubscribeToTopics(topics)

                mqttHelper?.startCheckingConnectionStatus { isConnected ->
                    Log.d("MQTT_STATUS", "Connected: $isConnected")
                    val color = if (isConnected) Color.GREEN else Color.RED
                    ImageViewCompat.setImageTintList(
                        connectionIndicator,
                        ColorStateList.valueOf(color)
                    )
                }
            }

            mqttHelper?.subscribe("fromdso-loc-self")
            mqttHelper?.subscribe("fromdso-loc-ibl")
            mqttHelper?.subscribe("fromdso-loc-glt")
            mqttHelper?.subscribe("fromdso-loc-fusion")
            mqttHelper?.subscribe("fromtool-loc-self-debug")

            mqttHelper?.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    Log.d("MQTT", "Connection lost: ${cause?.message}")
                    cause?.printStackTrace()
                    Toast.makeText(
                        this@MainActivity,
                        "Disconnected from Broker ${cause?.message}",
                        Toast.LENGTH_SHORT
                    ).show()
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

    private fun handleIncomingMessage(topic: String, message: MqttMessage) {
        if (!::mMap.isInitialized) {
            return
        }

        // Handling debug messages
        if (topic == "fromtool-loc-self-debug") {
            val msgString = String(message.payload)
            runOnUiThread {
                val debugTextView: TextView = findViewById(R.id.debugTextView)
                debugTextView.text = msgString
            }
        }

        // Parsing message payload
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
            sourceId, toolID, lat, lon, heading, altitude, mounting, speed, distance, quality, qualityHeading, outdoor, topic
        )


        runOnUiThread {
            // Update or create marker
            val existingMarker = toolMarkers[topic]
            val markerIcon = rotateAndColorBitmap(
                BitmapFactory.decodeResource(resources, getIconResourceByTopic(topic)),
                heading.toFloat(),
                getColorByTopic(topic),
                255 // 100% opacity
            )
            val marker = toolMarkers[topic]


            if (existingMarker != null) {
                existingMarker.position = latLng
                existingMarker.setIcon(markerIcon)
            } else {
                val markerOptions = MarkerOptions()
                    .position(latLng)
                    .title("$sourceId - $topic")
                    .icon(markerIcon)
                    .anchor(0.5f, 0.5f)

                toolMarkers[topic] = mMap.addMarker(markerOptions)
            }

            // Update polyline
            val path = toolPaths.getOrPut(topic) { mutableListOf() }
            path.add(latLng)
            val polyline = toolPolylines[topic]
            if (polyline != null) {
                polyline.points = path
            } else {
                val newPolyline = mMap.addPolyline(PolylineOptions().addAll(path).color(getColorByTopic(topic)))
                toolPolylines[topic] = newPolyline
            }

            // Update marker data
            toolMarkers[topic]?.let { marker ->
                markerDataMap[marker] = markerData
                marker.isVisible = getSwitchStateBasedOnTopic(topic)

                // If this marker is the selected marker, update the info panel
                if (selectedMarker == marker) {
                    updateInfoPanel(markerData)
                }
            }

        }
    }

    private fun getIconResourceByTopic(topic: String): Int {
        return when (topic) {
            "fromdso-loc-self" -> R.drawable.ic_visual_loc
            "fromdso-loc-ibl" -> R.drawable.ic_inertial_loc
            "fromdso-loc-glt" -> R.drawable.ic_galileo_loc
            "fromdso-loc-fusion" -> R.drawable.ic_fusion_loc
            else -> 0 // Default or no icon
        }
    }

    private fun rotateAndColorBitmap(source: Bitmap, angle: Float, color: Int, opacity: Int, scale: Float = 0.3f): BitmapDescriptor {
        val matrix = Matrix().apply {
            postRotate(angle)
            postScale(scale, scale)  // Scale down the bitmap
        }

        val alteredBitmap = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)

        // Applying color filter and opacity
        val paint = Paint().apply {
            colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_ATOP)
            alpha = opacity
        }

        val canvas = Canvas(alteredBitmap)
        canvas.drawBitmap(alteredBitmap, 0f, 0f, paint)

        return BitmapDescriptorFactory.fromBitmap(alteredBitmap)
    }

    private fun getSwitchStateBasedOnTopic(topic: String): Boolean {
        val switchId = when (topic) {
            "fromdso-loc-self" -> R.id.switch1
            "fromdso-loc-ibl" -> R.id.switch2
            "fromdso-loc-glt" -> R.id.switch3
            "fromdso-loc-fusion" -> R.id.switch4
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
    private fun sendPositionDialog() {
        val builder = AlertDialog.Builder(this)
        val inflater = layoutInflater
        val dialogLayout = inflater.inflate(R.layout.dialog_send_position, null)
        val editTextLatitude = dialogLayout.findViewById<EditText>(R.id.editTextLatitude)
        val editTextLongitude = dialogLayout.findViewById<EditText>(R.id.editTextLongitude)
        val editTextHeading = dialogLayout.findViewById<EditText>(R.id.editTextHeading)
        val editTextAltitude = dialogLayout.findViewById<EditText>(R.id.editTextAltitude)

        // Load last values
        val lastLatitude = sharedPreferences.getString("lastLatitude", "")
        val lastLongitude = sharedPreferences.getString("lastLongitude", "")
        val lastHeading = sharedPreferences.getString("lastHeading", "")
        val lastAltitude = sharedPreferences.getString("lastAltitude", "")

        editTextLatitude.setText(lastLatitude)
        editTextLongitude.setText(lastLongitude)
        editTextHeading.setText(lastHeading)
        editTextAltitude.setText(lastAltitude)

        builder.setView(dialogLayout)
        builder.setNegativeButton("Cancel") { _, _ -> }
        builder.setPositiveButton("Send") { _, _ ->
            try {
                val latitude = editTextLatitude.text.toString().toFloat()
                val longitude = editTextLongitude.text.toString().toFloat()
                val heading = editTextHeading.text.toString().toFloat()
                val altitude = editTextAltitude.text.toString()

                if (latitude in -90.0..90.0 && longitude in -180.0..180.0 && heading in 0.0..360.0) {
                    // Save current values
                    with(sharedPreferences.edit()) {
                        putString("lastLatitude", latitude.toString())
                        putString("lastLongitude", longitude.toString())
                        putString("lastHeading", heading.toString())
                        putString("lastAltitude", altitude)
                        apply()
                    }

                    mqttHelper?.sendQLocMessage(latitude.toString(), longitude.toString(), heading.toString(), altitude)
                    Toast.makeText(this, "Position data sent", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Invalid latitude, longitude, or heading values", Toast.LENGTH_SHORT).show()
                }
            } catch (e: NumberFormatException) {
                Toast.makeText(this, "Please enter valid numeric values", Toast.LENGTH_SHORT).show()
            }
        }
        builder.show()
    }
    private fun updateMarkerRotations() {
        val bearing = mMap.cameraPosition.bearing
        toolMarkers.values.forEach { marker ->
            marker?.rotation = -bearing
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mMap.setInfoWindowAdapter(CustomInfoWindowAdapter(this))
        mMap.setOnMarkerClickListener(this)

        mMap.setOnCameraMoveListener {
            updateMarkerRotations()
        }
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
        selectedMarker = marker
        markerDataMap[marker]?.let { data ->
            updateInfoPanel(data)
            customInfoPanel.visibility = View.VISIBLE
        }
        return true
    }
    private fun updateInfoPanel(data: MarkerData) {
        val info = StringBuilder()
        info.append("Tool ID: ").append(data.toolID).append("\n")
        info.append("Source ID: ").append(data.sourceId).append("\n")
        info.append("Latitude: ").append(data.latitude).append("\n")
        info.append("Longitude: ").append(data.longitude).append("\n")
        data.heading?.let { info.append("Heading: ").append(it).append("\n") }
        data.altitude?.let { info.append("Altitude: ").append(it).append("\n") }
        // Append other data fields as needed

        infoContent.text = info.toString()
    }

    private fun toggleButtonsVisibility() {
        val buttonsAndSwitchesIds = arrayOf(
            R.id.button, R.id.switch1, R.id.switch2, R.id.switch3,
            R.id.switch4, R.id.focusLastMarkerButton, R.id.remoteButton,
            R.id.infoButton, R.id.clearButton, R.id.connectionIndicator,
            R.id.showDebugInfoButton, R.id.mapTypeButton, R.id.posButton,
            R.id.button
        )

        areButtonsVisible = !areButtonsVisible

        buttonsAndSwitchesIds.forEach { id ->
            findViewById<View>(id).visibility = if (areButtonsVisible) View.VISIBLE else View.GONE
        }

        val toggleButton: Button = findViewById(R.id.toggleButton)
        toggleButton.text = if (areButtonsVisible) "Hide Buttons" else "Show Buttons"
    }
    override fun onDestroy() {
        super.onDestroy()
        mqttHelper?.disconnect()
        mqttHelper?.stopCheckingConnectionStatus()  // Stop checking connection status
    }
}