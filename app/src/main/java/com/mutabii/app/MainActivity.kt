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

        const val REQ_LOC = 104
        const val REQ_SOUND = 556

        /**
         * قفل التصفّح على صفحة التطبيق وحدها.
         *
         * ‏MTBNative مُحقَن في كل صفحة يعرضها هذا الـWebView — بما فيها أيّ رابط خارجي.
         * فلو فُتح موقع بعيد هنا (رابط في نصّ، أو إعادة توجيه) لأصبح بمقدوره استدعاء
         * ‏getAlarms و recStart و saveText: يقرأ إعداداتك، أو يسجّل بميكروفونك، أو يكتب ملفات.
         * لذلك: أصول التطبيق تبقى داخلاً، وكل ما عداها يُفتح في متصفّح النظام.
         *
         * ملاحظة: العميل يُبنى بلا أي مرجع للنشاط — الـWebView يعيش أطول من الشاشة
         * (ليستمر الصوت) فأيّ التقاط للنشاط هنا يعني تسريبه.
         */
        private fun assetOnlyClient(app: Context): WebViewClient =
            object : WebViewClient() {
                private fun isOurs(u: Uri?): Boolean {
                    val url = u?.toString() ?: return false
                    return url.startsWith("file:///android_asset/") ||
                        url.startsWith("file:///android_res/") ||
                        url == "about:blank"
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?, request: android.webkit.WebResourceRequest?
                ): Boolean {
                    val u = request?.url ?: return true
                    if (isOurs(u)) return false
                    openOutside(app, u)
                    return true
                }

                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    val u = try { Uri.parse(url ?: return true) } catch (_: Exception) { return true }
                    if (isOurs(u)) return false
                    openOutside(app, u)
                    return true
                }
            }

        /** يفتح الروابط الخارجية في متصفّح النظام — خارج نطاق الجسر تماماً. */
        private fun openOutside(app: Context, u: Uri) {
            val sch = (u.scheme ?: "").lowercase()
            if (sch != "http" && sch != "https" && sch != "mailto" && sch != "tel") return
            try {
                app.startActivity(
                    Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {}
        }

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
        // مطلوب لتحميل file:///android_asset على أندرويد ٨–١٠ (minSdk 26)
        s.allowFileAccess = true
        // يبقى مفتوحاً: منتقي الملفات يُعيد content:// لاستعادة JSON واستيراد CSV واختيار الصوت.
        // الحماية الفعلية هي قفل التصفّح أدناه — فلا توجد صفحة غريبة أصلاً لتستغلّه.
        s.allowContentAccess = true
        // تصريح دفاعي: صفحة file:// يجب ألّا تقرأ ملفات أخرى ولا أصولاً بعيدة
        @Suppress("DEPRECATION")
        s.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        s.allowUniversalAccessFromFileURLs = false
        s.mediaPlaybackRequiresUserGesture = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        // النوافذ المنبثقة التلقائية ليست مستعملة، وفتحها يوسّع سطح الهجوم بلا داعٍ
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(false)
        w.setBackgroundColor(0xFF1A1410.toInt())
        w.webViewClient = assetOnlyClient(applicationContext)
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

        /**
         * لا نمنح الصفحة أيّ صلاحية جهاز (ميكروفون/كاميرا) — نظام التسجيل الصوتي أُزيل،
         * والصفحة لا تحتاج getUserMedia إطلاقاً، فنرفض كل طلب توسّعاً في الحماية.
         */
        override fun onPermissionRequest(request: PermissionRequest?) {
            try { request?.deny() } catch (_: Exception) {}
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
                    val soundKey = if (soundTarget == "wake") Prefs.K_WAKE_SOUND else Prefs.K_ADHAN_SOUND
                    // اختيار ملف = تحويل المصدر إلى «custom» ليأخذ الأثر فوراً على التنبيهات
                    try { Prefs.sp(this).edit().putString(key, uri.toString()).putString(soundKey, "custom").apply() }
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
            if (Build.VERSION.SDK_INT >= 28) {
                // تعديل w.attributes في مكانه لا يُطبَّق: لا بدّ من إعادة إسناد الكائن
                // حتى يُعيد النظام حساب التخطيط — وإلا بقي النقش (notch) يقصّ الواجهة.
                val lp = w.attributes
                lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                w.attributes = lp
            }
            if (full) {
                w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                w.decorView.systemUiVisibility = (android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN)
            } else {
                w.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                w.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
            }
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
                } else {
                    // الـWebView يبقى حيّاً للصوت — لكن chromeClient يمسك بهذه الشاشة
                    // المُتلَفة، فيتسرّب النشاط بأكمله. نفكّه ويُعاد ضبطه عند العودة.
                    try { w.webChromeClient = null } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
        web = null
        super.onDestroy()
    }
}
