package com.brachaai.app

import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.compose.runtime.Composable
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun WebViewScreen(url: String, onWebViewCreated: (WebView) -> Unit = {}) {
    AndroidView(factory = { context ->
        WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true   // needed for JWT token storage
                allowContentAccess = true
                loadWithOverviewMode = true
                useWideViewPort = true
            }
            webViewClient = WebViewClient()
            onWebViewCreated(this)
            loadUrl(url)
        }
    })
}