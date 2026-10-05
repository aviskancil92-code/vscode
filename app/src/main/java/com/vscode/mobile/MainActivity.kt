package com.vscode.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.Menu
import android.view.Gravity
import android.view.KeyEvent
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.materialswitch.MaterialSwitch
import com.vscode.mobile.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private enum class Mode { Setup, SetupFailed, Installing, Loading, Web }

    private lateinit var binding: ActivityMainBinding
    private var webView: WebView? = null
    private var mode = Mode.Setup
    private var installJob: Job? = null
    private var loadedOnce = false
    private var lastServerState: ServerState = ServerState.Idle

    // ---------------------------------------------------------- permission & picker

    private val requestNotifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Lanjutkan instalasi apa pun hasilnya (notifikasi hanya untuk status).
            checkDiskThenInstall()
        }

    private val pickRootfs =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) confirmManualInstall(rootfs = uri, codeServer = null)
        }

    private val pickCodeServer =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) confirmManualInstall(rootfs = null, codeServer = uri)
        }

    private val fileChooser =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            filePathCallback?.onReceiveValue(uri?.let { arrayOf(it) })
            filePathCallback = null
        }

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // Header (ActionBar) disembunyikan saat mode Web; ketuk 3 jari untuk menampilkan.
    private var headerVisible = false
    private var lastThreeFingerAt = 0L
    private var hintShown = false

    // Popup in-app (login OAuth GitHub/Google, window.open, link eksternal).
    private var popupRoot: LinearLayout? = null
    private var popupWeb: WebView? = null
    private var popupVisitedExternal = false

    // Tombol & pintasan keyboard.
    private lateinit var keyPad: KeyPad
    private var shortcutPanel: View? = null

    private val storagePerms = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    private val requestStorage =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasStoragePermission()) {
                afterStorageGranted()
            } else {
                Toast.makeText(this, R.string.storage_denied, Toast.LENGTH_LONG).show()
            }
        }

    private val allFilesAccess =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (storageReadable()) afterStorageGranted()
        }

    // ------------------------------------------------------------------ onCreate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        keyPad = KeyPad(this, { popupWeb ?: webView }, { toggleShortcutPanel() }, { toggleKeyBar() })
        keyPad.volMode = getSharedPreferences("ui", MODE_PRIVATE).getInt("volmode", 0)
        keyPad.buildBar(binding.keysRow)
        binding.keysBar.visibility =
            if (getSharedPreferences("ui", MODE_PRIVATE).getBoolean("keybar", false)) View.VISIBLE else View.GONE

        binding.textStorage.text = getString(
            R.string.storage_free_fmt,
            fmtSize(StatFs(filesDir.path).availableBytes)
        )
        binding.textInstallError.movementMethod = ScrollingMovementMethod()

        binding.buttonInstall.setOnClickListener { onInstallClicked() }
        binding.buttonRetry.setOnClickListener { onInstallClicked() }
        binding.buttonCancel.setOnClickListener { installJob?.cancel() }
        binding.buttonLoadingAction.setOnClickListener { onLoadingAction() }

        applyKeepScreenOn(StateStore.read(this).keepScreenOn)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (shortcutPanel != null) { hideShortcutPanel(); return }
                val pw = popupWeb
                if (pw != null) {
                    if (pw.canGoBack()) pw.goBack() else closePopup()
                    return
                }
                val wv = webView
                if (mode == Mode.Web && wv != null && wv.canGoBack()) wv.goBack()
                else moveTaskToBack(false)
            }
        })

        observeServerState()
        initUi()
    }

    private fun initUi() {
        if (LinuxRuntime.isInstalled(this)) {
            setMode(Mode.Loading)
            val st = LinuxService.state.value
            if (st is ServerState.Idle || st is ServerState.Stopped) LinuxService.start(this)
        } else {
            setMode(Mode.Setup)
        }
    }

    private fun setMode(m: Mode) {
        mode = m
        render()
    }

    private fun render() {
        binding.setupContainer.visibility =
            if (mode == Mode.Setup || mode == Mode.SetupFailed) View.VISIBLE else View.GONE
        binding.installContainer.visibility =
            if (mode == Mode.Installing) View.VISIBLE else View.GONE
        binding.loadingContainer.visibility =
            if (mode == Mode.Loading) View.VISIBLE else View.GONE
        binding.webArea.visibility =
            if (mode == Mode.Web) View.VISIBLE else View.GONE
        binding.buttonRetry.visibility =
            if (mode == Mode.SetupFailed) View.VISIBLE else View.GONE
        binding.textInstallError.visibility =
            if (mode == Mode.SetupFailed && binding.textInstallError.text.isNotBlank()) View.VISIBLE else View.GONE
        applyChrome()
        if (mode == Mode.Web && !hintShown) {
            hintShown = true
            Toast.makeText(this, R.string.hint_three_finger, Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------- layar penuh & header

    /** Mode Web: tanpa ActionBar & status/nav bar. Mode lain: tampilan normal. */
    private fun applyChrome() {
        val immersive = mode == Mode.Web && !headerVisible
        val ctrl = WindowCompat.getInsetsController(window, window.decorView)
        if (immersive) {
            supportActionBar?.hide()
            ctrl.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            supportActionBar?.show()
            if (mode == Mode.Web) ctrl.hide(WindowInsetsCompat.Type.systemBars())
            else ctrl.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun toggleHeader() {
        val now = System.currentTimeMillis()
        if (now - lastThreeFingerAt < 700) return // debounce
        lastThreeFingerAt = now
        headerVisible = !headerVisible
        applyChrome()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyChrome() // bar sistem bisa muncul lagi setelah dialog/keyboard
    }

    // -------------------------------------------------------------- server state

    private fun observeServerState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                LinuxService.state.collect { st -> onServerState(st) }
            }
        }
    }

    private fun onServerState(st: ServerState) {
        if (st is ServerState.Running) {
            enterWebIfNeeded()
        } else if (LinuxRuntime.isInstalled(this) && mode != Mode.Installing) {
            if (mode == Mode.Web || mode == Mode.Loading) {
                setMode(Mode.Loading)
                updateLoadingText(st)
            }
        }
        lastServerState = st
    }

    private fun enterWebIfNeeded() {
        if (mode != Mode.Web) setMode(Mode.Web)
        ensureStorageAccess(force = false)
        val wv = webView ?: createWebView()
        when {
            !loadedOnce -> {
                loadedOnce = true
                wv.loadUrl(LinuxRuntime.SERVER_URL)
            }
            wv.url == null -> wv.loadUrl(LinuxRuntime.SERVER_URL)
            lastServerState !is ServerState.Running -> wv.reload()
        }
    }

    private fun updateLoadingText(st: ServerState) {
        when (st) {
            is ServerState.Starting -> {
                binding.textLoadingStatus.setText(R.string.status_starting)
                binding.buttonLoadingAction.visibility = View.GONE
            }
            is ServerState.Restarting -> {
                binding.textLoadingStatus.text =
                    getString(R.string.status_restarting_fmt, st.attempt)
                binding.buttonLoadingAction.visibility = View.GONE
            }
            is ServerState.Error -> {
                binding.textLoadingStatus.text = getString(R.string.status_error_fmt, st.message)
                binding.buttonLoadingAction.setText(R.string.action_retry_start)
                binding.buttonLoadingAction.visibility = View.VISIBLE
            }
            is ServerState.Stopped -> {
                binding.textLoadingStatus.setText(R.string.status_stopped)
                binding.buttonLoadingAction.setText(R.string.action_start)
                binding.buttonLoadingAction.visibility = View.VISIBLE
            }
            else -> {}
        }
    }

    private fun onLoadingAction() {
        when (LinuxService.state.value) {
            is ServerState.Error, is ServerState.Restarting -> LinuxService.requestRestart(this)
            is ServerState.Stopped -> LinuxService.start(this)
            else -> {}
        }
    }

    // ------------------------------------------------------------------ install

    private fun onInstallClicked() {
        if (installJob?.isActive == true) return
        if (Build.VERSION.SDK_INT >= 33) {
            val perm = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                requestNotifPermission.launch(perm)
                return
            }
        }
        checkDiskThenInstall()
    }

    private fun checkDiskThenInstall() {
        val avail = StatFs(filesDir.path).availableBytes
        if (avail < 1_200_000_000L) {
            AlertDialog.Builder(this)
                .setTitle(R.string.low_disk_title)
                .setMessage(getString(R.string.low_disk_msg_fmt, fmtSize(avail)))
                .setPositiveButton(R.string.continue_anyway) { _, _ -> beginInstall(null, null) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            beginInstall(null, null)
        }
    }

    private fun confirmManualInstall(rootfs: Uri?, codeServer: Uri?) {
        AlertDialog.Builder(this)
            .setTitle(R.string.manual_install_title)
            .setMessage(R.string.manual_install_msg)
            .setPositiveButton(R.string.install_now) { _, _ -> beginInstall(rootfs, codeServer) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun beginInstall(rootfs: Uri?, codeServer: Uri?) {
        if (installJob?.isActive == true) return
        setMode(Mode.Installing)
        showInstallProgress(Installer.Progress(getString(R.string.preparing_install)))

        installJob = lifecycleScope.launch {
            try {
                val result = Installer(this@MainActivity).install(
                    onProgress = { p ->
                        withContext(Dispatchers.Main) { showInstallProgress(p) }
                    },
                    manualRootfs = rootfs,
                    manualCodeServer = codeServer
                )
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, R.string.install_done, Toast.LENGTH_LONG).show()
                    applyKeepScreenOn(result.keepScreenOn)
                    setMode(Mode.Loading)
                    LinuxService.start(this@MainActivity)
                }
            } catch (e: CancellationException) {
                withContext(Dispatchers.Main) { setMode(Mode.Setup) }
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.textInstallError.text = getString(
                        R.string.install_failed_fmt,
                        e.message ?: e.javaClass.simpleName
                    )
                    setMode(Mode.SetupFailed)
                }
            }
        }
    }

    private fun showInstallProgress(p: Installer.Progress) {
        binding.textInstallStep.text = p.step
        binding.textInstallDetail.text = when {
            p.total > 0 -> "${fmtSize(p.read)} / ${fmtSize(p.total)}  ${p.detail}".trim()
            p.read > 0 -> fmtSize(p.read)
            else -> p.detail
        }
        binding.textInstallSpeed.text = if (p.speedBps > 0) "${fmtSize(p.speedBps)}/dtk" else ""
        if (p.total > 0) {
            binding.progressInstall.isIndeterminate = false
            binding.progressInstall.max = 100
            binding.progressInstall.setProgressCompat(
                (p.read * 100 / p.total).toInt(), true
            )
        } else {
            binding.progressInstall.isIndeterminate = true
        }
    }

    // ------------------------------------------------------------------- WebView

    private fun createWebView(): WebView {
        destroyWebView()
        val wv = CodeWebView(this).also { it.keyPad = keyPad }
        wv.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        wv.setBackgroundColor(0xFF0F1115.toInt())
        configureWebView(wv)
        wv.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN && ev.pointerCount == 3) toggleHeader()
            false // tetap teruskan sentuhan ke WebView
        }
        binding.webContainer.addView(wv, 0)
        webView = wv
        return wv
    }

    private fun destroyWebView() {
        closePopup()
        val wv = webView ?: return
        runCatching { (wv.parent as? ViewGroup)?.removeView(wv) }
        runCatching { wv.destroy() }
        webView = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(wv: WebView) {
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.textZoom = 100
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        // Login OAuth (GitHub/Google dsb.): butuh window.open/popup + cookie pihak ketiga.
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = true
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val u = request.url
                val scheme = u.scheme ?: ""
                return when {
                    scheme != "http" && scheme != "https" -> { handleExternalScheme(u); true }
                    isLocalHost(u.host) -> false
                    else -> { openInPopup(u.toString()); true } // tetap di dalam aplikasi
                }
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail
            ): Boolean {
                Log.w("MainActivity", "proses render WebView mati — membangun ulang")
                runOnUiThread {
                    loadedOnce = false
                    createWebView()
                    if (LinuxService.state.value is ServerState.Running) enterWebIfNeeded()
                }
                return true
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message
            ): Boolean {
                val popup = createPopup()
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) { window.post { closePopup() } }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                val accept = params.acceptTypes.firstOrNull { it.isNotBlank() } ?: "*/*"
                runCatching { fileChooser.launch(accept) }
                    .onFailure {
                        filePathCallback?.onReceiveValue(null)
                        filePathCallback = null
                    }
                return true
            }
        }

        wv.setDownloadListener { url, _, contentDisposition, mimetype, _ ->
            handleDownload(url, contentDisposition, mimetype)
        }
    }

    /**
     * Unduh berkas dari code-server. Sengaja TIDAK memakai DownloadManager
     * sistem karena layanan itu berjalan di proses lain yang tidak bisa
     * mengakses 127.0.0.1 milik aplikasi ini.
     */
    private fun handleDownload(url: String, contentDisposition: String?, mimetype: String?) {
        val name = URLUtil.guessFileName(url, contentDisposition, mimetype)
        Toast.makeText(this, getString(R.string.downloading_fmt, name), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dir = downloadDir()
                dir.mkdirs()
                val f = File(dir, name)
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 60_000
                try {
                    if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
                    FileOutputStream(f).use { out ->
                        conn.inputStream.use { it.copyTo(out) }
                    }
                } finally {
                    conn.disconnect()
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.saved_fmt, f.absolutePath),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.download_failed_fmt, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }


    // ------------------------------------------------------ pintasan keyboard

    private fun toggleShortcutPanel() {
        if (shortcutPanel != null) { hideShortcutPanel(); return }
        val panel = keyPad.buildPanel { hideShortcutPanel() }
        val h = (resources.displayMetrics.heightPixels * 0.45f).toInt()
        binding.webContainer.addView(
            panel,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h, Gravity.BOTTOM)
        )
        shortcutPanel = panel
    }

    private fun hideShortcutPanel() {
        val p = shortcutPanel ?: return
        shortcutPanel = null
        runCatching { (p.parent as? ViewGroup)?.removeView(p) }
    }

    private fun showVolumeKeysDialog() {
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        val items = arrayOf(
            getString(R.string.volkeys_opt0), getString(R.string.volkeys_opt1), getString(R.string.volkeys_opt2)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.volkeys_title)
            .setSingleChoiceItems(items, prefs.getInt("volmode", 0)) { d, which ->
                prefs.edit().putInt("volmode", which).apply()
                keyPad.volMode = which
                keyPad.refreshMods()
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val c = event.keyCode
        if ((c == KeyEvent.KEYCODE_VOLUME_DOWN || c == KeyEvent.KEYCODE_VOLUME_UP) &&
            mode == Mode.Web && ::keyPad.isInitialized &&
            keyPad.onVolume(c, event.action == KeyEvent.ACTION_DOWN, event.repeatCount)
        ) return true
        return super.dispatchKeyEvent(event)
    }

    private fun toggleKeyBar() {
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        val on = !prefs.getBoolean("keybar", false)
        prefs.edit().putBoolean("keybar", on).apply()
        binding.keysBar.visibility = if (on) View.VISIBLE else View.GONE
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_keybar)?.isChecked =
            getSharedPreferences("ui", MODE_PRIVATE).getBoolean("keybar", false)
        return super.onPrepareOptionsMenu(menu)
    }

    // -------------------------------------------------------- popup / login OAuth

    private fun isLocalHost(host: String?) = host == "127.0.0.1" || host == "localhost"

    /** UA mirip Chrome: Google menolak login pada UA WebView ("; wv" / "Version/4.0"). */
    private fun chromeUserAgent(): String =
        WebSettings.getDefaultUserAgent(this)
            .replace("; wv)", ")")
            .replace(Regex("Version/\\d+\\.\\d+\\s"), "")

    private fun handleExternalScheme(u: Uri) {
        when (u.scheme) {
            "intent" -> runCatching {
                val i = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = null
                    selector = null
                }
                startActivity(i)
            }
            "vscode", "vscode-insiders", "about", "javascript", "data", "blob" -> { /* tidak didukung */ }
            else -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, u)) }
        }
    }

    private fun openInPopup(url: String) {
        createPopup().loadUrl(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createPopup(): WebView {
        closePopup()
        popupVisitedExternal = false
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0F1115.toInt())
            isClickable = true
            elevation = 16 * dp
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val title = TextView(this).apply {
            setTextColor(0xFFE6E6E6.toInt())
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = "…"
        }
        val pw = WebView(this)
        val btnBrowser = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            setText(R.string.popup_browser)
            setOnClickListener {
                val u = pw.url
                if (!u.isNullOrBlank()) runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                }
            }
        }
        val btnClose = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            setText(R.string.popup_close)
            setOnClickListener { closePopup() }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF1B1E26.toInt())
            setPadding((12 * dp).toInt(), 0, (4 * dp).toInt(), 0)
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnBrowser)
            addView(btnClose)
        }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (44 * dp).toInt()))

        pw.setBackgroundColor(0xFF0F1115.toInt())
        pw.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            userAgentString = chromeUserAgent()
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(pw, true)
        pw.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val sch = request.url.scheme ?: ""
                if (sch == "http" || sch == "https") return false
                handleExternalScheme(request.url)
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                title.text = url ?: ""
                val host = runCatching { Uri.parse(url).host }.getOrNull()
                if (host == null) return
                if (!isLocalHost(host)) {
                    popupVisitedExternal = true
                } else if (popupVisitedExternal) {
                    // Callback OAuth sudah mendarat di code-server lokal -> tutup popup.
                    view.postDelayed({ if (popupWeb === view) closePopup() }, 1500)
                }
            }
        }
        pw.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message
            ): Boolean {
                val next = createPopup()
                (resultMsg.obj as WebView.WebViewTransport).webView = next
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) { window.post { closePopup() } }
        }
        pw.setDownloadListener { url, _, cd, mime, _ -> handleDownload(url, cd, mime) }
        root.addView(pw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        binding.webContainer.addView(root)
        popupRoot = root
        popupWeb = pw
        return pw
    }

    private fun closePopup() {
        val root = popupRoot
        val pw = popupWeb
        popupRoot = null
        popupWeb = null
        runCatching { (root?.parent as? ViewGroup)?.removeView(root) }
        runCatching { pw?.stopLoading(); pw?.destroy() }
        // Beri tahu halaman utama bahwa login mungkin selesai (cookie/localStorage dibagi).
        runCatching { CookieManager.getInstance().flush() }
    }

    // ---------------------------------------------------------- akses penyimpanan

    private fun hasStoragePermission() = storagePerms.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Benar-benar bisa membaca /sdcard/Download (cek nyata, bukan sekadar izin). */
    private fun storageReadable(): Boolean = runCatching {
        File("/sdcard/Download").list() != null
    }.getOrDefault(false)

    private fun afterStorageGranted() {
        Toast.makeText(this, R.string.storage_granted, Toast.LENGTH_LONG).show()
        // Proses proot mewarisi grup penyimpanan saat dijalankan -> mulai ulang server.
        LinuxService.requestRestart(this)
    }

    private fun ensureStorageAccess(force: Boolean) {
        if (storageReadable() && hasStoragePermission()) {
            if (force) Toast.makeText(this, R.string.storage_already, Toast.LENGTH_SHORT).show()
            return
        }
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (!force && prefs.getBoolean("storage_asked", false)) return
        prefs.edit().putBoolean("storage_asked", true).apply()
        AlertDialog.Builder(this)
            .setTitle(R.string.storage_title)
            .setMessage(R.string.storage_rationale)
            .setPositiveButton(R.string.storage_allow) { _, _ -> requestStorageNow() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun requestStorageNow() {
        if (!hasStoragePermission()) {
            requestStorage.launch(storagePerms)
            return
        }
        // Izin sudah ada tetapi /sdcard masih tertutup (Android 11+): butuh "Akses semua berkas".
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            val i = Intent(
                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            )
            runCatching { allFilesAccess.launch(i) }.onFailure {
                runCatching {
                    allFilesAccess.launch(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        }
    }

    private fun downloadDir(): File {
        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "VSCodeMobile"
        )
        return if (publicDir.canWrite()) publicDir
        else File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir, "unduhan")
    }

    // --------------------------------------------------------------------- menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_restart -> {
                LinuxService.requestRestart(this)
                Toast.makeText(this, R.string.restarting_server, Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_stop -> {
                LinuxService.requestStop(this)
                true
            }
            R.id.action_shortcuts -> {
                toggleShortcutPanel()
                true
            }
            R.id.action_volkeys -> {
                showVolumeKeysDialog()
                true
            }
            R.id.action_keybar -> {
                toggleKeyBar()
                true
            }
            R.id.action_storage -> {
                ensureStorageAccess(force = true)
                true
            }
            R.id.action_settings -> {
                showSettings()
                true
            }
            R.id.action_logs -> {
                showLogs()
                true
            }
            R.id.action_about -> {
                showAbout()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ------------------------------------------------------------------ dialogs

    private fun showSettings() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val switchAuth = view.findViewById<MaterialSwitch>(R.id.switchAuth)
        val switchKeep = view.findViewById<MaterialSwitch>(R.id.switchKeepOn)
        val textPassword = view.findViewById<TextView>(R.id.textPassword)
        val btnRootfs = view.findViewById<Button>(R.id.buttonImportRootfs)
        val btnCs = view.findViewById<Button>(R.id.buttonImportCodeServer)
        val btnWipe = view.findViewById<Button>(R.id.buttonWipe)
        val textVersions = view.findViewById<TextView>(R.id.textVersions)

        var state = StateStore.read(this)
        switchAuth.isChecked = state.authEnabled
        switchKeep.isChecked = state.keepScreenOn

        fun refreshPassword() {
            if (state.authEnabled) {
                textPassword.text = getString(R.string.password_fmt, state.password)
                textPassword.visibility = View.VISIBLE
            } else {
                textPassword.visibility = View.GONE
            }
        }
        refreshPassword()

        textVersions.text = getString(
            R.string.versions_fmt,
            state.codeServerVersion.ifEmpty { LinuxRuntime.codeServerVersionFor() },
            state.rootfsSource.ifEmpty { "debian/${Pins.DEBIAN_RELEASE}" },
            Pins.PROOT_VERSION
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .show()

        switchAuth.setOnCheckedChangeListener { _, checked ->
            state = if (checked) {
                state.copy(authEnabled = true, password = state.password.ifEmpty { StateStore.newPassword() })
            } else {
                state.copy(authEnabled = false)
            }
            StateStore.write(this, state)
            if (LinuxRuntime.isInstalled(this)) {
                LinuxRuntime.writeServerConfig(
                    LinuxRuntime.rootfsDir(this), state.authEnabled, state.password
                )
                Toast.makeText(this, R.string.applied_after_restart, Toast.LENGTH_SHORT).show()
                LinuxService.requestRestart(this)
            }
            refreshPassword()
        }

        switchKeep.setOnCheckedChangeListener { _, checked ->
            state = state.copy(keepScreenOn = checked)
            StateStore.write(this, state)
            applyKeepScreenOn(checked)
        }

        btnRootfs.setOnClickListener {
            dialog.dismiss()
            pickRootfs.launch("*/*")
        }
        btnCs.setOnClickListener {
            dialog.dismiss()
            pickCodeServer.launch("*/*")
        }
        btnWipe.setOnClickListener {
            dialog.dismiss()
            confirmWipe()
        }
    }

    private fun confirmWipe() {
        AlertDialog.Builder(this)
            .setTitle(R.string.wipe_title)
            .setMessage(R.string.wipe_msg)
            .setPositiveButton(R.string.wipe_yes) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    LinuxService.requestStop(this@MainActivity)
                    delay(800)
                    StateStore.wipe(this@MainActivity)
                    withContext(Dispatchers.Main) { recreate() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showLogs() {
        val logs = LinuxService.recentLogs()
        val content = if (logs.isEmpty()) getString(R.string.no_logs)
        else logs.takeLast(200).joinToString("\n")
        val tv = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            text = content
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle(R.string.logs)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle(R.string.about)
            .setMessage(
                getString(
                    R.string.about_msg_fmt,
                    LinuxRuntime.codeServerVersionFor(),
                    Pins.DEBIAN_RELEASE,
                    Pins.PROOT_VERSION
                )
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // -------------------------------------------------------------------- util

    private fun applyKeepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun fmtSize(b: Long): String = when {
        b < 0 -> "—"
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
        b < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", b / 1024.0 / 1024.0)
        else -> String.format(Locale.US, "%.2f GB", b / 1024.0 / 1024.0 / 1024.0)
    }

    override fun onDestroy() {
        destroyWebView()
        super.onDestroy()
    }
}
