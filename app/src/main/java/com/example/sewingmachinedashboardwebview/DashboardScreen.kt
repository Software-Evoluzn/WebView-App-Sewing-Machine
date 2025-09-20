package com.example.sewingmachinedashboardwebview

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.OutputStream
import android.util.Base64

class DashBoardScreenViewModel : androidx.lifecycle.ViewModel() {
    var downloadId: Long? by mutableStateOf(null)
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashBoardScreen(vm: DashBoardScreenViewModel = viewModel()) {
    val context = LocalContext.current

    // Register receiver for DownloadManager completions
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != null && id == vm.downloadId) {
                    Toast.makeText(context, "Download complete!", Toast.LENGTH_LONG).show()
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        onDispose { context.unregisterReceiver(receiver) }
    }

    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            settings.javaScriptEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.domStorageEnabled = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.allowFileAccess = true
            settings.allowContentAccess = true

            WebView.setWebContentsDebuggingEnabled(true) // Enable JS console

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean = false

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)

                    // Inject JS to hook blob downloads
                    val jsHook = """
    (function() {
        const origCreateObjectURL = URL.createObjectURL;
        URL.createObjectURL = function(blob) {
            try {
                var reader = new FileReader();
                reader.onloadend = function() {
                    var base64data = reader.result.split(',')[1];
                    // Set filename with .xlsx
                    var filename = "data.xlsx";
                    Android.saveFile(filename, base64data);
                    console.log("Blob saved via JS hook: " + filename);
                };
                reader.readAsDataURL(blob);
            } catch(e) {
                console.log("Failed to read blob: " + e);
            }
            return origCreateObjectURL.call(URL, blob);
        };
    })();
""".trimIndent()


                    evaluateJavascript(jsHook, null)
                }
            }

            webChromeClient = WebChromeClient()

            // JS interface to save files
            addJavascriptInterface(object {
                @JavascriptInterface
                fun saveFile(filename: String, base64Data: String) {
                    Log.d("print","save file function call from javascript interface: $filename")
                    val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                    saveFileToDownloads(context, filename, bytes)
                }
            }, "Android")

            // Handle normal HTTP/HTTPS downloads
            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                val filename = URLUtil.guessFileName(url, contentDisposition, mimeType)
                Toast.makeText(context, "Download started: $filename", Toast.LENGTH_SHORT).show()
                Log.d("print", "Download clicked: $filename")

                if (url.startsWith("blob:")) {
                    Log.d("print","Blob link clicked. JS hook will handle this automatically.")
                    // No need to do xhr.open, the JS hook will catch it
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        Log.d("print","Saving HTTP download to MediaStore")
                        Thread {
                            try {
                                val conn = java.net.URL(url).openConnection()
                                conn.setRequestProperty("User-Agent", userAgent)
                                val bytes = conn.getInputStream().readBytes()
                                saveFileToDownloads(context, filename, bytes)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }.start()
                    } else {
                        Log.d("print","Using DownloadManager for legacy Android")
                        val request = DownloadManager.Request(Uri.parse(url))
                        request.setMimeType(mimeType)
                        request.addRequestHeader("User-Agent", userAgent)
                        request.setTitle(filename)
                        request.setDescription("Downloading file...")
                        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                        vm.downloadId = dm.enqueue(request)
                    }
                }
            }

            // Load your dashboard URL
            loadUrl("http://192.168.0.2:5000/")
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("MACHINE DASHBOARD") },
            actions = {
                IconButton(onClick = { webView.reload() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            }
        )
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
    }
}

/**
 * Save bytes into Downloads folder using MediaStore
 */
fun saveFileToDownloads(context: Context, filename: String, bytes: ByteArray) {
    try {
        val resolver = context.contentResolver
        val values = android.content.ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "*/*")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            resolver.openOutputStream(uri).use { out ->
                out?.write(bytes)
                out?.flush()
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context, "Download complete: $filename", Toast.LENGTH_LONG).show()
                Log.d("print", "File saved: $filename")
            }
        } else {
            Toast.makeText(context, "Failed to save: $filename", Toast.LENGTH_LONG).show()
            Log.e("print", "Failed to insert MediaStore entry for: $filename")
        }
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "Error saving file: ${e.message}", Toast.LENGTH_LONG).show()
        Log.e("print", "Exception saving file: $filename", e)
    }
}
