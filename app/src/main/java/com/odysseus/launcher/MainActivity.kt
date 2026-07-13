package com.odysseus.launcher

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorView: View
    private lateinit var errorText: TextView
    private lateinit var startServerButton: Button

    private val serverUrl = "http://localhost:7000"

    // Putanja do skripte unutar Termux-a (home foldera)
    private val startScriptPath = "/data/data/com.termux/files/home/start_all.sh"
    private val termuxWorkDir = "/data/data/com.termux/files/home"

    private val requestRunCommandPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                sendStartServerCommand()
            } else {
                Toast.makeText(this, getString(R.string.run_command_permission_needed), Toast.LENGTH_LONG).show()
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        errorView = findViewById(R.id.errorView)
        errorText = findViewById(R.id.errorText)

        val retryButton = findViewById<Button>(R.id.retryButton)
        retryButton.setOnClickListener { loadOdysseus() }

        startServerButton = findViewById(R.id.startServerButton)
        startServerButton.setOnClickListener { onStartServerClicked() }

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.mediaPlaybackRequiresUserGesture = false

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
                errorView.visibility = View.GONE
                webView.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                webView.visibility = View.VISIBLE
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    progressBar.visibility = View.GONE
                    webView.visibility = View.GONE
                    errorView.visibility = View.VISIBLE
                    errorText.text = getString(R.string.connection_error, serverUrl)
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        loadOdysseus()
    }

    private fun loadOdysseus() {
        errorView.visibility = View.GONE
        webView.loadUrl(serverUrl)
    }

    private fun onStartServerClicked() {
        val permission = "com.termux.permission.RUN_COMMAND"
        val alreadyGranted = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

        if (alreadyGranted) {
            sendStartServerCommand()
        } else {
            requestRunCommandPermission.launch(permission)
        }
    }

    private fun sendStartServerCommand() {
        try {
            val intent = Intent()
            intent.setClassName("com.termux", "com.termux.app.RunCommandService")
            intent.action = "com.termux.RUN_COMMAND"
            intent.putExtra("com.termux.RUN_COMMAND_PATH", startScriptPath)
            intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf<String>())
            intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", termuxWorkDir)
            intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")

            startForegroundService(intent)

            Toast.makeText(this, getString(R.string.starting_server), Toast.LENGTH_SHORT).show()

            // Serveru treba par sekundi da se digne (LiteLLM + 9Router + Odysseus),
            // pa pokušavamo ponovo učitati stranicu nakon kratke pauze.
            Handler(Looper.getMainLooper()).postDelayed({
                loadOdysseus()
            }, 6000)

        } catch (e: SecurityException) {
            Toast.makeText(this, getString(R.string.termux_not_found), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.termux_not_found), Toast.LENGTH_LONG).show()
        }
    }
}
