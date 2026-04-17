package com.brachaai.app
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Tell the app where to look fo r the test audio file
        val dummyFile = File(cacheDir, "נעה חדד_260415_165702.m4a")

        // 2. Grab your OpenAI API key securely
        val myApiKey = BuildConfig.OPENAI_API_KEY
        val processor = AudioProcessor(myApiKey)

        // 3. Draw the User Interface (A simple button in the middle of the screen)
        setContent {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Button(onClick = {
                    // 4. When the button is clicked, start the background process!
                    CoroutineScope(Dispatchers.Main).launch {
                        processor.processAndSendToBackend(dummyFile)
                    }
                }) {
                    Text("Test Bracha AI Process")
                }
            }
        }
    }
}