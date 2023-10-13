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
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.ImageViewCompat


class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private var mostRecentLatLng: LatLng? = null
    private lateinit var mMap: GoogleMap
    private var mqttHelper: MqttHelper? = null
    private val toolPaths: MutableMap<String, MutableList<LatLng>> = mutableMapOf()
    private val toolMarkers: MutableMap<String, Marker?> = mutableMapOf()
    private val toolPolylines: MutableMap<String, Polyline> = mutableMapOf()
    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPreferences = getSharedPreferences("map5_preferences", Context.MODE_PRIVATE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)  // Keep screen on

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this) // Initialize Google Map

        val connectButton: Button = findViewById(R.id.button)

        val visualSwitch: Switch = findViewById(R.id.switch1)
        val inertioSwitch: Switch = findViewById(R.id.switch2)
        val galileoSwitch: Switch = findViewById(R.id.switch3)
        val fusionSwitch: Switch = findViewById(R.id.switch4)

        val focusLastMarkerButton: Button = findViewById(R.id.focusLastMarkerButton)

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
        builder.setPositiveButton("OK") { _, _ ->
            val brokerIp = editTextIp.text.toString()
            val sourceId = editTextId.text.toString()
            val connectionIndicator: ImageView = findViewById(R.id.connectionIndicator)

            if (brokerIp.isNotEmpty() && sourceId.isNotEmpty()) {
                with(sharedPreferences.edit()) {
                    putString("brokerIp", brokerIp)
                    putString("sourceId", sourceId)
                    apply()
                }
                mqttHelper = MqttHelper(this@MainActivity, "tcp://$brokerIp:1883", sourceId, sourceId)

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
        mostRecentLatLng?.let {
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(it, 19f))
        }
    }

    private fun handleIncomingMessage(topic: String, message: MqttMessage) {
        if (!::mMap.isInitialized) {
            return
        }

        val payload = String(message.payload)
        val jsonObject = JSONObject(payload)
        val infoprioPayload = jsonObject.getJSONObject("infoprioPayload")
        val toolData = infoprioPayload.getJSONArray("toolData").getJSONObject(0)

        val lat = toolData.getDouble("latitude")
        val lon = toolData.getDouble("longitude")
        val latLng = LatLng(lat, lon)

        mostRecentLatLng = latLng  // Save the most recent LatLng

        val path = toolPaths.getOrPut(topic) { mutableListOf() }
        path.add(latLng)

        runOnUiThread {
            toolMarkers[topic]?.remove()

            // Get color for the topic
            val color = getColorByTopic(topic)
            val sourceId = sharedPreferences.getString("sourceId", "")
            val markerTitle = "$sourceId - $topic"

            // Create the marker
            val markerOptions = MarkerOptions()
                .position(latLng)
                .title(markerTitle)
                .icon(BitmapDescriptorFactory.defaultMarker(
                    when (color) {
                        Color.BLUE -> BitmapDescriptorFactory.HUE_BLUE
                        Color.GREEN -> BitmapDescriptorFactory.HUE_GREEN
                        Color.RED -> BitmapDescriptorFactory.HUE_RED
                        Color.YELLOW -> BitmapDescriptorFactory.HUE_YELLOW
                        else -> BitmapDescriptorFactory.HUE_VIOLET
                    }
                ))

            // Get switch ID based on the topic
            val switchId = when (topic) {
                "fromtool-loc-self" -> R.id.switch1
                "fromtool-loc-ibl" -> R.id.switch2
                "fromtool-loc-glt" -> R.id.switch3
                "fromtool-loc-fusion" -> R.id.switch4
                else -> null
            }

            // Get the state of the relevant switch
            val isChecked = switchId?.let { findViewById<Switch>(it).isChecked } ?: true

            // Create and set visibility of the new marker
            val marker = mMap.addMarker(markerOptions)
            if (marker != null) {
                marker.isVisible = isChecked
            }
            toolMarkers[topic] = marker

            // Create and set visibility of the new polyline
            val polylineOptions = PolylineOptions()
                .addAll(path)
                .color(color)

            if (toolPolylines.containsKey(topic)) {
                toolPolylines[topic]?.points = path
                toolPolylines[topic]?.isVisible = isChecked
            } else {
                val newPolyline = mMap.addPolyline(polylineOptions)
                newPolyline.isVisible = isChecked
                toolPolylines[topic] = newPolyline
            }
        }
    }

    private fun showMapTypeSelectorDialog() {
        val mapTypeIds = arrayOf(
            GoogleMap.MAP_TYPE_NORMAL,
            GoogleMap.MAP_TYPE_SATELLITE,
            GoogleMap.MAP_TYPE_TERRAIN,
            GoogleMap.MAP_TYPE_HYBRID,
            GoogleMap.MAP_TYPE_NONE
        )
        val mapTypeNames = arrayOf(
            "Normal",
            "Satellite",
            "Terrain",
            "Hybrid",
            "None"
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
        mMap.mapType = GoogleMap.MAP_TYPE_HYBRID // Initial map type
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

    override fun onDestroy() {
        super.onDestroy()
        mqttHelper?.disconnect()
        mqttHelper?.stopCheckingConnectionStatus()  // Stop checking connection status
    }
}