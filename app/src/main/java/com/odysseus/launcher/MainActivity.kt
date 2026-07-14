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

    // Termux (F-Droid / GitHub build) paket ID — isti za obje distribucije
    private val termuxPackage = "com.termux"
    private val termuxFdroidUrl = "https://f-droid.org/packages/com.termux/"

    // Putanje unutar Termux-a (home foldera)
    private val termuxPrefix = "/data/data/com.termux/files/usr"
    private val startScriptPath = "/data/data/com.termux/files/home/start_all.sh"
    private val termuxWorkDir = "/data/data/com.termux/files/home"
    private val nineRouterPath = "$termuxPrefix/bin/9router"

    private val requestRunCommandPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startEverything()
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

    private fun isTermuxInstalled(): Boolean {
        return try {
            packageManager.getPackageInfo(termuxPackage, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun onStartServerClicked() {
        if (!isTermuxInstalled()) {
            Toast.makeText(this, getString(R.string.termux_not_installed), Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(termuxFdroidUrl)))
            } catch (e: Exception) { /* nema browsera, samo ignoriši */ }
            return
        }

        val permission = "com.termux.permission.RUN_COMMAND"
        val alreadyGranted = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

        if (alreadyGranted) {
            startEverything()
        } else {
            requestRunCommandPermission.launch(permission)
        }
    }

    /**
     * Pokreće Termux (F-Droid build) potpuno u pozadini — bez otvaranja terminala —
     * tako što šalje RUN_COMMAND intente RunCommandService-u. Prvi poziv već sam po sebi
     * podiže Termux proces ako nije aktivan. Redoslijed:
     *   1) komanda "9router"
     *   2) skripta "./start_all.sh" (koja podiže LiteLLM + Odysseus, i preskače 9Router
     *      ako je već pokrenut korakom 1)
     */
    private fun startEverything() {
        Toast.makeText(this, getString(R.string.starting_server), Toast.LENGTH_SHORT).show()

        val step1Ok = sendRunCommand(
            path = nineRouterPath,
            args = arrayOf(),
            workDir = termuxWorkDir
        )

        if (!step1Ok) return

        // Malo sačekamo da se 9Router podigne, pa tek onda pokrenemo glavnu skriptu.
        Handler(Looper.getMainLooper()).postDelayed({
            sendRunCommand(
                path = startScriptPath,
                args = arrayOf(),
                workDir = termuxWorkDir
            )

            // Serveru treba par sekundi da se digne (LiteLLM + 9Router + Odysseus),
            // pa pokušavamo ponovo učitati stranicu nakon kratke pauze.
            Handler(Looper.getMainLooper()).postDelayed({
                loadOdysseus()
            }, 8000)
        }, 2000)
    }

    private fun sendRunCommand(path: String, args: Array<String>, workDir: String): Boolean {
        return try {
            val intent = Intent()
            intent.setClassName(termuxPackage, "com.termux.app.RunCommandService")
            intent.action = "com.termux.RUN_COMMAND"
            intent.putExtra("com.termux.RUN_COMMAND_PATH", path)
            intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args)
            intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", workDir)
            // true = izvrši u pozadini, bez prebacivanja na Termux terminal
            intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")

            startForegroundService(intent)
            true
        } catch (e: SecurityException) {
            Toast.makeText(this, getString(R.string.termux_not_found), Toast.LENGTH_LONG).show()
            false
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.termux_not_found), Toast.LENGTH_LONG).show()
            false
        }
    }
}
