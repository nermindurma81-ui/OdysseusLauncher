package com.odysseus.launcher

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.io.IOException
import java.net.Socket
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: android.widget.ProgressBar
    private lateinit var errorView: View
    private lateinit var errorText: TextView
    private lateinit var startNineRouterButton: MaterialButton
    private lateinit var startServersButton: MaterialButton
    private lateinit var retryButton: MaterialButton
    private lateinit var debugLogButton: TextView
    private lateinit var debugLogFloatingButton: TextView

    private val serverUrl = "http://localhost:7000"
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    // Termux (F-Droid / GitHub build) paket ID — isti za obje distribucije
    private val termuxPackage = "com.termux"
    private val termuxFdroidUrl = "https://f-droid.org/packages/com.termux/"

    // Putanje unutar Termux-a (home foldera)
    private val startScriptPath = "/data/data/com.termux/files/home/start_all.sh"
    private val stopScriptPath = "/data/data/com.termux/files/home/stop_all.sh"
    private val termuxWorkDir = "/data/data/com.termux/files/home"
    private val termuxBashPath = "/data/data/com.termux/files/usr/bin/bash"

    // Šta uraditi nakon što korisnik odobri RUN_COMMAND dozvolu
    private var pendingAction: (() -> Unit)? = null

    // Sve poruke iz JS konzole Odysseus web app-a (uključujući neuhvaćene greške) —
    // ovo nam pomaže da vidimo ZAŠTO dugmad u web app-u ne reaguju u WebView-u.
    private val consoleLog = StringBuilder()

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
        progressBar = findViewById(R.id.progressBar)
        errorView = findViewById(R.id.errorView)
        errorText = findViewById(R.id.errorText)
        startNineRouterButton = findViewById(R.id.startNineRouterButton)
        startServersButton = findViewById(R.id.startServersButton)
        retryButton = findViewById(R.id.retryButton)
        debugLogButton = findViewById(R.id.debugLogButton)
        debugLogFloatingButton = findViewById(R.id.debugLogFloatingButton)

        retryButton.setOnClickListener { loadOdysseus() }
        startNineRouterButton.setOnClickListener { onStartNineRouterClicked() }
        startServersButton.setOnClickListener { onStartServersClicked() }
        debugLogButton.setOnClickListener { showDebugLogDialog() }
        debugLogFloatingButton.setOnClickListener { showDebugLogDialog() }

        // Omogući inspekciju preko chrome://inspect na računaru (opciono, ali korisno).
        WebView.setWebContentsDebuggingEnabled(true)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.mediaPlaybackRequiresUserGesture = false

        webView.webChromeClient = object : WebChromeClient() {
            // Hvata SVE poruke iz JS konzole Odysseus web app-a — uključujući
            // neuhvaćene JS greške (browseri ih automatski loguju kao console errore).
            // Ovo je ključno za dijagnostiku "dugmad ne reaguju" problema.
            override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                message ?: return false
                val line = "[${message.messageLevel()}] ${message.message()} " +
                        "(${message.sourceId()}:${message.lineNumber()})"
                Log.d("OdysseusJS", line)
                consoleLog.append(line).append("\n\n")

                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.debug_log_js_error_toast),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
                errorView.visibility = View.GONE
                webView.visibility = View.GONE
                debugLogFloatingButton.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                webView.visibility = View.VISIBLE
                // Ostavi debug dugme dostupno i dok gledaš samu Odysseus stranicu,
                // za slučaj da neko dugme unutra ne reaguje.
                debugLogFloatingButton.visibility = View.VISIBLE
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
                    debugLogFloatingButton.visibility = View.GONE
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
        super.onDestroy()
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

    /** Osigurava da je Termux instaliran i da imamo RUN_COMMAND dozvolu prije nego što izvršimo [action]. */
    private fun withTermuxPermission(action: () -> Unit) {
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
            action()
        } else {
            pendingAction = action
            requestRunCommandPermission.launch(permission)
        }
    }

    /** Dugme "🔀 Pokreni 9Router" — pokreće SAMO 9Router. */
    private fun onStartNineRouterClicked() {
        withTermuxPermission {
            Toast.makeText(this, getString(R.string.starting_nine_router), Toast.LENGTH_SHORT).show()
            val commandSent = sendRunCommand(
                path = termuxBashPath,
                args = arrayOf(
                    "-lc",
                    """
                    source ~/.bashrc 2>/dev/null || true
                    mkdir -p "${'$'}HOME/logs" "${'$'}HOME/.pids"
                    if curl -s -o /dev/null --max-time 1 http://127.0.0.1:20128; then
                      exit 0
                    fi
                    NINE_ROUTER_BIN="/data/data/com.termux/files/usr/bin/9router"
                    if [ ! -x "${'$'}NINE_ROUTER_BIN" ]; then
                      NINE_ROUTER_BIN="${'$'}(command -v 9router || true)"
                    fi
                    if [ -n "${'$'}NINE_ROUTER_BIN" ] && [ -x "${'$'}NINE_ROUTER_BIN" ]; then
                      # Termux nema desktop tray; --tray zna da sruši 9Router prije otvaranja porta.
                      nohup "${'$'}NINE_ROUTER_BIN" --host 127.0.0.1 --no-browser --skip-update > "${'$'}HOME/logs/9router.log" 2>&1 &
                      echo ${'$'}! > "${'$'}HOME/.pids/9router.pid"
                    else
                      echo "9router binary nije pronađen u /data/data/com.termux/files/usr/bin ili PATH" > "${'$'}HOME/logs/9router.log"
                      exit 127
                    fi
                    """.trimIndent()
                ),
                workDir = termuxWorkDir
            )
            if (!commandSent) return@withTermuxPermission
            beginStartupProgress(
                targets = listOf(ServiceTarget("9Router", 20128)),
                initialMessage = getString(R.string.startup_progress_nine_router),
                successMessage = getString(R.string.startup_progress_nine_router_ready),
                openWhenReady = false
            )
        }
    }

    /** Dugme "▶️ Pokreni servere" — pokreće start_all.sh (LiteLLM + Odysseus; preskače 9Router ako je već gore). */
    private fun onStartServersClicked() {
        withTermuxPermission {
            Toast.makeText(this, getString(R.string.starting_servers), Toast.LENGTH_SHORT).show()
            val commandSent = sendRunCommand(
                path = termuxBashPath,
                args = arrayOf(startScriptPath),
                workDir = termuxWorkDir
            )
            if (!commandSent) return@withTermuxPermission

            errorText.text = getString(R.string.status_waiting)
            beginStartupProgress(
                targets = listOf(
                    ServiceTarget("9Router", 20128),
                    ServiceTarget("LiteLLM", 4000),
                    ServiceTarget("Odysseus", 7000)
                ),
                initialMessage = getString(R.string.startup_progress_starting),
                successMessage = getString(R.string.startup_progress_ready),
                openWhenReady = true
            )
        }
    }

    private data class ServiceTarget(val name: String, val port: Int)

    private fun beginStartupProgress(
        targets: List<ServiceTarget>,
        initialMessage: String,
        successMessage: String,
        openWhenReady: Boolean
    ) {
        errorText.text = initialMessage
        startNineRouterButton.isEnabled = false
        startServersButton.isEnabled = false
        retryButton.isEnabled = false

        executor.execute {
            val ready = linkedSetOf<String>()
            val startedAt = System.currentTimeMillis()
            val timeoutMs = 45_000L
            val deadline = startedAt + timeoutMs

            while (System.currentTimeMillis() < deadline && ready.size < targets.size) {
                targets.forEach { target ->
                    if (target.name !in ready && isPortOpen(target.port)) {
                        ready.add(target.name)
                    }
                }

                val elapsed = System.currentTimeMillis() - startedAt
                val percent = maxOf(5, minOf(99, ((elapsed * 100) / timeoutMs).toInt()))
                val missing = targets.map { it.name }.filterNot { it in ready }
                handler.post {
                    errorText.text = if (missing.isEmpty()) {
                        successMessage
                    } else {
                        "${visualProgress(percent)} ${percent}%\n" +
                                getString(R.string.startup_progress_partial, missing.joinToString(", "))
                    }
                }

                if (ready.size < targets.size) {
                    Thread.sleep(1_000)
                }
            }

            val missing = targets.map { it.name }.filterNot { it in ready }
            handler.post {
                startNineRouterButton.isEnabled = true
                startServersButton.isEnabled = true
                retryButton.isEnabled = true
                if (missing.isEmpty()) {
                    errorText.text = "${visualProgress(100)} 100%\n$successMessage"
                    if (openWhenReady) loadOdysseus()
                } else {
                    errorText.text = getString(
                        R.string.startup_progress_failed,
                        missing.joinToString(", ")
                    )
                }
            }
        }
    }

    private fun visualProgress(percent: Int): String {
        val filled = percent / 10
        return "▰".repeat(filled) + "▱".repeat(10 - filled)
    }

    private fun isPortOpen(port: Int): Boolean {
        return try {
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 500
                socket.isConnected
            }
        } catch (e: IOException) {
            false
        }
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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            true
        } catch (e: SecurityException) {
            Toast.makeText(this, getString(R.string.termux_not_found), Toast.LENGTH_LONG).show()
            false
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.termux_not_found), Toast.LENGTH_LONG).show()
            false
        }
    }

    /** Prikazuje sve zabilježene JS konzol poruke iz Odysseus web app-a (za dijagnostiku "dugmad ne rade"). */
    private fun showDebugLogDialog() {
        val content = if (consoleLog.isEmpty()) getString(R.string.debug_log_empty) else consoleLog.toString()

        val textView = TextView(this).apply {
            text = content
            setTextIsSelectable(true)
            setPadding(32, 24, 32, 24)
            setTextColor(getColor(R.color.od_fg))
            textSize = 12f
        }
        val scroll = ScrollView(this).apply { addView(textView) }

        AlertDialog.Builder(this)
            .setTitle(R.string.debug_log_title)
            .setView(scroll)
            .setPositiveButton(R.string.debug_log_close, null)
            .setNegativeButton(R.string.debug_log_clear) { _, _ ->
                consoleLog.clear()
            }
            .show()
    }
}
