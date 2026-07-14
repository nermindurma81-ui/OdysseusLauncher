package com.odysseus.launcher

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var homeView: View
    private lateinit var homeSubtitle: TextView
    private lateinit var loadingView: View
    private lateinit var loadingLogo: ImageView
    private lateinit var loadingStatusText: TextView
    private lateinit var btnStart9Router: Button
    private lateinit var btnStartServers: Button

    private var compassAnimator: ObjectAnimator? = null
    private val phaseHandler = Handler(Looper.getMainLooper())
    private var pendingAction: (() -> Unit)? = null
    private var startupJob: Job? = null

    companion object {
        private const val STARTUP_TIMEOUT_MS = 45_000L
        private const val STARTUP_POLL_INTERVAL_MS = 750L
        private const val HTTP_CONNECT_TIMEOUT_MS = 750
        private const val HTTP_READ_TIMEOUT_MS = 750
    }

    private val serverUrl = "http://localhost:7000"

    // Termux (F-Droid / GitHub build) paket ID — isti za obje distribucije
    private val termuxPackage = "com.termux"
    private val termuxFdroidUrl = "https://f-droid.org/packages/com.termux/"

    // Putanje unutar Termux-a (home foldera)
    private val termuxPrefix = "/data/data/com.termux/files/usr"
    private val startScriptPath = "/data/data/com.termux/files/home/start_all.sh"
    private val stopScriptPath = "/data/data/com.termux/files/home/stop_all.sh"
    private val termuxWorkDir = "/data/data/com.termux/files/home"
    private val nineRouterPath = "$termuxPrefix/bin/9router"
    private val bashPath = "$termuxPrefix/bin/bash"

    private val requestRunCommandPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                pendingAction?.invoke()
            } else {
                Toast.makeText(this, getString(R.string.run_command_permission_needed), Toast.LENGTH_LONG).show()
            }
            pendingAction = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        homeView = findViewById(R.id.homeView)
        homeSubtitle = findViewById(R.id.homeSubtitle)
        loadingView = findViewById(R.id.loadingView)
        loadingLogo = findViewById(R.id.loadingLogo)
        loadingStatusText = findViewById(R.id.loadingStatusText)

        btnStart9Router = findViewById(R.id.btnStart9Router)
        btnStart9Router.setOnClickListener { onStart9RouterClicked() }

        btnStartServers = findViewById(R.id.btnStartServers)
        btnStartServers.setOnClickListener { onStartServersClicked() }

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.mediaPlaybackRequiresUserGesture = false

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                showLoading(getString(R.string.phase_checking))
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                cancelStartupProgress()
                stopPhaseSequence()
                hideLoading()
                homeView.visibility = View.GONE
                webView.visibility = View.VISIBLE
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    stopPhaseSequence()
                    hideLoading()
                    webView.visibility = View.GONE
                    homeSubtitle.text = getString(R.string.home_subtitle_error)
                    homeView.visibility = View.VISIBLE
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.visibility == View.VISIBLE && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // Tihi pokušaj konekcije u pozadini — ako server već radi (npr. nakon rotacije
        // ekrana ili background/foreground), preskačemo home ekran direktno na WebView.
        loadOdysseus()
    }

    override fun onDestroy() {
        // Kad korisnik zatvori aplikaciju (back / ukloni iz recent apps),
        // pošalji Termux-u komandu da ugasi sve servere (LiteLLM, 9Router, Odysseus).
        if (isFinishing && isTermuxInstalled()) {
            sendRunCommand(
                path = stopScriptPath,
                args = arrayOf(),
                workDir = termuxWorkDir
            )
        }
        cancelStartupProgress()
        stopPhaseSequence()
        phaseHandler.removeCallbacksAndMessages(null)
        compassAnimator?.cancel()
        super.onDestroy()
    }

    private fun loadOdysseus() {
        webView.loadUrl(serverUrl)
    }

    private fun isActivityAlive(): Boolean = !isFinishing && !isDestroyed

    private fun cancelStartupProgress() {
        startupJob?.cancel()
        startupJob = null
    }

    private fun isTermuxInstalled(): Boolean {
        return try {
            packageManager.getPackageInfo(termuxPackage, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    // ---------------------------------------------------------------------
    // UI stanja: home / loading / webview
    // ---------------------------------------------------------------------

    private fun showLoading(initialPhase: String) {
        webView.visibility = View.GONE
        homeView.visibility = View.GONE
        loadingView.visibility = View.VISIBLE
        loadingStatusText.text = initialPhase
        startCompassAnimation()
    }

    private fun hideLoading() {
        loadingView.visibility = View.GONE
        compassAnimator?.cancel()
    }

    private fun startCompassAnimation() {
        if (compassAnimator?.isRunning == true) return
        compassAnimator = ObjectAnimator.ofFloat(loadingLogo, View.ROTATION, 0f, 360f).apply {
            duration = 2400
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    /** Ciklično mijenja tekst statusa dok čekamo da se serveri podignu. */
    private val phaseRunnable = object : Runnable {
        var step = 0
        override fun run() {
            val phases = listOf(
                getString(R.string.phase_9router),
                getString(R.string.phase_litellm),
                getString(R.string.phase_odysseus)
            )
            loadingStatusText.text = phases[step % phases.size]
            step++
            phaseHandler.postDelayed(this, 2500)
        }
    }

    private fun startPhaseSequence() {
        phaseRunnable.step = 0
        phaseHandler.removeCallbacks(phaseRunnable)
        phaseHandler.post(phaseRunnable)
    }

    private fun stopPhaseSequence() {
        phaseHandler.removeCallbacks(phaseRunnable)
    }

    // ---------------------------------------------------------------------
    // Dugme 1: pokreni SAMO 9Router
    // ---------------------------------------------------------------------

    private fun onStart9RouterClicked() {
        if (!ensureTermuxReady { onStart9RouterClicked() }) return

        cancelStartupProgress()
        phaseHandler.removeCallbacksAndMessages(null)
        Toast.makeText(this, getString(R.string.toast_9router_starting), Toast.LENGTH_SHORT).show()
        showLoading(getString(R.string.phase_9router))
        startPhaseSequence()

        // pkill pa ponovo pokreni 9router (rješava slučaj kad je proces "obješen").
        val restartCmd = "pkill -9 -f 9router; sleep 1; $nineRouterPath --host 127.0.0.1 --tray --no-browser --skip-update"
        val ok = sendRunCommand(
            path = bashPath,
            args = arrayOf("-c", restartCmd),
            workDir = termuxWorkDir
        )
        if (!ok) {
            stopPhaseSequence()
            hideLoading()
            homeView.visibility = View.VISIBLE
            return
        }

        phaseHandler.postDelayed({
            if (!isActivityAlive()) return@postDelayed
            stopPhaseSequence()
            hideLoading()
            Toast.makeText(this, getString(R.string.toast_9router_started), Toast.LENGTH_LONG).show()
            homeView.visibility = View.VISIBLE
        }, 4000)
    }

    // ---------------------------------------------------------------------
    // Dugme 2: pokreni sve servere (9Router + LiteLLM + Odysseus)
    // ---------------------------------------------------------------------

    private fun onStartServersClicked() {
        if (!ensureTermuxReady { onStartServersClicked() }) return
        startEverything()
    }

    /**
     * Pokreće Termux (F-Droid build) potpuno u pozadini — bez otvaranja terminala —
     * tako što šalje RUN_COMMAND intente RunCommandService-u. Redoslijed:
     *   1) komanda "9router"
     *   2) skripta "./start_all.sh" (koja podiže LiteLLM + Odysseus, i preskače 9Router
     *      ako je već pokrenut korakom 1)
     */
    private fun startEverything() {
        cancelStartupProgress()
        phaseHandler.removeCallbacksAndMessages(null)
        showLoading(getString(R.string.phase_9router))
        startPhaseSequence()

        val step1Ok = sendRunCommand(
            path = nineRouterPath,
            args = arrayOf(),
            workDir = termuxWorkDir
        )

        if (!step1Ok) {
            stopPhaseSequence()
            hideLoading()
            homeSubtitle.text = getString(R.string.home_subtitle_start_failed)
            homeView.visibility = View.VISIBLE
            return
        }

        // Malo sačekamo da se 9Router podigne, pa tek onda pokrenemo glavnu skriptu.
        phaseHandler.postDelayed({
            if (!isActivityAlive()) return@postDelayed
            val step2Ok = sendRunCommand(
                path = startScriptPath,
                args = arrayOf(),
                workDir = termuxWorkDir
            )

            if (!step2Ok) {
                stopPhaseSequence()
                hideLoading()
                homeSubtitle.text = getString(R.string.home_subtitle_start_failed)
                homeView.visibility = View.VISIBLE
                return@postDelayed
            }

            beginStartupProgress()
        }, 2000)
    }

    private fun beginStartupProgress() {
        cancelStartupProgress()
        startupJob = lifecycleScope.launch {
            val isReady = withTimeoutOrNull(STARTUP_TIMEOUT_MS) {
                while (isActive) {
                    if (isOdysseusHealthy()) return@withTimeoutOrNull true
                    delay(STARTUP_POLL_INTERVAL_MS)
                }
                false
            } == true

            startupJob = null
            if (!isActivityAlive()) return@launch
            stopPhaseSequence()
            if (isReady) {
                loadOdysseus()
            } else {
                hideLoading()
                homeSubtitle.text = getString(R.string.home_subtitle_start_timeout)
                homeView.visibility = View.VISIBLE
            }
        }
    }

    private suspend fun isOdysseusHealthy(): Boolean = withContext(Dispatchers.IO) {
        try {
            val connection = (URL(serverUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = HTTP_CONNECT_TIMEOUT_MS
                readTimeout = HTTP_READ_TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = false
            }
            try {
                connection.responseCode in 200..399
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            false
        }
    }

    // ---------------------------------------------------------------------
    // Termux / dozvole
    // ---------------------------------------------------------------------

    /** Vraća true ako je Termux spreman (instaliran + dozvola odobrena). Inače pokreće flow za dobijanje dozvole i vraća false. */
    private fun ensureTermuxReady(retryAction: () -> Unit): Boolean {
        if (!isTermuxInstalled()) {
            Toast.makeText(this, getString(R.string.termux_not_installed), Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(termuxFdroidUrl)))
            } catch (e: Exception) { /* nema browsera, samo ignoriši */ }
            return false
        }

        val permission = "com.termux.permission.RUN_COMMAND"
        val alreadyGranted = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

        if (!alreadyGranted) {
            pendingAction = retryAction
            requestRunCommandPermission.launch(permission)
            return false
        }

        return true
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
