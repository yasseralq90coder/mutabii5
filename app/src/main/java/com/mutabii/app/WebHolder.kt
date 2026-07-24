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

    /**
     * الأوامر تُبنى نصّاً داخل JavaScript، فأيّ اقتباس أو شرطة مائلة في الوسيط
     * يكسر الجملة أو يحقن شفرة. نُهرّب كل ما يخرج عن الحروف الآمنة ونقصر الطول.
     */
    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s.take(256)) {
            val code = ch.code
            when {
                ch == '\\' -> sb.append("\\\\")
                ch == '\'' -> sb.append("\\'")
                ch == '"' -> sb.append("\\\"")
                ch == '<' -> sb.append("\\u003c")
                ch == '>' -> sb.append("\\u003e")
                ch == '&' -> sb.append("\\u0026")
                // فواصل الأسطر بأنواعها تُنهي جملة JavaScript قبل أوانها
                code < 0x20 || code == 0x2028 || code == 0x2029 || code == 0x7F ->
                    sb.append(String.format("\\u%04x", code))
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun cmd(c: String, arg: String = "0") {
        eval("window.__mtbCmd && window.__mtbCmd('${esc(c)}','${esc(arg)}');")
    }
}
