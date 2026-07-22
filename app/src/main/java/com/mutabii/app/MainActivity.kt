package com.mutabii.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
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
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = java.lang.ref.WeakReference(this)
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
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
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

    /** يخفي/يُظهر شريط حالة النظام (يبقى شريط التنقّل ليتوافق مع تبويبات التطبيق السفلية). */
    @Suppress("DEPRECATION")
    fun applyImmersive(full: Boolean) {
        lastFull = full
        try {
            val w = window ?: return
            if (Build.VERSION.SDK_INT >= 30) {
                val c = w.insetsController ?: return
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (full) c.hide(WindowInsets.Type.statusBars())
                else c.show(WindowInsets.Type.statusBars())
            } else {
                if (full) w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                else w.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }
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
