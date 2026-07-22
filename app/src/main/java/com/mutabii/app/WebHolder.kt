package com.mutabii.app

import android.webkit.WebView

/** يحتفظ بمرجع الـWebView ليستمر تشغيل القرآن بعد إغلاق الواجهة. */
object WebHolder {
    @Volatile var web: WebView? = null
    @Volatile var audioActive: Boolean = false

    fun eval(js: String) {
        val w = web ?: return
        try {
            w.post { try { w.evaluateJavascript(js, null) } catch (_: Exception) {} }
        } catch (_: Exception) {}
    }

    fun cmd(c: String, arg: String = "0") {
        eval("window.__mtbCmd && window.__mtbCmd('$c','$arg');")
    }
}
