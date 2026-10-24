package com.combustible12.hdresourceextractor

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.load
import java.net.URLDecoder
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var urlInput: EditText
    private lateinit var statusText: TextView
    private lateinit var webView: WebView
    private lateinit var resultsContainer: LinearLayout
    private lateinit var resultTitle: TextView
    private lateinit var saveAllButton: Button

    private val imageFallbacks = mutableMapOf<String, LinkedHashSet<String>>()
    private val taskStore by lazy { DownloadTaskStore(this) }
    private val resources = linkedSetOf<String>()
    private val visibleResources = mutableListOf<String>()
    private val xhsCandidates = linkedMapOf<String, String>()
    private val xhsCandidateScores = mutableMapOf<String, Int>()
    private val structuredMedia = linkedSetOf<String>()
    private val structuredOtherImages = linkedSetOf<String>()
    private val handler = Handler(Looper.getMainLooper())

    private var pendingSharedText: String = ""
    private var currentPageUrl: String = ""
    private var isXhsSession: Boolean = false
    private var isDouyinSession: Boolean = false
    private var xhsFallbackShown: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DownloadCache.clean(this, taskStore.all().filter { it.status !in listOf("completed", "cancelled") }
            .map { ResourceIdentity.of(it.url) }.toSet())
        if (taskStore.all().any { it.kind != "SYSTEM" && it.status in listOf("running", "queued") }) DownloadService.start(this)

        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 104)
        }
        buildUi()
        configureWebView()
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::statusText.isInitialized) AppUpdater(this).recoverPendingUpdate { message -> runOnUiThread { statusText.text = message } }
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
    ): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        strokeColor?.let { setStroke(dp(strokeDp), it) }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(247, 248, 250))
        }

        webView = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
        }
        root.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val foreground = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(247, 248, 250))
            // Apply the safe fallback immediately. Some devices never dispatch the
            // insets callback, so relying on the listener alone can leave the title at y=0.
            setPadding(dp(14), dp(63), dp(14), dp(10))
        }

        ViewCompat.setOnApplyWindowInsetsListener(foreground) { view, insets ->
            val systemInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            // Some vendor builds report a zero top inset even though their status bar
            // overlays the app. Keep a small fallback safe area so the title stays clear.
            val safeTopInset = maxOf(systemInsets.top, dp(28))
            view.setPadding(
                dp(14) + systemInsets.left,
                dp(35) + safeTopInset,
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
            setTextColor(Color.rgb(31, 31, 31))
            setTypeface(typeface, Typeface.BOLD)
            includeFontPadding = false
        }
        foreground.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
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
        foreground.addView(
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
            setOnClickListener { scanCurrentPage() }
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
        foreground.addView(
            buttonRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44)
            ).apply { topMargin = dp(10) }
        )

        statusText = TextView(this).apply {
            text = "等待网页链接"
            textSize = 13f
            setTextColor(Color.rgb(95, 95, 95))
            includeFontPadding = false
        }
        foreground.addView(
            statusText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(9)
                bottomMargin = dp(10)
            }
        )

        val updateButton = makeBottomActionButton("一键检查并升级").apply {
            setOnClickListener {
                AppUpdater(this@MainActivity).checkForUpdate { message ->
                    runOnUiThread { statusText.text = message }
                }
            }
        }
        val resultHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        resultTitle = TextView(this).apply {
            text = "提取结果"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(36, 36, 36))
            includeFontPadding = false
        }
        resultHeader.addView(
            resultTitle,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        saveAllButton = Button(this).apply {
            text = "保存全部图片"
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.WHITE)
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(14), 0, dp(14), 0)
            background = roundedBackground(Color.rgb(41, 156, 235), 8)
            visibility = View.GONE
            setOnClickListener { saveAllResources() }
        }
        resultHeader.addView(
            saveAllButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(38)
            )
        )

        foreground.addView(
            resultHeader,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        )

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }

        resultsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(16))
        }
        scrollView.addView(
            resultsContainer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        foreground.addView(
            scrollView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val tasksButton = makeBottomActionButton("下载任务管理").apply {
            setOnClickListener { showDownloadTasks() }
        }
        foreground.addView(tasksButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(8) })

        foreground.addView(
            updateButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply {
                topMargin = dp(8)
                bottomMargin = dp(42)
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
        renderResults()
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

    private fun makeBottomActionButton(textValue: String): Button =
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
            background = roundedBackground(Color.rgb(245, 246, 248), 7)
            stateListAnimator = null
            elevation = 0f
        }

    private fun renderResults() {
        if (!::resultsContainer.isInitialized) return

        resultsContainer.removeAllViews()
        val snapshot = visibleResources.toList()

        resultTitle.text = when {
            snapshot.isEmpty() -> "提取结果"
            isXhsSession && !xhsFallbackShown -> "正文媒体（${snapshot.size}）"
            else -> "检测到的资源（${snapshot.size}）"
        }

        saveAllButton.visibility = if (snapshot.isEmpty()) View.GONE else View.VISIBLE
        saveAllButton.text = if (isXhsSession && !xhsFallbackShown) {
            "保存全部媒体"
        } else {
            "保存全部资源"
        }

        if (snapshot.isEmpty()) {
            val empty = TextView(this).apply {
                text = "解析完成后，图片会在这里直接预览"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(145, 145, 145))
                setPadding(dp(12), dp(34), dp(12), dp(34))
                background = roundedBackground(Color.WHITE, 10)
            }
            resultsContainer.addView(
                empty,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            return
        }

        snapshot.chunked(2).forEachIndexed { rowIndex, pair ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }

            pair.forEachIndexed { columnIndex, url ->
                val index = rowIndex * 2 + columnIndex
                row.addView(
                    createResourceCard(url, index),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (columnIndex == 0) rightMargin = dp(5)
                        else leftMargin = dp(5)
                    }
                )
            }

            if (pair.size == 1) {
                val spacer = View(this)
                row.addView(
                    spacer,
                    LinearLayout.LayoutParams(0, dp(1), 1f).apply {
                        leftMargin = dp(5)
                    }
                )
            }

            resultsContainer.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            )
        }
    }

    private fun createResourceCard(url: String, index: Int): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(Color.WHITE, 10)
            clipToOutline = true
        }

        val imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(235, 237, 240))
            contentDescription = "资源 ${index + 1}"
        }

        if (isLikelyVideoUrl(url)) {
            imageView.setImageDrawable(ColorDrawable(Color.rgb(45, 48, 54)))
        } else {
            imageView.load(url) {
                addHeader("User-Agent", webView.settings.userAgentString.orEmpty())
                if (currentPageUrl.isNotBlank()) addHeader("Referer", currentPageUrl)
                CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let { addHeader("Cookie", it) }
                crossfade(true)
                placeholder(ColorDrawable(Color.rgb(235, 237, 240)))
                error(ColorDrawable(Color.rgb(224, 227, 231)))
            }
        }

        card.addView(
            imageView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(220)
            )
        )

        val firstAction = makeCardAction(
            if (isLikelyVideoUrl(url)) "下载视频" else "下载图片"
        ) {
            enqueueDownload(url, index, showToast = true)
        }
        card.addView(
            firstAction,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(46)
            )
        )

        val divider = View(this).apply {
            setBackgroundColor(Color.rgb(238, 238, 238))
        }
        card.addView(
            divider,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
            )
        )

        val secondAction = makeCardAction(
            if (isLikelyVideoUrl(url)) "复制视频链接" else "复制图片链接"
        ) {
            copyToClipboard(url)
        }
        card.addView(
            secondAction,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(46)
            )
        )

        return card
    }

    private fun makeCardAction(textValue: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = textValue
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.rgb(40, 40, 40))
            setPadding(dp(12), 0, dp(12), 0)
            includeFontPadding = false
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    @Suppress("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "$userAgentString HDResourceExtractor/0.5"
            loadWithOverviewMode = true
            useWideViewPort = true
            loadsImagesAutomatically = true
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(ResourceBridge(), "HDExtractor")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean = shouldBlockNavigation(request?.url?.toString())

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                shouldBlockNavigation(url)

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                request?.url?.toString()?.let { observeResource(it) }
                return super.shouldInterceptRequest(view, request)
            }

            @Suppress("DEPRECATION")
            override fun shouldInterceptRequest(
                view: WebView?,
                url: String?
            ): WebResourceResponse? {
                url?.let { observeResource(it) }
                return super.shouldInterceptRequest(view, url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!url.isNullOrBlank() && url != "about:blank") {
                    currentPageUrl = url
                }

                if (isXhsSession || isDouyinSession) {
                    statusText.text = "正在识别正文媒体资源…"
                    scanXhsNoteResources(silent = true)
                    scanStructuredNote()
                    handler.postDelayed({ scanXhsNoteResources(silent = true) }, 800)
                    handler.postDelayed({ scanXhsNoteResources(silent = true); scanStructuredNote() }, 2200)
                    handler.postDelayed({ finalizeXhsResults(allowFallback = false) }, 3000)
                    handler.postDelayed({ scanXhsNoteResources(silent = true); scanStructuredNote() }, 5000)
                    handler.postDelayed({ finalizeXhsResults(allowFallback = true) }, 6500)
                    handler.postDelayed({ scanXhsNoteResources(silent = true) }, 10000)
                    handler.postDelayed({ finalizeXhsResults(allowFallback = true) }, 12000)
                } else {
                    statusText.text = "网页已打开，正在扫描高清资源…"
                    scanDomResources()
                    handler.postDelayed({ scanDomResources(silent = true) }, 1500)
                    handler.postDelayed({ scanDomResources(silent = true) }, 4000)
                    handler.postDelayed({ scanDomResources(silent = true) }, 7000)
                    handler.postDelayed({ scanDomResources(silent = true) }, 11000)
                    handler.postDelayed({ scanDomResources(silent = true) }, 16000)
                }
            }
        }

        webView.loadUrl("about:blank")
    }

    private fun shouldBlockNavigation(url: String?): Boolean {
        if (url.isNullOrBlank()) return false

        val scheme = try {
            Uri.parse(url).scheme?.lowercase(Locale.US).orEmpty()
        } catch (_: Exception) {
            ""
        }

        if (scheme == "http" || scheme == "https" || scheme == "about") {
            return false
        }

        if (isXhsSession) {
            runOnUiThread {
                statusText.text = "已拦截小红书 APP 跳转，继续提取正文图片…"
                scanXhsNoteResources(silent = true)
                handler.postDelayed({ finalizeXhsResults(allowFallback = true) }, 700)
            }
        }
        return true
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
        isXhsSession = isXhsUrl(url)
        isDouyinSession = url.contains("douyin.com", true) || url.contains("iesdouyin.com", true)
        xhsFallbackShown = false

        synchronized(resources) { resources.clear() }
        synchronized(xhsCandidates) {
            xhsCandidates.clear()
            xhsCandidateScores.clear()
            imageFallbacks.clear()
        }
        synchronized(structuredMedia) { structuredMedia.clear() }
        synchronized(structuredOtherImages) { structuredOtherImages.clear() }
        visibleResources.clear()
        renderResults()

        statusText.text = if (isXhsSession) {
            "正在打开小红书笔记…"
        } else {
            "正在打开网页…"
        }

        webView.stopLoading()
        webView.loadUrl(url)
    }

    private fun scanCurrentPage() {
        if (isXhsSession || isDouyinSession) {
            statusText.text = "正在重新识别媒体资源…"
            scanXhsNoteResources(silent = true)
            scanStructuredNote()
            handler.postDelayed({ finalizeXhsResults(allowFallback = true) }, 900)
        } else {
            scanDomResources()
        }
    }

    private fun readClipboardText(): String {
        return try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
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

    private fun isXhsUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        return lower.contains("xhslink.cn") ||
            lower.contains("xiaohongshu.com") ||
            lower.contains("xhscdn.com")
    }

    private fun observeResource(url: String) {
        collectGenericResource(url, showImmediately = !isXhsSession)
        if (isXhsSession) {
            collectXhsCandidate(url, 0, 0, "network")
        }
    }

    private val sharedDouyinParser by lazy {
        assets.open("douyin_parser.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private val sharedXhsParser by lazy {
        assets.open("xhs_parser.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun scanStructuredNote() {
        if (!isXhsSession && !isDouyinSession) return
        val script = if (isDouyinSession) {
            sharedDouyinParser + "\n;try{HDExtractor.structured(JSON.stringify(window.HDDouyinParser.extract()));}catch(e){}"
        } else {
            sharedXhsParser + "\n;try{HDExtractor.structured(JSON.stringify(window.HDXhsParser.extract()));}catch(e){}"
        }
        webView.evaluateJavascript(script, null)
    }

    private fun scanXhsNoteResources(silent: Boolean = false) {
        val pageUrl = webView.url
        if (pageUrl.isNullOrBlank() || pageUrl == "about:blank") {
            if (!silent) statusText.text = "还没有打开网页"
            return
        }

        val script = """
            (function() {
              const seen = new Set();

              const normalise = (u) => {
                if (!u || typeof u !== 'string') return '';
                return u
                  .replace(/\\u002F/gi, '/')
                  .replace(/\\\//g, '/')
                  .replace(/&amp;/g, '&')
                  .trim();
              };

              const add = (u, w, h, source) => {
                u = normalise(u);
                if (!u || seen.has(u)) return;
                if (!/^https?:/i.test(u)) return;
                if (!/xhscdn\.com/i.test(u)) return;
                seen.add(u);
                try {
                  HDExtractor.addXhs(u, Number(w || 0), Number(h || 0), String(source || ''));
                } catch(e) {}
              };

              document.querySelectorAll('img').forEach((el) => {
                const w = el.naturalWidth || el.width || 0;
                const h = el.naturalHeight || el.height || 0;
                add(el.currentSrc, w, h, 'dom');
                add(el.src, w, h, 'dom');
                [
                  'data-src', 'data-original', 'data-origin', 'data-url',
                  'data-lazy-src', 'data-original-src', 'data-actualsrc'
                ].forEach(a => add(el.getAttribute(a), w, h, 'dom'));

                const srcset = el.getAttribute('srcset') || el.getAttribute('data-srcset') || '';
                srcset.split(',').forEach(x => add(x.trim().split(/\s+/)[0], w, h, 'dom'));
              });

              try {
                performance.getEntriesByType('resource').forEach(e => {
                  add(e.name, 0, 0, 'performance');
                });
              } catch(e) {}

              try {
                const html = document.documentElement ? document.documentElement.innerHTML : '';
                const re = /https?:\\?\/\\?\/[^\"'<>\\s]+?xhscdn\.com[^\"'<>\\s]*/gi;
                let m;
                let guard = 0;
                while ((m = re.exec(html)) !== null && guard < 1200) {
                  add(m[0], 0, 0, 'html');
                  guard++;
                }
              } catch(e) {}

              try {
                document.querySelectorAll('script').forEach(s => {
                  const t = s.textContent || '';
                  if (!/xhscdn\.com/i.test(t)) return;
                  const re = /https?:\\?\/\\?\/[^\"'<>\\s]+?xhscdn\.com[^\"'<>\\s]*/gi;
                  let m;
                  let guard = 0;
                  while ((m = re.exec(t)) !== null && guard < 500) {
                    add(m[0], 0, 0, 'script');
                    guard++;
                  }
                });
              } catch(e) {}

              try { HDExtractor.doneXhs(seen.size); } catch(e) {}
            })();
        """.trimIndent()

        webView.evaluateJavascript(script, null)
    }

    private fun collectXhsCandidate(
        rawUrl: String,
        width: Int,
        height: Int,
        source: String
    ) {
        val url = rawUrl
            .replace("\\u002F", "/", ignoreCase = true)
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .trim()

        if (!isLikelyXhsMainImage(url, width, height, source)) return

        val key = canonicalXhsKey(url)
        if (key.isBlank()) return
        val score = xhsQualityScore(url, width, height, source)

        synchronized(xhsCandidates) {
            imageFallbacks.getOrPut(key) { linkedSetOf() }.add(url)
            val oldScore = xhsCandidateScores[key] ?: Int.MIN_VALUE
            if (score > oldScore) {
                xhsCandidates[key] = xhsOriginalImageUrl(url)
                xhsCandidateScores[key] = score
            }
        }
    }

    private fun xhsOriginalImageUrl(url: String): String {
        return try {
            val uri = Uri.parse(url)
            val host = uri.host?.lowercase(Locale.US).orEmpty()
            if (host == "ci.xiaohongshu.com") {
                url.substringBefore('?').substringBefore('#')
            } else if (host.endsWith("xhscdn.com")) {
                val segments = uri.pathSegments
                val timeIndex = segments.indexOfFirst { it.matches(Regex("[0-9]{12}")) }
                if (timeIndex < 0 || timeIndex + 2 >= segments.size ||
                    !segments[timeIndex + 1].matches(Regex("[0-9a-fA-F]{32}"))) {
                    url
                } else {
                    val payload = segments.drop(timeIndex + 2).toMutableList()
                    payload[payload.lastIndex] = payload.last().substringBefore('!').substringBefore('?')
                    "https://ci.xiaohongshu.com/" + payload.joinToString("/")
                }
            } else url
        } catch (_: Exception) { url }
    }

    private fun isLikelyXhsMainImage(
        url: String,
        width: Int,
        height: Int,
        source: String
    ): Boolean {
        val lower = url.lowercase(Locale.US)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        if (!lower.contains("xhscdn.com") && !lower.contains("ci.xiaohongshu.com")) return false

        val host = try {
            Uri.parse(url).host?.lowercase(Locale.US).orEmpty()
        } catch (_: Exception) {
            ""
        }
        val path = try {
            Uri.parse(url).path?.lowercase(Locale.US).orEmpty()
        } catch (_: Exception) {
            lower
        }

        if (
            host.contains("avatar") ||
            path.contains("avatar") ||
            path.contains("emoji") ||
            path.contains("icon") ||
            path.contains("logo") ||
            path.contains("qrcode")
        ) return false

        val mainImageHost = host.contains("sns-webpic") || host.contains("sns-img")
        if (!mainImageHost && source != "structured") return false

        val looksLikeNoteAsset =
            path.contains("/1040g") ||
            path.contains("/notes") ||
            lower.contains("!h5_") ||
            lower.contains("1080jpg") ||
            lower.contains("1080webp")

        val largeDomImage =
            source == "dom" &&
            maxOf(width, height) >= 700 &&
            minOf(width, height) >= 350

        return source == "structured" || looksLikeNoteAsset || largeDomImage
    }

    private fun canonicalXhsKey(url: String): String {
        val cleaned = url
            .substringBefore('#')
            .substringBefore('?')
            .substringBefore('!')

        return try {
            val uri = Uri.parse(cleaned)
            val segment = uri.lastPathSegment.orEmpty()
            if (segment.length >= 18) segment else cleaned
        } catch (_: Exception) {
            cleaned
        }
    }

    private fun xhsQualityScore(
        url: String,
        width: Int,
        height: Int,
        source: String
    ): Int {
        val lower = url.lowercase(Locale.US)
        var score = 0
        if (lower.startsWith("https://")) score += 30
        if (source == "dom") score += 200
        if (width > 0 && height > 0) {
            score += ((width.toLong() * height.toLong()) / 10000L)
                .coerceAtMost(5000L)
                .toInt()
        }
        if (!url.contains('!')) score += 800
        if (lower.contains("original") || lower.contains("origin")) score += 1500

        val sizes = Regex(
            "(?<!\\d)(2160|2048|1920|1440|1280|1200|1080|1040|960|720|640)(?!\\d)"
        )
            .findAll(lower)
            .mapNotNull { it.value.toIntOrNull() }
            .toList()
        if (sizes.isNotEmpty()) score += sizes.maxOrNull() ?: 0
        return score
    }

    private fun finalizeXhsResults(allowFallback: Boolean) {
        val filtered = synchronized(xhsCandidates) {
            xhsCandidates.values.toList()
        } + synchronized(structuredOtherImages) { structuredOtherImages.toList() } + synchronized(structuredMedia) { structuredMedia.toList() }

        if (filtered.isNotEmpty()) {
            runOnUiThread {
                visibleResources.clear()
                visibleResources.addAll(filtered)
                renderResults()
                statusText.text = "已识别 ${filtered.size} 个小红书图片/视频资源"
            }
            return
        }

        if (allowFallback && !xhsFallbackShown) {
            xhsFallbackShown = true
            runOnUiThread {
                statusText.text = "未读取到正文图片，已切换通用资源扫描"
                showGenericFallbackResults()
                scanDomResources(silent = true)
            }
        }
    }

    private fun showGenericFallbackResults() {
        val snapshot = synchronized(resources) { resources.toList() }
        visibleResources.clear()
        visibleResources.addAll(snapshot)
        renderResults()
        if (snapshot.isNotEmpty()) {
            statusText.text = "专用解析未命中，显示 ${snapshot.size} 个候选资源"
        }
    }

    private fun scanDomResources(silent: Boolean = false) {
        val pageUrl = webView.url
        if (pageUrl.isNullOrBlank() || pageUrl == "about:blank") {
            if (!silent) {
                statusText.text = "还没有打开网页"
                Toast.makeText(this, "先点“打开并提取”", Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (!silent && !isXhsSession) {
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
                  'data-src', 'data-original', 'data-origin', 'data-url',
                  'data-lazy-src', 'data-original-src', 'data-actualsrc'
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
                ) add(el.getAttribute('content'));
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
                    t === 'img' || t === 'video' ||
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

    private fun addStructuredImages(json: String) {
        try {
            val result = org.json.JSONObject(json)
            val images = result.optJSONArray("images")
            if (images != null) {
                for (i in 0 until images.length()) {
                    val image = images.optJSONObject(i) ?: continue
                    val url = image.optString("originalUrl")
                    if (url.startsWith("https://")) {
                        if (isDouyinSession) {
                            synchronized(structuredOtherImages) { structuredOtherImages.add(url) }
                        } else {
                            collectXhsCandidate(
                                url, image.optInt("width"), image.optInt("height"), "structured"
                            )
                        }
                    }
                }
            }

            val video = result.optString("originVideoUrl")
            synchronized(structuredMedia) {
                if (video.startsWith("https://")) structuredMedia.add(video)
                val live = result.optJSONArray("livePhotos")
                if (live != null) {
                    for (i in 0 until live.length()) {
                        val item = live.optJSONObject(i) ?: continue
                        val motion = item.optString("motionUrl")
                        if (motion.startsWith("https://")) structuredMedia.add(motion)
                    }
                }
            }
            handler.post { finalizeXhsResults(allowFallback = true) }
        } catch (_: Exception) {}
    }

    private inner class ResourceBridge {
        @JavascriptInterface
        fun structured(json: String) {
            addStructuredImages(json)
        }

        @JavascriptInterface
        fun add(url: String) {
            collectGenericResource(url, showImmediately = !isXhsSession || xhsFallbackShown)
        }

        @JavascriptInterface
        fun done(count: Int) {
            if (isXhsSession && !xhsFallbackShown) return
            runOnUiThread {
                statusText.text = if (visibleResources.isEmpty()) {
                    "暂未发现资源，可稍后点“重新扫描”"
                } else {
                    "已找到 ${visibleResources.size} 个候选资源"
                }
            }
        }

        @JavascriptInterface
        fun addXhs(url: String, width: Int, height: Int, source: String) {
            collectXhsCandidate(url, width, height, source)
        }

        @JavascriptInterface
        fun doneXhs(count: Int) {
            handler.postDelayed({ finalizeXhsResults(allowFallback = false) }, 120)
        }
    }

    private fun collectGenericResource(url: String, showImmediately: Boolean) {
        if (!isMediaCandidate(url)) return

        synchronized(resources) {
            if (!resources.add(url)) return
        }

        if (!showImmediately) return
        runOnUiThread {
            visibleResources.add(url)
            renderResults()
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
            "image", "img", "photo", "video", "media", "poster",
            "original", "origin", "source", "webp", "avif", "xhscdn"
        ).any { lower.contains(it) }
    }

    private fun isLikelyVideoUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        return listOf(".mp4", ".webm", ".mov", ".m4v", ".m3u8", ".mpd")
            .any { lower.substringBefore('?').contains(it) } ||
            lower.contains("video")
    }

    private fun saveAllResources() {
        val snapshot = visibleResources.toList()
        if (snapshot.isEmpty()) return

        var queued = 0
        snapshot.forEachIndexed { index, url ->
            if (enqueueDownload(url, index, showToast = false)) queued++
        }

        Toast.makeText(
            this,
            "已加入 $queued 个下载任务",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun enqueueDownload(url: String, index: Int, showToast: Boolean): Boolean {
        return try {
            require(url.startsWith("http://") || url.startsWith("https://")) { "该资源不能直接下载" }
            if (android.os.Build.VERSION.SDK_INT <= 28 && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 103)
                error("请允许存储权限后再次下载")
            }
            check(taskStore.all().none { it.status == "cancelled" && ResourceIdentity.of(it.url) == ResourceIdentity.of(url) && SegmentTaskQueue.get(this).isActive(it.id) }) { "正在取消旧任务，请稍后重试" }
            check(!MediaPublisher.saved(this, url)) { "此资源已下载保存" }
            val path = Uri.parse(url).path.orEmpty().lowercase(Locale.US)
            val kind = when { path.endsWith(".mpd") -> "DASH"; path.endsWith(".m3u8") -> "HLS"; else -> "FILE" }
            val raw = Uri.parse(url).lastPathSegment.orEmpty().substringBefore('!')
            val extension = guessExtension(url)
            val safe = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(75).ifBlank { "resource" }
            val basename = safe.substringBeforeLast('.', safe)
            // The URL identity prevents two different resources with the same source name colliding.
            val name = basename + "_" + ResourceIdentity.of(url).take(12) + "." + extension
            val fallbacks = org.json.JSONArray()
            synchronized(xhsCandidates) { imageFallbacks[canonicalXhsKey(url)]?.forEach { candidate ->
                if (candidate != url) fallbacks.put(org.json.JSONObject().put("url", candidate)
                    .put("cookie", CookieManager.getInstance().getCookie(candidate).orEmpty()))
            } }
            taskStore.add(url, kind, sources = fallbacks.toString(), agent = webView.settings.userAgentString.orEmpty(),
                referer = currentPageUrl, cookie = CookieManager.getInstance().getCookie(url).orEmpty(), name = name)
            DownloadService.start(this)
            if (showToast) Toast.makeText(this, "已加入下载队列", Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            if (showToast) Toast.makeText(this, e.message ?: "无法创建下载任务", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun showDownloadTasks() {
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val queue = SegmentTaskQueue.get(this)
        val dialog = android.app.AlertDialog.Builder(this).setTitle("下载任务")
            .setPositiveButton("关闭", null).create()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val scroller = ScrollView(this).apply { addView(root) }
        dialog.setView(scroller)
        val refresh = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                root.removeAllViews()
                val tasks = taskStore.all()
                if (tasks.isEmpty()) root.addView(TextView(this@MainActivity).apply { text = "暂无下载任务" })
                tasks.forEach { task ->
                    var status = task.status
                    var progress = task.progress
                    var detail = task.detail
                    if (task.kind == "SYSTEM" && task.systemId > 0 && status !in listOf("cancelled", "completed")) {
                        try {
                            manager.query(DownloadManager.Query().setFilterById(task.systemId))?.use { cursor ->
                                if (cursor.moveToFirst()) {
                                    val code = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                                    status = when (code) {
                                        DownloadManager.STATUS_SUCCESSFUL -> "completed"
                                        DownloadManager.STATUS_FAILED -> "failed"
                                        DownloadManager.STATUS_RUNNING -> "running"
                                        DownloadManager.STATUS_PAUSED -> "paused"
                                        else -> "queued"
                                    }
                                    val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                                    val done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                                    if (total > 0) progress = (done * 100 / total).toInt().coerceIn(0, 100)
                                    taskStore.update(task.id, status, progress, detail)
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    root.addView(TextView(this@MainActivity).apply {
                        text = "${task.kind} · ${status} · ${progress}%\n${detail}\n${task.url.take(90)}"
                        textSize = 13f
                        setPadding(0, dp(10), 0, dp(4))
                    })
                    val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
                    fun button(label: String, action: () -> Unit) {
                        row.addView(Button(this@MainActivity).apply {
                            text = label
                            isAllCaps = false
                            textSize = 12f
                            minWidth = 0
                            minimumWidth = 0
                            setOnClickListener { action(); handler.post { run() } }
                        }, LinearLayout.LayoutParams(0, dp(44), 1f))
                    }
                    if (task.kind != "SYSTEM" && status in listOf("queued", "running")) {
                        button("暂停") { queue.pause(task.id) }
                        button("取消") { queue.cancel(task.id) }
                    } else if (task.kind != "SYSTEM" && status == "paused") {
                        button("恢复") {
                            if (queue.resume(task.id)) {
                                DownloadService.start(this@MainActivity)
                            } else {
                                Toast.makeText(this@MainActivity, "任务正在停止，请稍后恢复", Toast.LENGTH_SHORT).show()
                            }
                        }
                        button("取消") { queue.cancel(task.id) }
                    } else {
                        if (status != "completed") button("重试") {
                            if (task.kind != "SYSTEM" && queue.resume(task.id)) DownloadService.start(this@MainActivity)
                            else enqueueDownload(task.url, 0, true)
                        }
                        button(if (task.kind == "SYSTEM" && status in listOf("queued", "running", "paused")) "取消" else if (status == "completed") "已保存" else "移除") {
                            if (task.kind == "SYSTEM" && task.systemId > 0 && status in listOf("queued", "running", "paused")) {
                                manager.remove(task.systemId)
                                taskStore.update(task.id, "cancelled", detail = "已取消")
                            } else taskStore.delete(task.id)
                        }
                    }
                    root.addView(row)
                }
                handler.postDelayed(this, 1000L)
            }
        }
        dialog.setOnDismissListener { handler.removeCallbacks(refresh) }
        dialog.show()
        handler.post(refresh)
    }

    private fun guessExtension(url: String): String {
        val lower = url.lowercase(Locale.US)
        return when {
            lower.contains(".png") -> "png"
            lower.contains(".webp") || lower.contains("1080webp") -> "webp"
            lower.contains(".gif") -> "gif"
            lower.contains(".mp3") -> "mp3"
            lower.contains(".m4a") -> "m4a"
            lower.contains(".aac") -> "aac"
            lower.contains(".wav") -> "wav"
            lower.contains(".ogg") -> "ogg"
            lower.contains(".mp4") || lower.contains("video") -> "mp4"
            lower.contains(".mov") -> "mov"
            lower.contains(".webm") -> "webm"
            lower.contains(".m4v") -> "m4v"
            lower.contains(".avif") -> "avif"
            lower.contains(".heic") -> "heic"
            lower.contains(".jpeg") -> "jpeg"
            else -> "jpg"
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("resource url", text))
        Toast.makeText(this, "资源链接已复制", Toast.LENGTH_SHORT).show()
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


