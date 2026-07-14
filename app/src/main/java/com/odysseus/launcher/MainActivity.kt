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
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.min

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
    private var pollingTask: Future<*>? = null

    companion object {
        private const val CONNECT_TIMEOUT_MS = 500
        private const val POLL_INTERVAL_MS = 500L
    }

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

    override fun onDestroy() {
        cancelPollingTask()
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun startService(service: String, port: Int) {
        cancelPollingTask()
        showLoading()
        updateStatus(service, getString(R.string.status_starting), android.R.color.holo_orange_light)
        pollingTask = executor.submit {
            try {
                Thread.sleep(2000)
                val isAvailable = pollPort(port, 30)
                postIfAlive {
                    if (isAvailable) {
                        updateStatus(service, getString(R.string.status_active), android.R.color.holo_green_dark)
                        if (service == "odysseus") loadWebView()
                        hideLoading()
                    } else {
                        updateStatus(service, getString(R.string.status_error), android.R.color.holo_red_dark)
                        showError("Servis nije pokrenut na portu $port")
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                postIfAlive {
                    updateStatus(service, getString(R.string.status_error), android.R.color.holo_red_dark)
                    showError("Greska: ${e.message}")
                }
            }
        }
    }

    private fun pollPort(port: Int, maxAttempts: Int): Boolean {
        val deadline = System.currentTimeMillis() + (maxAttempts * POLL_INTERVAL_MS)
        var attempts = 0
        while (attempts < maxAttempts && !Thread.currentThread().isInterrupted) {
            attempts++
            if (checkPort(port, deadline)) return true
            if (attempts < maxAttempts) Thread.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    private fun checkAllServices() {
        cancelPollingTask()
        errorView.visibility = View.GONE
        showLoading()
        pollingTask = executor.submit {
            val r = mapOf("9router" to checkPort(20128), "servers" to checkPort(4000), "odysseus" to checkPort(7000))
            postIfAlive {
                r.forEach { (s, a) ->
                    updateStatus(s, if (a) getString(R.string.status_active) else getString(R.string.status_inactive),
                        if (a) android.R.color.holo_green_dark else android.R.color.holo_orange_light)
                }
                if (r["odysseus"] == true) loadWebView()
                hideLoading()
            }
        }
    }

    private fun checkPort(port: Int, deadline: Long = System.currentTimeMillis() + CONNECT_TIMEOUT_MS): Boolean {
        val remainingMs = deadline - System.currentTimeMillis()
        if (remainingMs <= 0L) return false
        val timeoutMs = min(CONNECT_TIMEOUT_MS.toLong(), remainingMs).toInt()
        return try {
            Socket().use { socket ->
                socket.soTimeout = timeoutMs
                socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                socket.isConnected
            }
        } catch (e: IOException) {
            false
        }
    }

    private fun cancelPollingTask() {
        pollingTask?.cancel(true)
        pollingTask = null
    }

    private fun postIfAlive(action: () -> Unit) {
        handler.post {
            if (!isFinishing && !isDestroyed) action()
        }
    }
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
