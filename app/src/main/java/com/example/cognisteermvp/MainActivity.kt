package com.example.cognisteermvp

import androidx.lifecycle.lifecycleScope
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.cognisteermvp.ui.theme.CogniSteerMVPTheme
import android.util.Log
import kotlinx.coroutines.launch
import com.example.cognisteermvp.api.RetrofitClient


class MainActivity : ComponentActivity() {
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
        lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.checkHealth()
                // We’ll display a simple log or toast
                Log.d("MainActivity", "Health check: ${response.status}, ${response.message}")
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e("MainActivity", "Error calling /health endpoint: ${e.localizedMessage}")
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