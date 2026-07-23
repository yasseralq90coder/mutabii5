package com.mutabii.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.content.Context
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast

class MainActivity : android.app.Activity() {

    private var web: WebView? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val REQ_FILE = 777
    private var lastFull = false

    companion object {
        @Volatile private var current: java.lang.ref.WeakReference<MainActivity>? = null

        /** تُستدعى من الجسر لتفعيل/إلغاء وضع ملء الشاشة (يخفي أشرطة النظام). */
        fun setFullscreenMode(full: Boolean) {
            val a = current?.get() ?: return
            a.runOnUiThread { a.applyImmersive(full) }
        }

        /**
         * يطلب صلاحية الميكروفون لتسجيل الأذكار.
         * WebView لا يفتح الميكروفون إطلاقاً قبل منح صلاحية النظام، حتى مع onPermissionRequest.
         */
        fun askMic() {
            val a = current?.get() ?: return
            a.runOnUiThread {
                try { a.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC) }
                catch (_: Exception) {}
            }
        }

        const val REQ_MIC = 103
        const val REQ_LOC = 104
        const val REQ_SOUND = 556

        /** يفتح منتقي ملف صوت لتنبيه الأذان أو الإيقاظ. */
        fun pickSound(target: String) {
            val a = current?.get() ?: return
            a.runOnUiThread {
                a.soundTarget = if (target == "wake") "wake" else "adhan"
                try {
                    a.startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "audio/*"
                            addFlags(
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        }, REQ_SOUND
                    )
                } catch (_: Exception) {
                    try { Toast.makeText(a, "تعذّر فتح منتقي الصوت", Toast.LENGTH_SHORT).show() }
                    catch (_: Exception) {}
                }
            }
        }
    }

    private var soundTarget = "adhan"
    private var pendingGeo: android.webkit.GeolocationPermissions.Callback? = null
    private var pendingGeoOrigin: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = java.lang.ref.WeakReference(this)
        // طبّق وضع ملء الشاشة المحفوظ فوراً (الافتراضي: مُفعّل) قبل رسم الواجهة
        try {
            val savedFull = getSharedPreferences("mtb_ui", Context.MODE_PRIVATE)
                .getBoolean("fullscreen", true)
            applyImmersive(savedFull)
        } catch (_: Exception) {}
        Notif.ensure(this)

        val container = FrameLayout(this)
        setContentView(container)

        // إعادة استخدام الـWebView المحفوظ (ليستمر الصوت بعد إغلاق الواجهة)
        val existing = WebHolder.web
        val w: WebView
        if (existing != null) {
            (existing.parent as? ViewGroup)?.removeView(existing)
            w = existing
        } else {
            w = WebView(applicationContext)
            configure(w)
            w.loadUrl("file:///android_asset/www/index.html")
            WebHolder.web = w
        }
        w.webChromeClient = chrome()
        container.addView(
            w,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        web = w

        requestRuntimePermissions()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(w: WebView) {
        val s: WebSettings = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        @Suppress("DEPRECATION")
        s.databaseEnabled = true
        s.allowFileAccess = true
        s.allowContentAccess = true
        s.mediaPlaybackRequiresUserGesture = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.javaScriptCanOpenWindowsAutomatically = true
        w.setBackgroundColor(0xFF1A1410.toInt())
        w.webViewClient = WebViewClient()
        w.addJavascriptInterface(AlarmBridge(applicationContext), "MTBNative")
    }

    /** يفعّل منتقي الملفات — بدونه لا يعمل زر استعادة JSON ولا استيراد CSV ولا اختيار الصوت. */
    private fun chrome(): WebChromeClient = object : WebChromeClient() {
        override fun onShowFileChooser(
            webView: WebView?,
            callback: ValueCallback<Array<Uri>>?,
            params: FileChooserParams?
        ): Boolean {
            try { filePathCallback?.onReceiveValue(null) } catch (_: Exception) {}
            filePathCallback = callback
            return try {
                var intent = params?.createIntent()
                if (intent == null) {
                    intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                    }
                }
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivityForResult(Intent.createChooser(intent, "اختر ملفاً"), REQ_FILE)
                true
            } catch (e: Exception) {
                // بديل عام إن فشل المنتقي المخصص
                try {
                    val alt = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                    }
                    startActivityForResult(Intent.createChooser(alt, "اختر ملفاً"), REQ_FILE)
                    true
                } catch (e2: Exception) {
                    filePathCallback = null
                    Toast.makeText(this@MainActivity, "تعذّر فتح منتقي الملفات", Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }

        override fun onPermissionRequest(request: PermissionRequest?) {
            try { request?.grant(request.resources) } catch (_: Exception) {}
        }

        /**
         * الموقع داخل WebView: لا يكفي إذن الصفحة — لا بد من إذن النظام أولاً.
         * بدون تجاوز هذه الدالة يرفض WebView الطلب صامتاً فتظهر رسالة «الإذن مرفوض».
         */
        override fun onGeolocationPermissionsShowPrompt(
            origin: String?, callback: android.webkit.GeolocationPermissions.Callback?
        ) {
            val granted = try {
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) { false }
            if (granted) {
                try { callback?.invoke(origin, true, false) } catch (_: Exception) {}
            } else {
                // اطلب إذن النظام، وارفض هذا الطلب — يعيد المستخدم الضغط بعد المنح
                pendingGeo = callback; pendingGeoOrigin = origin
                try {
                    requestPermissions(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ), REQ_LOC
                    )
                } catch (_: Exception) {
                    try { callback?.invoke(origin, false, false) } catch (_: Exception) {}
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_SOUND) {
            if (resultCode == RESULT_OK) {
                val uri = data?.data
                if (uri != null) {
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) {}
                    val key = if (soundTarget == "wake") Prefs.K_WAKE_URI else Prefs.K_ADHAN_URI
                    try { Prefs.sp(this).edit().putString(key, uri.toString()).apply() }
                    catch (_: Exception) {}
                    val nm = if (soundTarget == "wake") "الإيقاظ" else "الأذان"
                    try { Toast.makeText(this, "✅ ضُبط صوت $nm", Toast.LENGTH_SHORT).show() }
                    catch (_: Exception) {}
                }
            }
            try { web?.evaluateJavascript("window.__mtbAlarmsChanged&&window.__mtbAlarmsChanged();", null) }
            catch (_: Exception) {}
            return
        }
        if (requestCode == REQ_FILE) {
            val cb = filePathCallback
            filePathCallback = null
            if (cb == null) { super.onActivityResult(requestCode, resultCode, data); return }
            var results: Array<Uri>? = null
            if (resultCode == RESULT_OK && data != null) {
                val clip = data.clipData
                if (clip != null && clip.itemCount > 0) {
                    results = Array(clip.itemCount) { clip.getItemAt(it).uri }
                } else {
                    data.data?.let { results = arrayOf(it) }
                }
            }
            try { cb.onReceiveValue(results) } catch (_: Exception) {}
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            } catch (_: Exception) {}
        }
        // أندرويد ٩ وأقل: صلاحية الكتابة ليصل تصدير JSON إلى مجلد التنزيلات الحقيقي
        if (Build.VERSION.SDK_INT <= 28) {
            try {
                if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED
                ) requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 102)
            } catch (_: Exception) {}
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            try { web?.evaluateJavascript("window.__mtbMicResult && window.__mtbMicResult($ok);", null) }
            catch (_: Exception) {}
        }
        if (requestCode == REQ_LOC) {
            val ok = grantResults.isNotEmpty() &&
                grantResults.any { it == PackageManager.PERMISSION_GRANTED }
            try { pendingGeo?.invoke(pendingGeoOrigin, ok, false) } catch (_: Exception) {}
            pendingGeo = null; pendingGeoOrigin = null
            try {
                web?.evaluateJavascript(
                    "window.__mtbGeoResult && window.__mtbGeoResult($ok);", null
                )
            } catch (_: Exception) {}
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val w = web
        if (keyCode == KeyEvent.KEYCODE_BACK && w != null && w.canGoBack()) {
            w.goBack(); return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        current = java.lang.ref.WeakReference(this)
        try {
            web?.evaluateJavascript(
                "window.__mtbSync && window.__mtbSync(); window.__mtbMediaSync && window.__mtbMediaSync();",
                null
            )
        } catch (_: Exception) {}
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // إعادة تطبيق الوضع الغامر بعد استعادة التركيز (يعود النظام لإظهار الأشرطة أحياناً)
        if (hasFocus && lastFull) applyImmersive(true)
    }

    /**
     * يخفي/يُظهر شريط حالة النظام لكامل التطبيق.
     * نستخدم FLAG_FULLSCREEN لأنه يعمل يقيناً على كل الإصدارات (حتى أندرويد 14 مع targetSdk 34)
     * ولا يزاحم شريط التنقّل السفلي كما يفعل الوضع edge-to-edge.
     */
    @Suppress("DEPRECATION")
    fun applyImmersive(full: Boolean) {
        lastFull = full
        try {
            val w = window ?: return
            if (full) w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            else w.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        } catch (_: Exception) {}
        try {
            getSharedPreferences("mtb_ui", Context.MODE_PRIVATE)
                .edit().putBoolean("fullscreen", full).apply()
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        // لا نتلف الـWebView إذا كان القرآن يعمل — ليستمر الصوت والإشعار
        try {
            val w = web
            if (w != null) {
                (w.parent as? ViewGroup)?.removeView(w)
                if (!WebHolder.audioActive) {
                    WebHolder.web = null
                    w.destroy()
                }
            }
        } catch (_: Exception) {}
        web = null
        super.onDestroy()
    }
}
