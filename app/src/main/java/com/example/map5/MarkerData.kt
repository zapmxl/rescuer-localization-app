package com.example.map5

    data class MarkerData(
        val sourceId: String,
        val toolID: String,
        val latitude: Double,
        val longitude: Double,
        val heading: Double,
        val altitude: Double,
        val mounting: String?,
        val speed: Double?,
        val distance: Double?,
        val quality: Int?,
        val qualityHeading: Int?,
        val outdoor: Boolean?,
        val topic: String
    )