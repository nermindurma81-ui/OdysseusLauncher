package com.odysseus.launcher

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.IOException
import java.net.Socket
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var btn9Router: Button
    private lateinit var btnServers: Button
    private lateinit var btnOdysseus: Button
    private lateinit var webView: WebView
    private lateinit var loadingView: View
    private lateinit var errorView: View
    private lateinit var mainContent: View
    private lateinit var retryButton: Button
    private lateinit var errorText: TextView
    private lateinit var statusDot9Router: ImageView
    private lateinit var statusText9Router: TextView
    private lateinit var statusDotServers: ImageView
    private lateinit var statusTextServers: TextView
    private lateinit var statusDotOdysseus: ImageView
    private lateinit var statusTextOdysseus: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        btn9Router = findViewById(R.id.btn9Router)
        btnServers = findViewById(R.id.btnServers)
        btnOdysseus = findViewById(R.id.btnOdysseus)
        webView = findViewById(R.id.webView)
        loadingView = findViewById(R.id.loadingView)
        errorView = findViewById(R.id.errorView)
        mainContent = findViewById(R.id.mainContent)
        retryButton = findViewById(R.id.retryButton)
        errorText = findViewById(R.id.errorText)
        statusDot9Router = findViewById(R.id.statusDot9Router)
        statusText9Router = findViewById(R.id.statusText9Router)
        statusDotServers = findViewById(R.id.statusDotServers)
        statusTextServers = findViewById(R.id.statusTextServers)
        statusDotOdysseus = findViewById(R.id.statusDotOdysseus)
        statusTextOdysseus = findViewById(R.id.statusTextOdysseus)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                loadingView.visibility = View.GONE
                webView.visibility = View.VISIBLE
            }
        }
        btn9Router.setOnClickListener { startService("9router", 20128) }
        btnServers.setOnClickListener { startService("servers", 4000) }
        btnOdysseus.setOnClickListener { startService("odysseus", 7000) }
        retryButton.setOnClickListener { checkAllServices() }
        checkAllServices()
    }

    private fun startService(service: String, port: Int) {
        showLoading()
        updateStatus(service, getString(R.string.status_starting), android.R.color.holo_orange_light)
        executor.execute {
            try {
                Thread.sleep(2000)
                pollPort(port, 30) { isAvailable ->
                    handler.post {
                        if (isAvailable) {
                            updateStatus(service, getString(R.string.status_active), android.R.color.holo_green_dark)
                            if (service == "odysseus") loadWebView()
                            hideLoading()
                        } else {
                            updateStatus(service, getString(R.string.status_error), android.R.color.holo_red_dark)
                            showError("Servis nije pokrenut na portu $port")
                        }
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    updateStatus(service, getString(R.string.status_error), android.R.color.holo_red_dark)
                    showError("Greska: ${e.message}")
                }
            }
        }
    }

    private fun pollPort(port: Int, maxAttempts: Int, callback: (Boolean) -> Unit) {
        var attempts = 0
        executor.execute {
            while (attempts < maxAttempts) {
                attempts++
                try {
                    Socket("127.0.0.1", port).use { if (it.isConnected) { callback(true); return@execute } }
                } catch (e: IOException) {}
                if (attempts < maxAttempts) Thread.sleep(500)
            }
            callback(false)
        }
    }

    private fun checkAllServices() {
        errorView.visibility = View.GONE
        showLoading()
        executor.execute {
            val r = mapOf("9router" to checkPort(20128), "servers" to checkPort(4000), "odysseus" to checkPort(7000))
            handler.post {
                r.forEach { (s, a) ->
                    updateStatus(s, if (a) getString(R.string.status_active) else getString(R.string.status_inactive),
                        if (a) android.R.color.holo_green_dark else android.R.color.holo_orange_light)
                }
                if (r["odysseus"] == true) loadWebView()
                hideLoading()
            }
        }
    }

    private fun checkPort(port: Int): Boolean = try { Socket("127.0.0.1", port).use { it.isConnected } } catch (e: IOException) { false }
    private fun loadWebView() { webView.visibility = View.VISIBLE; webView.loadUrl("http://127.0.0.1:7000") }
    private fun showLoading() { loadingView.visibility = View.VISIBLE; mainContent.visibility = View.GONE; errorView.visibility = View.GONE }
    private fun hideLoading() { loadingView.visibility = View.GONE; mainContent.visibility = View.VISIBLE }
    private fun showError(msg: String) { errorText.text = msg; errorView.visibility = View.VISIBLE; mainContent.visibility = View.GONE; loadingView.visibility = View.GONE }
    private fun updateStatus(service: String, text: String, color: Int) {
        val d = when(service) { "9router" -> statusDot9Router; "servers" -> statusDotServers; else -> statusDotOdysseus }
        val t = when(service) { "9router" -> statusText9Router; "servers" -> statusTextServers; else -> statusTextOdysseus }
        t.text = text; d.setColorFilter(resources.getColor(color, null))
    }
}
