package com.treugolniy.sleepjournal

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private var pendingFileName: String = ""
    private var pendingMimeType: String = "application/octet-stream"
    private var pendingFileBase64: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(12, 14, 20)
        window.navigationBarColor = Color.rgb(12, 14, 20)

        webView = WebView(this)
        webView.setBackgroundColor(Color.rgb(12, 14, 20))
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain(ASSET_DOMAIN)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }
        webView.addJavascriptInterface(SleepJavascriptBridge(this), "SleepNative")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest) =
                assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                val uri = request.url
                return uri.scheme != "https" || uri.host != ASSET_DOMAIN || !uri.path.orEmpty().startsWith("/assets/")
            }
        }
        webView.webChromeClient = WebChromeClient()
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(12, 14, 20))
            addView(webView)
        })
        webView.loadUrl("https://$ASSET_DOMAIN/assets/index.html")
        requestNotificationPermission()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    internal fun requestSaveFile(fileName: String, mimeType: String, base64: String) {
        runOnUiThread {
            pendingFileName = fileName.substringAfterLast('/').ifBlank { "dnevnik-sna" }
            pendingMimeType = mimeType.ifBlank { "application/octet-stream" }
            pendingFileBase64 = base64
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = pendingMimeType
                putExtra(Intent.EXTRA_TITLE, pendingFileName)
            }
            try {
                startActivityForResult(intent, REQUEST_CREATE_FILE)
            } catch (error: Exception) {
                Toast.makeText(this, "Не удалось открыть сохранение файла: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    @Deprecated("Required for the Android document picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CREATE_FILE) return
        if (resultCode != RESULT_OK) {
            pendingFileBase64 = ""
            return
        }
        val uri: Uri = data?.data ?: return
        try {
            val bytes = Base64.decode(pendingFileBase64, Base64.DEFAULT)
            contentResolver.openOutputStream(uri)?.use { output -> output.write(bytes) }
                ?: error("Не удалось открыть файл для записи")
            Toast.makeText(this, "Файл сохранён", Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            Toast.makeText(this, "Ошибка сохранения: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
        } finally {
            pendingFileBase64 = ""
            pendingFileName = ""
        }
    }

    @Deprecated("Use the page navigation controls when available")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("SleepNative")
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    private class SleepJavascriptBridge(private val activity: MainActivity) {
        @JavascriptInterface
        fun getTrackingState(): String = SleepBridgeStore.stateJson(activity)

        @JavascriptInterface
        fun getPendingEvents(): String = SleepBridgeStore.pendingEventsJson(activity)

        @JavascriptInterface
        fun acknowledgeEvents(idsJson: String): String {
            return try {
                SleepBridgeStore.acknowledgeEvents(activity, idsJson)
                JSONObject().put("ok", true).toString()
            } catch (error: Exception) {
                JSONObject().put("ok", false).put("error", error.localizedMessage ?: "Ошибка подтверждения").toString()
            }
        }

        @JavascriptInterface
        fun startSleep(startedAt: Long, nightDate: String): String {
            return try {
                val intent = Intent(activity, SleepTrackerService::class.java).apply {
                    action = SleepTrackerService.ACTION_START
                    putExtra(SleepTrackerService.EXTRA_STARTED_AT, startedAt)
                    putExtra(SleepTrackerService.EXTRA_NIGHT_DATE, nightDate)
                }
                activity.startForegroundService(intent)
                JSONObject().put("ok", true).toString()
            } catch (error: Exception) {
                JSONObject().put("ok", false).put("error", error.localizedMessage ?: "Не удалось запустить Android-сервис").toString()
            }
        }

        @JavascriptInterface
        fun stopSleep(): String {
            return try {
                activity.startService(Intent(activity, SleepTrackerService::class.java).setAction(SleepTrackerService.ACTION_STOP))
                JSONObject().put("ok", true).toString()
            } catch (error: Exception) {
                JSONObject().put("ok", false).put("error", error.localizedMessage ?: "Не удалось остановить Android-сервис").toString()
            }
        }

        @JavascriptInterface
        fun saveFile(fileName: String, mimeType: String, base64: String): String {
            return try {
                require(base64.length <= MAX_BASE64_LENGTH) { "Файл слишком большой для передачи" }
                activity.requestSaveFile(fileName, mimeType, base64)
                JSONObject().put("ok", true).toString()
            } catch (error: Exception) {
                JSONObject().put("ok", false).put("error", error.localizedMessage ?: "Не удалось сохранить файл").toString()
            }
        }
    }

    companion object {
        private const val ASSET_DOMAIN = "appassets.androidplatform.net"
        private const val REQUEST_CREATE_FILE = 8101
        private const val REQUEST_NOTIFICATIONS = 8102
        private const val MAX_BASE64_LENGTH = 40 * 1024 * 1024
    }
}
