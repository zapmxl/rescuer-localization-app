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

class MqttHelper(val context: Context,
                 private val brokerUrl: String,
                 private val clientId: String,
                 val sourceId: String) {

    companion object {
        const val QOS_LEVEL = 1
    }
    private var client: MqttClient? = null // Making it nullable
    private val handler = Handler(Looper.getMainLooper())
    var initialConnectionSuccess: Boolean? = null
    private var wasPreviouslyConnected = false
    private var isCheckingConnectionStatus = false
    var allowAutomaticReconnect = true
    private val debouncedReconnect = debounce(2000L) { actualReconnect() }
    private var connectionCheckRunnable: Runnable? = null


    init {
        reconnect()
    }

    private fun reconnect() {
        if (!allowAutomaticReconnect || initialConnectionSuccess != true) return
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
        } catch (e: MqttException) {
            e.printStackTrace()
            Toast.makeText(context, "Failed to Connect to Broker", Toast.LENGTH_SHORT).show()
            if (initialConnectionSuccess == null) {
                initialConnectionSuccess = false
            }
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
        if (isCheckingConnectionStatus) return // Return if it's already checking

        val checkInterval = 1000L // 1000 milliseconds = 1 second
        connectionCheckRunnable = object : Runnable {
            override fun run() {
                val isConnected = isConnected()
                callback.invoke(isConnected)
                if (!isConnected && allowAutomaticReconnect) {
                    Toast.makeText(context, "Disconnected. Attempting to reconnect...", Toast.LENGTH_SHORT).show()
                    reconnect()  // Attempt to reconnect if not connected
                }
                handler.postDelayed(this, checkInterval)
            }
        }
        handler.post(connectionCheckRunnable!!)
        isCheckingConnectionStatus = true  // Set the flag to true
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
        connectionCheckRunnable?.let { runnable ->
            handler.removeCallbacks(runnable)
        }
        isCheckingConnectionStatus = false
    }
    fun sendQRMessage(qrMessage: String) {
        val currentTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.ENGLISH).format(Date())
        val jsonMessage = JSONObject()

        jsonMessage.put("toolID", "ANDROID-APP")
        jsonMessage.put("sourceID", sourceId)
        jsonMessage.put("broadcast", false)
        jsonMessage.put("startTS", currentTime)
        jsonMessage.put("QR", qrMessage)


        publish("fromtool-android-app", jsonMessage.toString(), QOS_LEVEL)
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
            client?.subscribe(topic, QOS_LEVEL)  // Set the QoS level here
        } catch (e: MqttException) {
            e.printStackTrace()
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
        } catch (e: MqttException) {
            e.printStackTrace()
        }
    }
}

