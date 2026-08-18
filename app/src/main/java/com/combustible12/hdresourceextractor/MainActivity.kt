package com.combustible12.hdresourceextractor

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
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
import android.widget.EditText
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

    private lateinit var urlInput: EditText
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
        (value * getResources().displayMetrics.density).toInt()

    private fun roundedBackground(
        color: Int,
        radiusDp: Int,
        strokeColor: Int? = null,
        strokeDp: Int = 1
    ): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            strokeColor?.let { setStroke(dp(strokeDp), it) }
        }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(248, 249, 251))
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                dp(14) + systemInsets.left,
                dp(12) + systemInsets.top,
                dp(14) + systemInsets.right,
                dp(10) + systemInsets.bottom
            )
            insets
        }

        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE

        val title = TextView(this).apply {
            text = "HDResourceExtractor"
            textSize = 21f
            setTextColor(Color.rgb(34, 34, 34))
            setTypeface(typeface, Typeface.BOLD)
            includeFontPadding = false
        }
        root.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        )

        urlInput = EditText(this).apply {
            hint = "粘贴网页链接"
            textSize = 15f
            setTextColor(Color.rgb(35, 35, 35))
            setHintTextColor(Color.rgb(148, 152, 158))
            isSingleLine = true
            setPadding(dp(12), 0, dp(12), 0)
            background = roundedBackground(
                Color.WHITE,
                8,
                Color.rgb(199, 204, 211)
            )
        }
        root.addView(
            urlInput,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(46)
            )
        )

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val openButton = makeMainButton("打开并提取", primary = true).apply {
            setOnClickListener { openAndExtract() }
        }
        val scanButton = makeMainButton("重新扫描", primary = false).apply {
            setOnClickListener { scanDomResources() }
        }

        buttonRow.addView(
            openButton,
            LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                rightMargin = dp(5)
            }
        )
        buttonRow.addView(
            scanButton,
            LinearLayout.LayoutParams(0, dp(44), 0.54f).apply {
                leftMargin = dp(5)
            }
        )
        root.addView(
            buttonRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44)
            ).apply {
                topMargin = dp(10)
            }
        )

        statusText = TextView(this).apply {
            text = "等待网页链接"
            textSize = 13f
            setTextColor(Color.rgb(98, 98, 98))
            includeFontPadding = false
        }
        root.addView(
            statusText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(9)
                bottomMargin = dp(8)
            }
        )

        val previewTitle = TextView(this).apply {
            text = "网页预览"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(45, 45, 45))
            includeFontPadding = false
        }
        root.addView(
            previewTitle,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(6)
            }
        )

        webView = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            background = roundedBackground(
                Color.WHITE,
                7,
                Color.rgb(207, 212, 218)
            )
        }
        root.addView(
            webView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1.12f
            )
        )

        val resourceHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val resourceTitle = TextView(this).apply {
            text = "检测到的资源"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(45, 45, 45))
            includeFontPadding = false
        }
        val resourceHint = TextView(this).apply {
            text = "点击下载 · 长按复制"
            textSize = 11f
            gravity = Gravity.END
            setTextColor(Color.rgb(130, 130, 130))
            includeFontPadding = false
        }
        resourceHeader.addView(
            resourceTitle,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        resourceHeader.addView(
            resourceHint,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        root.addView(
            resourceHeader,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
                bottomMargin = dp(5)
            }
        )

        adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            visibleResources
        )

        listView = ListView(this).apply {
            adapter = this@MainActivity.adapter
            divider = null
            dividerHeight = 0
            setBackgroundColor(Color.WHITE)
            background = roundedBackground(
                Color.WHITE,
                7,
                Color.rgb(207, 212, 218)
            )
            setPadding(dp(4), dp(2), dp(4), dp(2))
            clipToPadding = false

            setOnItemClickListener { _, _, position, _ ->
                downloadResource(visibleResources[position])
            }
            setOnItemLongClickListener { _, _, position, _ ->
                copyToClipboard(visibleResources[position])
                true
            }
        }

        root.addView(
            listView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                0.88f
            )
        )

        setContentView(root)
        ViewCompat.requestApplyInsets(root)
    }

    private fun makeMainButton(textValue: String, primary: Boolean): Button =
        Button(this).apply {
            text = textValue
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.rgb(28, 28, 28))
            gravity = Gravity.CENTER
            includeFontPadding = false
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(0, 0, 0, 0)
            background = roundedBackground(
                if (primary) Color.rgb(230, 234, 240)
                else Color.rgb(245, 246, 248),
                7,
                Color.rgb(190, 195, 202)
            )
        }

    @Suppress("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "$userAgentString HDResourceExtractor/0.3"
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
                if (!url.isNullOrBlank()) {
                    currentPageUrl = url
                    if (urlInput.text.isNullOrBlank()) {
                        urlInput.setText(url)
                    }
                }

                statusText.text = "网页已打开，正在扫描高清资源…"
                scanDomResources()

                handler.postDelayed({ scanDomResources(silent = true) }, 1500)
                handler.postDelayed({ scanDomResources(silent = true) }, 4000)
                handler.postDelayed({ scanDomResources(silent = true) }, 7000)
            }
        }

        webView.loadUrl("about:blank")
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val sharedUrl = extractFirstUrl(sharedText)
            if (sharedUrl != null) {
                pendingSharedText = sharedText
                urlInput.setText(sharedUrl)
                statusText.text = "已接收网页链接，点“打开并提取”"
            }
        }
    }

    private fun openAndExtract() {
        val typedText = urlInput.text?.toString().orEmpty()
        val source = when {
            extractFirstUrl(typedText) != null -> typedText
            extractFirstUrl(pendingSharedText) != null -> pendingSharedText
            else -> readClipboardText()
        }

        val url = extractFirstUrl(source)
        if (url == null) {
            statusText.text = "等待网页链接"
            Toast.makeText(
                this,
                "先粘贴网页链接，或从浏览器 / APP 分享给本应用",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        urlInput.setText(url)
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
        val pageUrl = webView.url
        if (pageUrl.isNullOrBlank() || pageUrl == "about:blank") {
            if (!silent) {
                statusText.text = "还没有打开网页"
                Toast.makeText(
                    this,
                    "先点“打开并提取”",
                    Toast.LENGTH_SHORT
                ).show()
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
                  const u = x.trim().split(/\s+/)[0];
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
                  const re = /url\((['"]?)(.*?)\1\)/g;
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
                    /\.(jpe?g|png|webp|gif|avif|bmp|svg|mp4|webm|mov|m4v|m3u8)([?#]|$)/i.test(n) ||
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
