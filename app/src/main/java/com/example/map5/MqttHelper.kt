package com.example.map5

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import android.widget.Toast

class MqttHelper(val context: Context, var brokerUrl: String,
                 private var clientId: String, var sourceId: String) {

    companion object {
        const val QOS_LEVEL = 1
        private const val MAX_RECONNECT_ATTEMPTS = 10
        private const val INITIAL_RECONNECT_DELAY = 5000L
        private const val MAX_RECONNECT_DELAY = 120000L
    }
    private var client: MqttClient? = null // Making it nullable
    private val handler = Handler(Looper.getMainLooper())
    var initialConnectionSuccess: Boolean? = null
    private var wasPreviouslyConnected = false
    private var isCheckingConnectionStatus = false
    var allowAutomaticReconnect = true
    private val debouncedReconnect = debounce(2000L) { actualReconnect() }
    private var reconnectAttempts = 0
    private var reconnectDelay = INITIAL_RECONNECT_DELAY
    private val subscribedTopics = mutableListOf<String>()

    init {
        reconnect()
    }

    private fun reconnect() {
        if (!allowAutomaticReconnect) return
        debouncedReconnect()
    }
    private fun actualReconnect() {
        val persistence = MemoryPersistence()
        if (client == null) {
            client = MqttClient(brokerUrl, clientId, persistence)
        }

        val connOpts = MqttConnectOptions()
        connOpts.connectionTimeout = 10
        connOpts.isAutomaticReconnect = true
        connOpts.isCleanSession = true

        try {
            client?.connect(connOpts)
            if (wasPreviouslyConnected) {
                Toast.makeText(context, "Reconnection successful", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Successfully Connected to Broker", Toast.LENGTH_SHORT)
                    .show()
                wasPreviouslyConnected = true
            }
            initialConnectionSuccess = true
            resubscribeToTopics()
        } catch (e: MqttException) {
            e.printStackTrace()
            Toast.makeText(context, "Failed to Connect to Broker", Toast.LENGTH_SHORT).show()
            if (initialConnectionSuccess == null) {
                initialConnectionSuccess = false
            }
        }
    }
    fun reconnectIfNeeded(newBrokerUrl: String, newSourceId: String) {
        if (brokerUrl != newBrokerUrl || sourceId != newSourceId) {
            brokerUrl = newBrokerUrl
            sourceId = newSourceId
            disconnect()
            reconnect()
        }
    }

    fun resubscribeToTopics(topics: List<String>) {
        topics.forEach { topic ->
            subscribe(topic)
        }
    }
    private fun debounce(windowDuration: Long, action: () -> Unit): () -> Unit {
        var lastTime = 0L

        return {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastTime >= windowDuration) {
                lastTime = currentTime
                action()
            }
        }
    }

    fun startCheckingConnectionStatus(callback: (Boolean) -> Unit) {
        if (isCheckingConnectionStatus) {
            // Stop the existing loop before starting a new one
            stopCheckingConnectionStatus()
        }

        val checkInterval = 1000L // Check every second
        val statusCheckRunnable = object : Runnable {
            override fun run() {
                val isConnected = isConnected()
                callback.invoke(isConnected)

                if (!isConnected && allowAutomaticReconnect) {
                    Toast.makeText(context, "Disconnected. Attempting to reconnect...", Toast.LENGTH_SHORT).show()
                    reconnect()
                }

                if (isCheckingConnectionStatus) {
                    handler.postDelayed(this, checkInterval)
                }
            }
        }

        handler.postDelayed(statusCheckRunnable, checkInterval)
        isCheckingConnectionStatus = true
    }


    fun isConnected(): Boolean {
        return client?.isConnected == true
    }

    fun setCallback(callback: MqttCallback) {
        client?.setCallback(callback)
    }

    fun obtainSourceId(): String {
        return sourceId
    }
    fun stopCheckingConnectionStatus() {
        isCheckingConnectionStatus = false
        handler.removeCallbacksAndMessages(null) // Stop all callbacks
    }

    //Publish JSON "signal" from app
    fun sendQRMessage(qrMessage: String) {
        val currentTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.ENGLISH).format(Date())
        val jsonMessage = JSONObject()

        jsonMessage.put("toolID", "ANDROID-APP")
        jsonMessage.put("sourceID", sourceId)
        jsonMessage.put("broadcast", false)
        jsonMessage.put("startTS", currentTime)
        jsonMessage.put("QR", qrMessage)


        publish("internal-comms-android-app", jsonMessage.toString(), QOS_LEVEL)
    }

    fun sendQLocMessage(latitude: String, longitude: String, heading: String, altitude: String) {
        val currentTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.ENGLISH).format(Date())
        val jsonMessage = JSONObject()

        jsonMessage.put("toolID", "ANDROID-APP")
        jsonMessage.put("sourceID", sourceId)
        jsonMessage.put("broadcast", false)
        jsonMessage.put("startTS", currentTime)
        jsonMessage.put("Latitude", latitude)
        jsonMessage.put("Longitude", longitude)
        jsonMessage.put("Altitude", altitude)
        jsonMessage.put("Heading", heading)

        publish("internal-comms-android-app", jsonMessage.toString(), QOS_LEVEL)
    }

    fun publish(topic: String, message: String, QOS_LEVEL: Int = 1 ) {
        try {
            val mqttMessage = MqttMessage(message.toByteArray())
            mqttMessage.qos = QOS_LEVEL  // Set the QoS level here
            client?.publish(topic, mqttMessage)
        } catch (e: MqttException) {
            e.printStackTrace()
        }
    }
    fun subscribe(topic: String) {
        try {
            client?.subscribe(topic, QOS_LEVEL)
            if (!subscribedTopics.contains(topic)) {
                subscribedTopics.add(topic)
            }
        } catch (e: MqttException) {
            e.printStackTrace()
        }
    }
    private fun resubscribeToTopics() {
        subscribedTopics.forEach { topic ->
            try {
                client?.subscribe(topic, QOS_LEVEL)
            } catch (e: MqttException) {
                e.printStackTrace()
            }
        }
    }
    fun unsubscribe(topic: String) {
        try {
            client?.unsubscribe(topic)
        } catch (e: MqttException) {
            e.printStackTrace()
        }
    }

    fun disconnect() {
        try {
            allowAutomaticReconnect = false
            client?.disconnect()
            client = null
            wasPreviouslyConnected = false
        } catch (e: MqttException) {
            e.printStackTrace()
        }
    }
}

