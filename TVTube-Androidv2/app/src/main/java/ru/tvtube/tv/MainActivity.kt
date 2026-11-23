package ru.tvtube.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * TV Tube для Android TV / Google TV / приставок: полноэкранный WebView с твоим сайтом.
 * Пульт работает как в браузере (стрелки, OK, Назад, медиа-клавиши). Адрес сервера находится сам.
 */
class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var overlay: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var msg: TextView
    private lateinit var buttons: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("tvtube", MODE_PRIVATE) }
    private var lastBack = 0L
    private var rescanTried = false
    private var scanning = false
    private var serverUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        serverUrl = prefs.getString("url", null)
        val saved = serverUrl
        if (saved != null) openServer(saved) else startScan()
    }

    // ---------------------------------------------------------------- интерфейс
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun styleButton(b: Button) {
        val pad = dp(14)
        b.setPadding(dp(26), pad, dp(26), pad)
        b.textSize = 20f
        b.isAllCaps = false
        b.isFocusable = true
        fun shape(c: Int) = GradientDrawable().apply { cornerRadius = dp(40).toFloat(); setColor(c) }
        val sl = StateListDrawable()
        sl.addState(intArrayOf(android.R.attr.state_focused), shape(Color.WHITE))
        sl.addState(intArrayOf(), shape(Color.parseColor("#2A2A2E")))
        b.background = sl
        b.setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(Color.BLACK, Color.WHITE)))
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(dp(8), 0, dp(8), 0)
        b.layoutParams = lp
    }

    private fun buildUi() {
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        web = WebView(this)
        setupWeb()
        root.addView(web, FrameLayout.LayoutParams(-1, -1))

        overlay = LinearLayout(this)
        overlay.orientation = LinearLayout.VERTICAL
        overlay.gravity = Gravity.CENTER
        overlay.setBackgroundColor(Color.parseColor("#0A0607"))
        overlay.setPadding(dp(60), dp(40), dp(60), dp(40))
        titleView = TextView(this).apply { text = "TV Tube"; textSize = 46f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        msg = TextView(this).apply { textSize = 22f; setTextColor(Color.parseColor("#C9C2C3")); gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(26)) }
        buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        overlay.addView(titleView)
        overlay.addView(msg)
        overlay.addView(buttons)
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun showStatus(text: String, actions: List<Pair<String, () -> Unit>>) {
        msg.text = text
        buttons.removeAllViews()
        var first: Button? = null
        for ((label, fn) in actions) {
            val b = Button(this)
            b.text = label
            styleButton(b)
            b.setOnClickListener { fn() }
            buttons.addView(b)
            if (first == null) first = b
        }
        overlay.visibility = View.VISIBLE
        first?.requestFocus()
    }

    private fun hideStatus() {
        overlay.visibility = View.GONE
        web.requestFocus()
    }

    // ---------------------------------------------------------------- WebView
    private fun setupWeb() {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.allowFileAccess = false
        s.userAgentString = s.userAgentString + " TVTubeApp/1.0 Android TV"
        web.setBackgroundColor(Color.BLACK)
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.addJavascriptInterface(Bridge(), "TTApp")
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != null && url.startsWith("http")) {
                    rescanTried = false
                    hideStatus()
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) failed()
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                if (failingUrl != null && failingUrl == serverUrl) failed()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return false
                val base = serverUrl ?: return false
                return !u.toString().startsWith(base)   // чужие ссылки (реклама) на ТВ не открываем
            }
        }
    }

    private fun openServer(url: String) {
        serverUrl = url
        prefs.edit().putString("url", url).apply()
        showStatus("Подключаюсь к серверу\n$url", emptyList())
        web.loadUrl(url)
    }

    private fun failed() {
        if (!rescanTried) {   // адрес ПК мог поменяться (роутер выдал новый): ищем сервер заново
            rescanTried = true
            startScan("Сервер по старому адресу не отвечает. Ищу заново…")
            return
        }
        showStatus(
            "Не получается подключиться к серверу.\nПроверь, что на ПК запущен HOME.bat, а ТВ и ПК в одной сети Wi-Fi.",
            listOf(
                "Найти сервер" to { startScan() },
                "Ввести адрес" to { askAddress() },
                "Повторить" to { val u = serverUrl; if (u != null) openServer(u) else startScan() }
            )
        )
    }

    private fun startScan(text: String = "Ищу сервер TV Tube в домашней сети…\nНа ПК должен быть запущен HOME.bat") {
        if (scanning) return
        scanning = true
        showStatus(text, emptyList())
        Thread {
            val found = Finder.scan()
            ui.post {
                scanning = false
                if (found != null) {
                    rescanTried = true
                    openServer(found)
                } else {
                    showStatus(
                        "Сервер не найден.\nЗапусти на ПК HOME.bat (ТВ и ПК должны быть в одной сети Wi-Fi) или введи адрес вручную. Его показывает окно сервера, например 192.168.1.50:8000.",
                        listOf("Найти ещё раз" to { startScan() }, "Ввести адрес" to { askAddress() })
                    )
                }
            }
        }.start()
    }

    private fun askAddress() {
        val et = EditText(this)
        et.hint = "192.168.1.50:8000"
        et.setText(serverUrl?.removePrefix("http://") ?: "")
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        AlertDialog.Builder(this)
            .setTitle("Адрес сервера TV Tube")
            .setView(et)
            .setPositiveButton("Подключить") { _, _ ->
                var a = et.text.toString().trim()
                if (a.isNotEmpty()) {
                    if (!a.startsWith("http")) a = "http://$a"
                    val hasPort = Regex("^https?://[^/]+:\\d+").containsMatchIn(a)
                    if (!hasPort && !a.startsWith("https://")) a = a.trimEnd('/') + ":8000"
                    rescanTried = true
                    openServer(a.trimEnd('/'))
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** Методы, которые сайт может вызвать из Настроек: window.TTApp.changeServer() */
    inner class Bridge {
        @JavascriptInterface
        fun changeServer() { runOnUiThread { askAddress() } }

        @JavascriptInterface
        fun rescan() { runOnUiThread { startScan() } }

        @JavascriptInterface
        fun exit() { runOnUiThread { finish() } }

        @JavascriptInterface
        fun version(): String = "1.0"
    }

    // ---------------------------------------------------------------- кнопка Назад: сначала закрывает плеер/раздел, на главной выходит
    private fun backTwiceToExit() {
        if (System.currentTimeMillis() - lastBack < 1800) {
            finish()
        } else {
            lastBack = System.currentTimeMillis()
            Toast.makeText(this, "Нажми Назад ещё раз, чтобы выйти", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (overlay.visibility == View.VISIBLE) {
            backTwiceToExit()
            return
        }
        val js = "(function(){var p=document.getElementById('player'),m=document.getElementById('mplayer'),d=document.getElementById('modal');" +
                "var busy=(p&&!p.hidden)||(m&&!m.hidden)||(d&&!d.hidden);var a=document.querySelector('.mi.act');" +
                "return busy?'busy':((a&&a.getAttribute('data-v')==='home')?'home':'sub');})()"
        web.evaluateJavascript(js) { r ->
            val state = (r ?: "").trim('"')
            if (state == "home") {
                backTwiceToExit()
            } else {
                web.evaluateJavascript("document.dispatchEvent(new KeyboardEvent('keydown',{keyCode:27,which:27,key:'Escape',bubbles:true}))", null)
            }
        }
    }

    override fun onStop() {
        super.onStop()   // ушли на главный экран ТВ: видео ставим на паузу, музыка продолжает играть
        web.evaluateJavascript("(function(){var v=document.querySelector('#pl-stage video');if(v)v.pause();})()", null)
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
