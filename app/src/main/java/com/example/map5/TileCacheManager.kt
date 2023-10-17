package com.example.map5

import android.content.Context
import android.util.Log
import okhttp3.*
import java.io.File

class TileCacheManager(private val context: Context) {

    private val cacheDir = File(context.cacheDir, "map_tiles")
    private val dataDir = File(context.filesDir, "map_tiles")

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val tileName = request.url.toUri().path.replace("/", "_")
                val cacheFile = File(cacheDir, tileName)
                val dataFile = File(dataDir, tileName)

                when {
                    dataFile.exists() -> {
                        Log.d("TileCacheManager", "Serving tile from data directory: ${dataFile.name}")
                        val responseBody = ResponseBody.create(null, dataFile.readBytes())
                        Response.Builder()
                            .code(200)
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .body(responseBody)
                            .message("From Data Directory")
                            .build()
                    }
                    cacheFile.exists() -> {
                        Log.d("TileCacheManager", "Serving tile from cache: ${cacheFile.name}")
                        val responseBody = ResponseBody.create(null, cacheFile.readBytes())
                        Response.Builder()
                            .code(200)
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .body(responseBody)
                            .message("From Cache")
                            .build()
                    }
                    else -> {
                        Log.d("TileCacheManager", "Fetching tile from network: ${request.url.toUri().path}")
                        val response = chain.proceed(request)
                        if (response.isSuccessful) {
                            val bytes = response.body?.bytes()
                            if (bytes != null) {
                                cacheFile.writeBytes(bytes)
                                response.newBuilder().body(ResponseBody.create(response.body?.contentType(), bytes)).build()
                            } else {
                                response
                            }
                        } else {
                            response
                        }
                    }
                }
            }
            .build()
    }

    fun persistCacheToData() {
        val destinationDirectory = context.filesDir

        val files = cacheDir.listFiles()
        if (files.isNullOrEmpty()) {
            Log.d("TileCacheManager", "No files in cache to move!")
            return
        }

        files.forEach { file ->
            val destinationFile = File(destinationDirectory, file.name)
            try {
                file.copyTo(destinationFile, overwrite = true)
                file.delete()
            } catch (e: Exception) {
                Log.e("TileCacheManager", "Error moving file: ${file.name}", e)
            }
        }
    }
}
