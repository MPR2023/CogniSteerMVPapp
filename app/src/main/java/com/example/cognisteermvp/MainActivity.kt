@file:Suppress("DEPRECATION")

package com.example.cognisteermvp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.RemoteException
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.cognisteermvp.ui.theme.CogniSteerMVPTheme
import com.example.cognisteermvp.api.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import org.altbeacon.beacon.BeaconConsumer
import org.altbeacon.beacon.BeaconManager
import org.altbeacon.beacon.BeaconParser
import org.altbeacon.beacon.Region
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import android.content.res.AssetManager
import android.annotation.SuppressLint
import android.speech.tts.TextToSpeech
import java.util.Locale
import com.example.cognisteermvp.api.QueryRequest
import org.json.JSONObject
import org.json.JSONException

class MainActivity : ComponentActivity() {

    private lateinit var tts: TextToSpeech
    private var isCapturingQuery = false
    private val queryBuffer = StringBuilder()
    private var isScanning = false
    private var lastQueryUpdateTime = 0L

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val AUDIO_PERMISSION_REQUEST_CODE = 2001
        private const val BLUETOOTH_PERMISSION_REQUEST_CODE = 3001
        private const val QUERY_TIMEOUT_MS = 2000L // 2 seconds timeout
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CogniSteerMVPTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Android",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("TTS", "Language not supported")
                } else {
                    Log.d("TTS", "TextToSpeech initialized")
                }
            } else {
                Log.e("TTS", "Initialization failed")
            }
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ),
                    BLUETOOTH_PERMISSION_REQUEST_CODE
                )
            } else {
                Log.d("MainActivity", "Bluetooth permissions granted.")
            }
        }

        // Request location permissions needed for BLE scanning
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                PERMISSION_REQUEST_CODE
            )
        } else {
            initializeBeaconManager()
        }

        // Request RECORD_AUDIO permission for voice recognition
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                AUDIO_PERMISSION_REQUEST_CODE
            )
        } else {
            startVoiceRecognition()
        }

        // Call /health endpoint using Retrofit
        lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.checkHealth()
                Log.d("MainActivity", "Health check: ${response.status}, ${response.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Health: ${response.status}, ${response.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e("MainActivity", "Error calling /health endpoint: ${e.localizedMessage}")
            }
        }
    }

    // Initialize BeaconManager for BLE scanning using the Android Beacon Library
    private fun initializeBeaconManager() {
        Log.d("MainActivity", "Initializing BeaconManager for BLE scanning")
        val beaconManager = BeaconManager.getInstanceForApplication(this)
        val iBeaconLayout = "m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24"

        beaconManager.beaconParsers.clear()
        beaconManager.beaconParsers.add(BeaconParser().setBeaconLayout(iBeaconLayout))

        beaconManager.bind(object : BeaconConsumer {
            override fun onBeaconServiceConnect() {
                beaconManager.addRangeNotifier { beacons, _ ->
                    if (beacons.isNotEmpty()) {
                        Log.d("MainActivity", "Beacons found: ${beacons.joinToString { it.id1.toString() }}")
                    } else {
                        Log.d("MainActivity", "No beacons found in range.")
                    }
                }
                try {
                    if (!isScanning) {
                        beaconManager.startRangingBeaconsInRegion(Region("myRangingUniqueId", null, null, null))
                        isScanning = true
                        Log.d("MainActivity", "BLE scan started")
                    } else {
                        Log.d("MainActivity", "BLE scan already running; not starting a new scan.")
                    }
                } catch (e: RemoteException) {
                    e.printStackTrace()
                    Log.e("MainActivity", "Error starting beacon ranging: ${e.localizedMessage}")
                }
            }

            override fun getApplicationContext(): Context = this@MainActivity

            override fun unbindService(p0: ServiceConnection?) {
                if (p0 != null) {
                    this@MainActivity.unbindService(p0)
                }
            }

            override fun bindService(intent: Intent?, serviceConnection: ServiceConnection, flags: Int): Boolean {
                return if (intent != null) {
                    this@MainActivity.bindService(intent, serviceConnection, flags)
                } else {
                    false
                }
            }
        })
    }

    // Function to start offline voice recognition using Vosk.
    // It copies the Vosk model from assets (if needed) and then initializes the model.
    @SuppressLint("MissingPermission")
    private fun startVoiceRecognition() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val modelDir = File(filesDir, "vosk-model-small-en-us-0.15")
                if (!modelDir.exists()) {
                    if (!copyAssetFolder(assets, "vosk-model-small-en-us-0.15", modelDir.absolutePath)) {
                        Log.e("VoiceRecognition", "Failed to copy Vosk model from assets")
                        return@launch
                    }
                }
                val model = Model(modelDir.absolutePath)
                val sampleRate = 16000
                val recognizer = Recognizer(model, sampleRate.toFloat())

                val bufferSize = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
                audioRecord.startRecording()
                Log.d("VoiceRecognition", "AudioRecord started")
                val buffer = ByteArray(bufferSize)

                var wakeWordDetected = false

                while (true) {
                    // Check for query capture timeout at the start of each iteration.
                    if (isCapturingQuery && queryBuffer.isNotEmpty() && System.currentTimeMillis() - lastQueryUpdateTime > QUERY_TIMEOUT_MS) {
                        val finalQuery = queryBuffer.toString().trim()
                        Log.d("VoiceRecognition", "Query capture timeout reached, final query: $finalQuery")
                        isCapturingQuery = false
                        queryBuffer.clear()
                        sendQueryToBackend(finalQuery)
                    }

                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        if (recognizer.acceptWaveForm(buffer, read)) {
                            val result = recognizer.result
                            Log.d("VoiceRecognition", "Result: $result")
                            if (!wakeWordDetected && result.toLowerCase(Locale.ROOT).contains("hello")) {
                                wakeWordDetected = true
                                withContext(Dispatchers.Main) {
                                    tts.speak("How may I assist you?", TextToSpeech.QUEUE_FLUSH, null, "wakeWord")
                                }
                                Log.d("VoiceRecognition", "Wake word detected!")
                                launch {
                                    delay(5000)
                                    wakeWordDetected = false
                                    isCapturingQuery = true
                                    lastQueryUpdateTime = System.currentTimeMillis()
                                    Log.d("VoiceRecognition", "Switched to query capture mode")
                                }
                            } else if (isCapturingQuery) {
                                queryBuffer.append(" ").append(result.trim())
                                lastQueryUpdateTime = System.currentTimeMillis()
                                Log.d("VoiceRecognition", "Query Buffer: $queryBuffer")
                                if (queryBuffer.length > 10) { // Adjust threshold as needed
                                    val finalQuery = queryBuffer.toString().trim()
                                    // Extract plain text from the JSON output if available.
                                    val finalQueryText = try {
                                        val jsonResult = JSONObject(finalQuery)
                                        when {
                                            jsonResult.has("text") && jsonResult.getString("text").isNotBlank() ->
                                                jsonResult.getString("text")
                                            jsonResult.has("partial") && jsonResult.getString("partial").isNotBlank() ->
                                                jsonResult.getString("partial")
                                            else -> finalQuery
                                        }
                                    } catch (e: JSONException) {
                                        finalQuery
                                    }
                                    Log.d("VoiceRecognition", "Final query captured: $finalQueryText")
                                    isCapturingQuery = false
                                    queryBuffer.clear()
                                    sendQueryToBackend(finalQueryText)
                                }
                            }
                        } else {
                            val partial = recognizer.partialResult
                            Log.d("VoiceRecognition", "Partial: $partial")
                            if (!wakeWordDetected && partial.toLowerCase(Locale.ROOT).contains("hello")) {
                                wakeWordDetected = true
                                withContext(Dispatchers.Main) {
                                    tts.speak("How may I assist you?", TextToSpeech.QUEUE_FLUSH, null, "wakeWord")
                                }
                                Log.d("VoiceRecognition", "Wake word detected (partial)!")
                                launch {
                                    delay(5000)
                                    wakeWordDetected = false
                                    isCapturingQuery = true
                                    lastQueryUpdateTime = System.currentTimeMillis()
                                    Log.d("VoiceRecognition", "Switched to query capture mode")
                                }
                            } else if (isCapturingQuery) {
                                queryBuffer.append(" ").append(partial.trim())
                                lastQueryUpdateTime = System.currentTimeMillis()
                                Log.d("VoiceRecognition", "Query Buffer: $queryBuffer")
                                if (queryBuffer.length > 10) { // Adjust threshold as needed
                                    val finalQuery = queryBuffer.toString().trim()
                                    val finalQueryText = try {
                                        val jsonResult = JSONObject(finalQuery)
                                        when {
                                            jsonResult.has("text") && jsonResult.getString("text").isNotBlank() ->
                                                jsonResult.getString("text")
                                            jsonResult.has("partial") && jsonResult.getString("partial").isNotBlank() ->
                                                jsonResult.getString("partial")
                                            else -> finalQuery
                                        }
                                    } catch (e: JSONException) {
                                        finalQuery
                                    }
                                    Log.d("VoiceRecognition", "Final query captured: $finalQueryText")
                                    isCapturingQuery = false
                                    queryBuffer.clear()
                                    sendQueryToBackend(finalQueryText)
                                }
                            }
                        }
                    }
                    if (isCapturingQuery && System.currentTimeMillis() - lastQueryUpdateTime > QUERY_TIMEOUT_MS && queryBuffer.isNotEmpty()) {
                        val finalQuery = queryBuffer.toString().trim()
                        Log.d("VoiceRecognition", "Query capture timeout reached, final query: $finalQuery")
                        isCapturingQuery = false
                        queryBuffer.clear()
                        sendQueryToBackend(finalQuery)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e("VoiceRecognition", "Error initializing Vosk: ${e.localizedMessage}")
            }
        }
    }

    // Helper function to recursively copy an asset folder to a destination path
    private fun copyAssetFolder(assetManager: AssetManager, fromAssetPath: String, toPath: String): Boolean {
        return try {
            val files = assetManager.list(fromAssetPath)
            if (files.isNullOrEmpty()) {
                // It's a file; copy it
                copyAssetFile(assetManager, fromAssetPath, toPath)
            } else {
                val dir = File(toPath)
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                for (file in files) {
                    val success = copyAssetFolder(assetManager, "$fromAssetPath/$file", "$toPath/$file")
                    if (!success) return false
                }
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // Helper function to copy a single asset file to a destination path
    private fun copyAssetFile(assetManager: AssetManager, fromAssetPath: String, toPath: String): Boolean {
        return try {
            assetManager.open(fromAssetPath).use { inputStream ->
                FileOutputStream(File(toPath)).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    initializeBeaconManager()
                } else {
                    Log.e("MainActivity", "Location permissions not granted!")
                    Toast.makeText(
                        this,
                        "Location permissions are required for BLE scanning",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            AUDIO_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    startVoiceRecognition()
                } else {
                    Log.e("MainActivity", "Record audio permission not granted!")
                    Toast.makeText(
                        this,
                        "Record audio permission is required for voice recognition",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            BLUETOOTH_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    Log.d("MainActivity", "Bluetooth permissions granted.")
                } else {
                    Log.e("MainActivity", "Bluetooth permissions not granted!")
                    Toast.makeText(
                        this,
                        "Bluetooth permissions are required for BLE scanning",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun sendQueryToBackend(query: String) {
        Log.d("MainActivity", "Sending query to backend: $query")
        lifecycleScope.launch {
            try {
                val request = QueryRequest(query = query, sessionId = "exampleSession")
                val response = RetrofitClient.api.processQuery(request)
                // Assuming you want to use the first protocol's content:
                if (response.results.isNotEmpty()) {
                    val protocolContent = response.results[0].content
                    Log.d("MainActivity", "Query response: $protocolContent")
                    // Use TTS to speak the response on the main thread
                    withContext(Dispatchers.Main) {
                        tts.speak(protocolContent, TextToSpeech.QUEUE_FLUSH, null, "queryResponse")
                    }
                } else {
                    Log.d("MainActivity", "Query response: No results found.")
                    withContext(Dispatchers.Main) {
                        tts.speak("No results found.", TextToSpeech.QUEUE_FLUSH, null, "queryResponse")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e("MainActivity", "Error processing query: ${e.localizedMessage}")
                withContext(Dispatchers.Main) {
                    tts.speak("I encountered an error processing your request.", TextToSpeech.QUEUE_FLUSH, null, "error")
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    CogniSteerMVPTheme {
        Greeting("Android")
    }
}
