package com.brachaai.app

import android.util.Log
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun WebViewScreen(
    url: String,
    onAuthenticated: () -> Unit = {},
    onWebViewCreated: (WebView) -> Unit = {}
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowContentAccess = true
                    allowFileAccess = true
                    allowFileAccessFromFileURLs = true
                    allowUniversalAccessFromFileURLs = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
                }
                addJavascriptInterface(AuthBridge(context, onAuthenticated), AuthBridge.JS_NAME)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        Log.d("WebView", "Page loaded: $url")
                    }
                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError
                    ) {
                        Log.e("WebView", "Error: ${error.description} for ${request.url}")
                    }
                }
                onWebViewCreated(this)
                loadUrl(url)
            }
        }
    )
}