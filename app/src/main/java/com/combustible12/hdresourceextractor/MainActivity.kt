package com.combustible12.hdresourceextractor

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.net.URLDecoder
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var webView: WebView
    private lateinit var listView: ListView
    private lateinit var adapter: ArrayAdapter<String>

    private val resources = linkedSetOf<String>()
    private val visibleResources = mutableListOf<String>()
    private val handler = Handler(Looper.getMainLooper())

    private var pendingSharedText: String = ""
    private var currentPageUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        configureWebView()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun roundedBackground(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun buildUi() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
        }

        webView = WebView(this)
        root.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val foreground = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        ViewCompat.setOnApplyWindowInsetsListener(foreground) { view, insets ->
            val systemInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                dp(8) + systemInsets.left,
                dp(13) + systemInsets.top,
                dp(8) + systemInsets.right,
                systemInsets.bottom
            )
            insets
        }

        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = "粘贴网页"
            textSize = 20f
            setTextColor(Color.rgb(92, 92, 92))
            includeFontPadding = false
        }
        headerRow.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(27)
            )
        )

        addHeaderBadge(headerRow, "微", Color.rgb(34, 194, 82), 19)
        addHeaderBadge(headerRow, "蓝", Color.rgb(73, 132, 213), 19)
        addHeaderBadge(headerRow, "淘", Color.rgb(255, 101, 33), 19)
        addHeaderBadge(headerRow, "BOSS", Color.rgb(48, 170, 148), 24)

        foreground.addView(
            headerRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(28)
            )
        )

        val dividerLine = TextView(this).apply {
            setBackgroundColor(Color.rgb(88, 88, 88))
        }
        foreground.addView(
            dividerLine,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                topMargin = dp(7)
            }
        )

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val openButton = makeMainButton("打开并提取").apply {
            setOnClickListener { openAndExtract() }
        }
        val scanButton = makeMainButton("重新扫描").apply {
            setOnClickListener { scanDomResources() }
        }

        buttonRow.addView(
            openButton,
            LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                rightMargin = dp(4)
            }
        )
        buttonRow.addView(
            scanButton,
            LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                leftMargin = dp(4)
            }
        )

        foreground.addView(
            buttonRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44)
            ).apply {
                topMargin = dp(14)
            }
        )

        statusText = TextView(this).apply {
            text = "等待网页链接"
            textSize = 14f
            setTextColor(Color.rgb(104, 104, 104))
            includeFontPadding = false
        }
        foreground.addView(
            statusText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(9)
            }
        )

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, visibleResources)

        listView = ListView(this).apply {
            adapter = this@MainActivity.adapter
            this.divider = null
            dividerHeight = 0
            setBackgroundColor(Color.WHITE)

            setOnItemClickListener { _, _, position, _ ->
                downloadResource(visibleResources[position])
            }
            setOnItemLongClickListener { _, _, position, _ ->
                copyToClipboard(visibleResources[position])
                true
            }
        }

        foreground.addView(
            listView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                topMargin = dp(4)
            }
        )

        root.addView(
            foreground,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        setContentView(root)
        ViewCompat.requestApplyInsets(foreground)
    }

    private fun addHeaderBadge(
        parent: LinearLayout,
        text: String,
        color: Int,
        widthDp: Int
    ) {
        val badge = TextView(this).apply {
            this.text = text
            textSize = if (text.length > 1) 7f else 10f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = roundedBackground(color, 3)
        }

        parent.addView(
            badge,
            LinearLayout.LayoutParams(dp(widthDp), dp(19)).apply {
                leftMargin = dp(2)
            }
        )
    }

    private fun makeMainButton(textValue: String): Button =
        Button(this).apply {
            text = textValue
            isAllCaps = false
            textSize = 17f
            setTextColor(Color.rgb(28, 28, 28))
            gravity = Gravity.CENTER
            includeFontPadding = false
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(0, 0, 0, 0)
            background = roundedBackground(Color.rgb(224, 225, 225), 2)
        }

    @Suppress("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "$userAgentString HDResourceExtractor/0.2"
            loadWithOverviewMode = true
            useWideViewPort = true
            loadsImagesAutomatically = true
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(ResourceBridge(), "HDExtractor")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                request?.url?.toString()?.let { collectResource(it) }
                return super.shouldInterceptRequest(view, request)
            }

            @Suppress("DEPRECATION")
            override fun shouldInterceptRequest(
                view: WebView?,
                url: String?
            ): WebResourceResponse? {
                url?.let { collectResource(it) }
                return super.shouldInterceptRequest(view, url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!url.isNullOrBlank()) currentPageUrl = url

                statusText.text = "网页已打开，正在扫描高清资源…"
                scanDomResources()

                handler.postDelayed({ scanDomResources(silent = true) }, 1500)
                handler.postDelayed({ scanDomResources(silent = true) }, 4000)
                handler.postDelayed({ scanDomResources(silent = true) }, 7000)
            }
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            if (extractFirstUrl(sharedText) != null) {
                pendingSharedText = sharedText
                statusText.text = "已接收网页链接，点“打开并提取”"
            }
        }
    }

    private fun openAndExtract() {
        val source = pendingSharedText.takeIf { extractFirstUrl(it) != null }
            ?: readClipboardText()

        val url = extractFirstUrl(source)
        if (url == null) {
            statusText.text = "等待网页链接"
            Toast.makeText(
                this,
                "先复制网页链接，或从浏览器 / APP 分享给本应用",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        currentPageUrl = url
        resources.clear()
        visibleResources.clear()
        adapter.notifyDataSetChanged()

        statusText.text = "正在打开网页…"
        webView.stopLoading()
        webView.loadUrl(url)
    }

    private fun readClipboardText(): String {
        return try {
            val clipboard =
                getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

            if (!clipboard.hasPrimaryClip()) return ""

            val clip = clipboard.primaryClip ?: return ""
            if (clip.itemCount == 0) return ""

            clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractFirstUrl(text: String): String? {
        if (text.isBlank()) return null

        return Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
            .find(text)
            ?.value
            ?.trimEnd(
                '.', ',', '，', '。', ';', '；', '!', '！', '?', '？',
                ')', '）', ']', '】', '}', '》', '>', '\'', '"'
            )
    }

    private fun scanDomResources(silent: Boolean = false) {
        if (webView.url.isNullOrBlank()) {
            if (!silent) {
                statusText.text = "还没有打开网页"
                Toast.makeText(this, "先点“打开并提取”", Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (!silent) {
            statusText.text = "正在重新扫描…"
        }

        val script = """
            (function() {
              const found = new Set();

              const add = (u) => {
                if (!u || typeof u !== 'string') return;
                u = u.trim();
                if (!u || u.startsWith('data:') || u.startsWith('javascript:')) return;
                try {
                  found.add(new URL(u, document.baseURI).href);
                } catch(e) {}
              };

              const addSrcset = (value) => {
                if (!value) return;
                String(value).split(',').forEach(x => {
                  const u = x.trim().split(/\\s+/)[0];
                  add(u);
                });
              };

              document.querySelectorAll('img').forEach(el => {
                add(el.currentSrc);
                add(el.src);

                [
                  'data-src',
                  'data-original',
                  'data-origin',
                  'data-url',
                  'data-lazy-src',
                  'data-original-src',
                  'data-actualsrc'
                ].forEach(a => add(el.getAttribute(a)));

                addSrcset(el.getAttribute('srcset'));
                addSrcset(el.getAttribute('data-srcset'));
              });

              document.querySelectorAll('video, audio, source').forEach(el => {
                add(el.currentSrc);
                add(el.src);
                add(el.getAttribute('src'));
                add(el.getAttribute('data-src'));
                addSrcset(el.getAttribute('srcset'));
              });

              document.querySelectorAll('meta[property], meta[name]').forEach(el => {
                const key = (
                  el.getAttribute('property') ||
                  el.getAttribute('name') ||
                  ''
                ).toLowerCase();

                if (
                  key.includes('og:image') ||
                  key.includes('og:video') ||
                  key.includes('twitter:image')
                ) {
                  add(el.getAttribute('content'));
                }
              });

              document.querySelectorAll('*').forEach(el => {
                try {
                  const bg = getComputedStyle(el).backgroundImage || '';
                  const re = /url\\((['"]?)(.*?)\\1\\)/g;
                  let m;
                  while ((m = re.exec(bg)) !== null) add(m[2]);
                } catch(e) {}
              });

              try {
                performance.getEntriesByType('resource').forEach(e => {
                  const t = String(e.initiatorType || '').toLowerCase();
                  const n = String(e.name || '');

                  if (
                    t === 'img' ||
                    t === 'video' ||
                    /\\.(jpe?g|png|webp|gif|avif|bmp|svg|mp4|webm|mov|m4v|m3u8)([?#]|$)/i.test(n) ||
                    /(image|img|photo|video|media|original|origin|webp|avif)/i.test(n)
                  ) add(n);
                });
              } catch(e) {}

              found.forEach(u => HDExtractor.add(u));
              HDExtractor.done(found.size);
            })();
        """.trimIndent()

        webView.evaluateJavascript(script, null)
    }

    private inner class ResourceBridge {
        @JavascriptInterface
        fun add(url: String) {
            collectResource(url)
        }

        @JavascriptInterface
        fun done(count: Int) {
            runOnUiThread {
                statusText.text =
                    if (visibleResources.isEmpty()) {
                        "暂未发现资源，可稍后点“重新扫描”"
                    } else {
                        "已找到 ${visibleResources.size} 个候选资源"
                    }
            }
        }
    }

    private fun collectResource(url: String) {
        if (!isMediaCandidate(url)) return

        synchronized(resources) {
            if (!resources.add(url)) return
        }

        runOnUiThread {
            visibleResources.add(url)
            adapter.notifyDataSetChanged()
            statusText.text = "已找到 ${visibleResources.size} 个候选资源"
        }
    }

    private fun isMediaCandidate(url: String): Boolean {
        if (
            !url.startsWith("http://") &&
            !url.startsWith("https://") &&
            !url.startsWith("blob:")
        ) return false

        val clean = url
            .substringBefore('?')
            .substringBefore('#')
            .lowercase(Locale.US)

        val extensions = listOf(
            ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".avif", ".heic",
            ".mp4", ".webm", ".mov", ".m4v", ".m3u8", ".ts", ".mpd",
            ".mp3", ".m4a", ".aac", ".wav", ".ogg"
        )

        if (extensions.any { clean.endsWith(it) }) return true

        val lower = url.lowercase(Locale.US)
        return listOf(
            "image",
            "img",
            "photo",
            "video",
            "media",
            "poster",
            "original",
            "origin",
            "source",
            "webp",
            "avif"
        ).any { lower.contains(it) }
    }

    private fun downloadResource(url: String) {
        if (url.startsWith("blob:")) {
            Toast.makeText(
                this,
                "blob: 资源不能直接交给系统下载",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        try {
            val uri = Uri.parse(url)
            val guessedName =
                URLDecoder.decode(uri.lastPathSegment ?: "resource", "UTF-8")
                    .takeIf { it.isNotBlank() }
                    ?: "resource"

            val request = DownloadManager.Request(uri)
                .setTitle(guessedName)
                .setDescription("HDResourceExtractor")
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    guessedName
                )
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            val userAgent = webView.settings.userAgentString
            if (!userAgent.isNullOrBlank()) {
                request.addRequestHeader("User-Agent", userAgent)
            }

            if (currentPageUrl.isNotBlank()) {
                request.addRequestHeader("Referer", currentPageUrl)
            }

            CookieManager.getInstance().getCookie(url)
                ?.takeIf { it.isNotBlank() }
                ?.let { request.addRequestHeader("Cookie", it) }

            val manager =
                getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

            manager.enqueue(request)

            Toast.makeText(
                this,
                "已交给系统下载：$guessedName",
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "下载失败：${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard =
            getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        clipboard.setPrimaryClip(
            ClipData.newPlainText("resource url", text)
        )

        Toast.makeText(
            this,
            "资源链接已复制",
            Toast.LENGTH_SHORT
        ).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)

        webView.apply {
            loadUrl("about:blank")
            stopLoading()
            removeAllViews()
            destroy()
        }

        super.onDestroy()
    }
}
