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
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.cognisteermvp.api.RetrofitClient
import com.example.cognisteermvp.api.QueryRequest
import com.example.cognisteermvp.ui.theme.CogniSteerMVPTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.altbeacon.beacon.BeaconConsumer
import org.altbeacon.beacon.BeaconManager
import org.altbeacon.beacon.BeaconParser
import org.altbeacon.beacon.Region
import org.json.JSONException
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import android.content.res.AssetManager
import android.annotation.SuppressLint
import java.util.Locale
import androidx.compose.runtime.Composable
import kotlinx.coroutines.Job
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.ui.Alignment


class MainActivity : ComponentActivity() {

    private lateinit var tts: TextToSpeech
    // Flag for voice recognition state and capturing query
    private var isCapturingQuery = false
    private val queryBuffer = StringBuilder()
    private var isScanning = false
    private var lastQueryUpdateTime = 0L
    // Mutable state holds the dynamic prompt text for the UI.
    private var promptText by mutableStateOf("Welcome to CogniSteer!")
    // Flag to indicate whether TTS is currently speaking.
    private var isTtsSpeaking = false
    private var currentZone: String? = null
    private var currentTtsType: String? = null
    private var lastPromptTime = 0L
    private var lastWakeTtsTime = 0L
    private var lastTtsUtterance: String? = null
    private var ignoreVoiceInput: Boolean = false
    // New flag to track if waiting for yes/no response after beacon prompt
    private var isWaitingForBeaconResponse: Boolean = false
    private var beaconResponseTimeoutJob: Job? = null
    private var isStandbyMode by mutableStateOf(false)


    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val AUDIO_PERMISSION_REQUEST_CODE = 2001
        private const val BLUETOOTH_PERMISSION_REQUEST_CODE = 3001
        private const val QUERY_TIMEOUT_MS = 2000L // 2 seconds timeout
        private const val DEBOUNCE_TIMEOUT = 30000L // 30 seconds in milliseconds
        private const val WAKE_TTS_GRACE_PERIOD = 3000L // 1.5 seconds, adjust as needed
        private const val MIN_INPUT_LENGTH = 10
    }

    // Helper to extract recognized text from Vosk JSON output
    private fun extractRecognizedText(jsonString: String): String {
        return try {
            val jsonObj = JSONObject(jsonString)
            when {
                jsonObj.has("text") && jsonObj.getString("text").isNotBlank() -> jsonObj.getString("text")
                jsonObj.has("partial") && jsonObj.getString("partial").isNotBlank() -> jsonObj.getString("partial")
                else -> ""
            }
        } catch (e: JSONException) {
            jsonString
        }
    }

    // Yes/no helper functions
    private fun isYesResponse(input: String): Boolean {
        val normalized = input.trim().toLowerCase(Locale.ROOT)
        return normalized == "yes" || normalized == "yeah" || normalized == "yup" ||
                normalized == "sure" || normalized == "okay" || normalized == "ok" ||
                normalized.contains("yes")
    }

    private fun isNoResponse(input: String): Boolean {
        val normalized = input.trim().toLowerCase(Locale.ROOT)
        return normalized == "no" || normalized == "nope"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CogniSteerMVPTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(modifier = Modifier
                        .padding(innerPadding)
                        .padding(16.dp)) {
                        Text(
                            text = if (isStandbyMode) "App is in standby mode" else promptText,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                isStandbyMode = !isStandbyMode
                                if (isStandbyMode) {
                                    promptText = "App is in standby mode"
                                    tts.speak("App is now in standby mode", TextToSpeech.QUEUE_FLUSH, null, "standby")
                                } else {
                                    promptText = "Welcome to CogniSteer!"
                                    tts.speak("App is now active", TextToSpeech.QUEUE_FLUSH, null, "active")
                                }
                            },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text(text = if (isStandbyMode) "Exit Standby" else "Enter Standby")
                        }
                    }
                }
            }
        }

        // Initialize TTS with an utterance progress listener.
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("TTS", "Language not supported")
                } else {
                    Log.d("TTS", "TextToSpeech initialized")
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            isTtsSpeaking = true
                            Log.d("TTS", "TTS started: $utteranceId")
                        }
                        override fun onDone(utteranceId: String?) {
                            isTtsSpeaking = false
                            ignoreVoiceInput = false // Reset flag when TTS is done
                            // REMOVE THIS BLOCK:
                            // if (utteranceId == "beaconPrompt") {
                            //     isWaitingForBeaconResponse = false
                            // }
                            Log.d("TTS", "TTS done: $utteranceId")
                        }
                        override fun onError(utteranceId: String?) {
                            isTtsSpeaking = false
                            ignoreVoiceInput = false // Reset flag on error
                            // Reset beacon response flag if this was a beacon prompt
                            if (utteranceId == "beaconPrompt") {
                                isWaitingForBeaconResponse = false
                            }
                            Log.e("TTS", "TTS error: $utteranceId")
                        }
                    })
                }
            } else {
                Log.e("TTS", "Initialization failed")
            }
        }

        // Request Bluetooth permissions for Android 12+.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
                    BLUETOOTH_PERMISSION_REQUEST_CODE
                )
            } else {
                Log.d("MainActivity", "Bluetooth permissions granted.")
            }
        }

        // Request location permissions needed for BLE scanning.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                PERMISSION_REQUEST_CODE
            )
        } else {
            initializeBeaconManager()
        }

        // Request RECORD_AUDIO permission for voice recognition.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                AUDIO_PERMISSION_REQUEST_CODE
            )
        } else {
            startVoiceRecognition()
        }

        // Call /health endpoint using Retrofit.
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

    private fun initializeBeaconManager() {
        Log.d("MainActivity", "Initializing BeaconManager for BLE scanning")
        val beaconManager = BeaconManager.getInstanceForApplication(this)
        val iBeaconLayout = "m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24"
        beaconManager.beaconParsers.clear()
        beaconManager.beaconParsers.add(BeaconParser().setBeaconLayout(iBeaconLayout))

        beaconManager.bind(object : BeaconConsumer {
            override fun getApplicationContext(): Context = this@MainActivity
            override fun unbindService(serviceConnection: ServiceConnection) {
                this@MainActivity.unbindService(serviceConnection)
            }
            override fun bindService(intent: Intent, serviceConnection: ServiceConnection, flags: Int): Boolean {
                return this@MainActivity.bindService(intent, serviceConnection, flags)
            }
            override fun onBeaconServiceConnect() {
                beaconManager.addRangeNotifier { beacons, _ ->
                    // Skip beacon processing if in standby mode
                    if (isStandbyMode) {
                        return@addRangeNotifier
                    }
                    if (beacons.isNotEmpty()) {
                        for (beacon in beacons) {
                            val uuid = beacon.id1.toString()
                            val major = beacon.id2.toInt()
                            val minor = beacon.id3.toInt()
                            Log.d("MainActivity", "Detected beacon: UUID=$uuid, Major=$major, Minor=$minor")
                            val areaName = mapBeaconToArea(uuid, major, minor)
                            if (areaName != null) {
                                Log.d("MainActivity", "Mapped beacon to area: $areaName")
                                val currentTime = System.currentTimeMillis()
                                if (areaName != currentZone || (currentTime - lastPromptTime) > DEBOUNCE_TIMEOUT) {
                                    currentZone = areaName
                                    lastPromptTime = currentTime
                                    runOnUiThread {
                                        promptText =
                                            "Welcome user, you are in the $areaName. Do you need assistance with any protocols and procedures?"
                                        lastTtsUtterance = promptText
                                        ignoreVoiceInput = true
                                        isWaitingForBeaconResponse = true // Expect yes/no response

                                        // --- Add the timeout logic here ---
                                        beaconResponseTimeoutJob?.cancel()
                                        beaconResponseTimeoutJob = lifecycleScope.launch {
                                            delay(10000) // 10 seconds
                                            if (isWaitingForBeaconResponse) {
                                                Log.d(
                                                    "VoiceRecognition",
                                                    "Timeout waiting for yes/no response"
                                                )
                                                isWaitingForBeaconResponse = false
                                            }
                                        }
                                        // --- End timeout logic ---

                                        tts.speak(
                                            promptText,
                                            TextToSpeech.QUEUE_FLUSH,
                                            null,
                                            "beaconPrompt"
                                        )
                                    }
                                }else {
                                    Log.d("MainActivity", "Same zone and within debounce period; not re-triggering the prompt.")
                                }
                                break
                            } else {
                                Log.d("MainActivity", "Beacon does not map to any known area: UUID=$uuid, Major=$major, Minor=$minor")
                            }
                        }
                    } else {
                        // No beacons found: reset the current zone
                        if (currentZone != null) {
                            Log.d("MainActivity", "No beacons found; resetting current zone.")
                            currentZone = null
                        }
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
        })
    }

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
                val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
                audioRecord.startRecording()
                Log.d("VoiceRecognition", "AudioRecord started")
                val buffer = ByteArray(bufferSize)
                var wakeWordDetected = false

                while (true) {
                    // Skip processing if in standby mode
                    if (isStandbyMode) {
                        delay(100)
                        continue
                    }
                    Log.d("VoiceRecognition", "State: isTtsSpeaking=$isTtsSpeaking, isWaitingForBeaconResponse=$isWaitingForBeaconResponse, currentTtsType=$currentTtsType")
                    // Skip voice processing if TTS is speaking and ignoreVoiceInput is true
                    if (ignoreVoiceInput && isTtsSpeaking) {
                        Log.d("VoiceRecognition", "Ignoring voice input while TTS is speaking")
                        delay(100) // Small delay to prevent tight loop
                        continue
                    }

                    if (isCapturingQuery && queryBuffer.isNotEmpty() &&
                        System.currentTimeMillis() - lastQueryUpdateTime > QUERY_TIMEOUT_MS) {
                        val finalQuery = queryBuffer.toString().trim()
                        Log.d("VoiceRecognition", "Query capture timeout reached, final query: $finalQuery")
                        isCapturingQuery = false
                        queryBuffer.clear()
                        sendQueryToBackend(finalQuery)
                    }

                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        // Add this check to properly identify beacon prompt responses
                        if (!isTtsSpeaking && isWaitingForBeaconResponse) {
                            val inputText = extractRecognizedText(recognizer.partialResult).trim()
                            if (isYesResponse(inputText)) {
                                Log.d("VoiceRecognition", "Detected 'yes' response for beacon prompt after TTS finished")
                                isWaitingForBeaconResponse = false
                                queryBuffer.clear()
                                val queryText = "Fetch protocol for $currentZone"
                                withContext(Dispatchers.Main) {
                                    lastTtsUtterance = "Fetching protocol for $currentZone"
                                    ignoreVoiceInput = true
                                    tts.speak(lastTtsUtterance, TextToSpeech.QUEUE_FLUSH, null, "beaconYesResponse")
                                }
                                sendQueryToBackend(queryText, currentZone)
                                continue
                            }
                        }
                        if (isTtsSpeaking) {
                            // Handle yes/no responses for beacon prompt
                            if (isWaitingForBeaconResponse && currentTtsType == "beaconPrompt") {
                                val inputText = extractRecognizedText(recognizer.partialResult).trim()
                                if (isYesResponse(inputText)) {
                                    Log.d("VoiceRecognition", "Detected 'yes' response for beacon prompt")
                                    tts.stop()
                                    isTtsSpeaking = false
                                    isWaitingForBeaconResponse = false
                                    queryBuffer.clear()
                                    val queryText = "Fetch protocol for $currentZone"
                                    withContext(Dispatchers.Main) {
                                        lastTtsUtterance = "Fetching protocol for $currentZone"
                                        ignoreVoiceInput = true
                                        tts.speak(lastTtsUtterance, TextToSpeech.QUEUE_FLUSH, null, "beaconYesResponse")
                                    }
                                    sendQueryToBackend(queryText, currentZone)
                                    continue
                                } else if (isNoResponse(inputText)) {
                                    Log.d("VoiceRecognition", "Detected 'no' response for beacon prompt")
                                    tts.stop()
                                    isTtsSpeaking = false
                                    isWaitingForBeaconResponse = false
                                    queryBuffer.clear()
                                    withContext(Dispatchers.Main) {
                                        lastTtsUtterance = "Okay, standing by."
                                        ignoreVoiceInput = true
                                        tts.speak(lastTtsUtterance, TextToSpeech.QUEUE_FLUSH, null, "beaconNoResponse")
                                    }
                                    continue
                                } else {
                                    Log.d("VoiceRecognition", "Ignoring non-yes/no input during beacon prompt TTS")
                                    continue
                                }
                            }
                            // Handle yes/no responses during queryResponse TTS
                            if (currentTtsType == "queryResponse") {
                                val inputText = extractRecognizedText(recognizer.partialResult).trim()
                                if (isYesResponse(inputText) || isNoResponse(inputText)) {
                                    Log.d("VoiceRecognition", "Detected yes/no response during query response: $inputText")
                                    tts.stop()
                                    isTtsSpeaking = false
                                    queryBuffer.clear()
                                    sendQueryToBackend(inputText)
                                } else {
                                    Log.d("VoiceRecognition", "Ignoring non-yes/no input during query response TTS")
                                    continue
                                }
                            } else {
                                // Ignore all input during other TTS types (e.g., wake word prompt)
                                Log.d("VoiceRecognition", "Ignoring input during TTS (type: $currentTtsType)")
                                continue
                            }
                        }

                        if (recognizer.acceptWaveForm(buffer, read)) {
                            val result = recognizer.result
                            Log.d("VoiceRecognition", "Result: $result")
                            if (!wakeWordDetected && result.toLowerCase(Locale.ROOT).contains("hello")) {
                                wakeWordDetected = true
                                lastWakeTtsTime = System.currentTimeMillis()
                                currentTtsType = "wakeWord"
                                withContext(Dispatchers.Main) {
                                    lastTtsUtterance = "How may I assist you?"
                                    ignoreVoiceInput = true
                                    tts.speak(lastTtsUtterance, TextToSpeech.QUEUE_FLUSH, null, "wakeWord")
                                }
                                Log.d("VoiceRecognition", "Wake word detected! TTS started for wake word.")
                                launch {
                                    delay(5000)
                                    wakeWordDetected = false
                                    isCapturingQuery = true
                                    lastQueryUpdateTime = System.currentTimeMillis()
                                    Log.d("VoiceRecognition", "Switched to query capture mode")
                                }
                            } else if (isCapturingQuery) {
                                queryBuffer.append(" ").append(extractRecognizedText(result).trim())
                                lastQueryUpdateTime = System.currentTimeMillis()
                                Log.d("VoiceRecognition", "Query Buffer: $queryBuffer")
                                if (queryBuffer.length > 10) {
                                    val finalQuery = queryBuffer.toString().trim()
                                    Log.d("VoiceRecognition", "Final query captured: $finalQuery")
                                    isCapturingQuery = false
                                    queryBuffer.clear()
                                    sendQueryToBackend(finalQuery)
                                }
                            }
                        } else {
                            val partial = recognizer.partialResult
                            Log.d("VoiceRecognition", "Partial: $partial")
                            if (!wakeWordDetected && partial.toLowerCase(Locale.ROOT).contains("hello")) {
                                wakeWordDetected = true
                                withContext(Dispatchers.Main) {
                                    lastTtsUtterance = "How may I assist you?"
                                    ignoreVoiceInput = true
                                    tts.speak(lastTtsUtterance, TextToSpeech.QUEUE_FLUSH, null, "wakeWord")
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
                                queryBuffer.append(" ").append(extractRecognizedText(partial).trim())
                                lastQueryUpdateTime = System.currentTimeMillis()
                                Log.d("VoiceRecognition", "Query Buffer: $queryBuffer")
                                if (queryBuffer.length > 10) {
                                    val finalQuery = queryBuffer.toString().trim()
                                    Log.d("VoiceRecognition", "Final query captured: $finalQuery")
                                    isCapturingQuery = false
                                    queryBuffer.clear()
                                    sendQueryToBackend(finalQuery)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e("VoiceRecognition", "Error initializing Vosk: ${e.localizedMessage}")
            }
        }
    }

    private fun copyAssetFolder(assetManager: AssetManager, fromAssetPath: String, toPath: String): Boolean {
        return try {
            val files = assetManager.list(fromAssetPath)
            if (files.isNullOrEmpty()) {
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

    private fun sendQueryToBackend(query: String, area: String? = null) {
        Log.d("MainActivity", "Sending query to backend: $query")
        lifecycleScope.launch {
            try {
                val request = QueryRequest(
                    query = query,
                    area = area,
                    sessionId = "exampleSession"
                )
                val response = RetrofitClient.api.processQuery(request)
                if (response.results.isNotEmpty()) {
                    val protocolContent = response.results[0].content
                    Log.d("MainActivity", "Query response: $protocolContent")
                    currentTtsType = "queryResponse"
                    withContext(Dispatchers.Main) {
                        lastTtsUtterance = protocolContent
                        ignoreVoiceInput = true
                        tts.speak(protocolContent, TextToSpeech.QUEUE_FLUSH, null, "queryResponse")
                    }
                } else {
                    Log.d("MainActivity", "Query response: No results found.")
                    withContext(Dispatchers.Main) {
                        lastTtsUtterance = "No results found."
                        ignoreVoiceInput = true
                        tts.speak(lastTtsUtterance, TextToSpeech.QUEUE_FLUSH, null, "queryResponse")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e("MainActivity", "Error processing query: ${e.localizedMessage}")
                withContext(Dispatchers.Main) {
                    lastTtsUtterance = "I encountered an error processing your request."
                    ignoreVoiceInput = true
                    tts.speak(lastTtsUtterance, TextToSpeech.QUEUE_FLUSH, null, "error")
                }
            }
        }
    }

    private fun mapBeaconToArea(uuid: String, major: Int, minor: Int): String? {
        return when {
            uuid.equals("12345678-1234-5678-1234-56789abcdef0", ignoreCase = true) &&
                    major == 2 && minor == 1 -> "Security Zone"
            else -> null
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "OK $name!",
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