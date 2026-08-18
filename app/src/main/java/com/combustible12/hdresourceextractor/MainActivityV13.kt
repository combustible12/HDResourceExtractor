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
import org.json.JSONObject
import java.util.Locale

/**
 * Clean XHS pipeline:
 * 1) Shared structured parser (same xhs_parser.js that GitHub regression tests execute).
 * 2) Network image/video observation always stays enabled as an independent fallback.
 * 3) Image, caption, cover and original-video state are independent.
 * 4) H5 playback URLs are never labelled or downloaded as the original video.
 */
class MainActivityV13 : AppCompatActivity() {

    private lateinit var urlInput: EditText
    private lateinit var statusText: TextView
    private lateinit var webView: WebView
    private lateinit var resultsContainer: LinearLayout
    private lateinit var resultTitle: TextView
    private lateinit var saveAllButton: Button
    private lateinit var captionCard: LinearLayout
    private lateinit var captionText: TextView

    private val handler = Handler(Looper.getMainLooper())

    // Structured results. These always outrank fallback candidates.
    private val structuredImages = mutableListOf<String>()
    private val parserFallbackImages = mutableListOf<String>()
    private var xhsCaption = ""
    private var xhsCoverUrl = ""
    private var xhsOriginVideoUrl = ""
    private var xhsStructuredType = ""
    private var structuredReady = false

    // Independent network fallback. Never disabled just because structured parsing fails.
    private val networkImages = linkedMapOf<String, String>()
    private val networkImageScores = mutableMapOf<String, Int>()
    private val playbackVideos = linkedSetOf<String>()

    private var currentPageUrl = ""
    private var isXhsSession = false
    private var finalized = false
    private var parserAttempts = 0
    private var parserLastError = ""
    private var pendingSharedText = ""
    private var lastClipboardUrl = ""
    private var clipboardAutofilledUrl = ""

    private val sharedParserSource: String by lazy {
        assets.open("xhs_parser.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

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

    override fun onResume() {
        super.onResume()
        handler.postDelayed({ autoDetectClipboardLink() }, 180)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView.removeJavascriptInterface("HDNative")
        webView.destroy()
        super.onDestroy()
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

        // Keep a normal, full-size WebView. The opaque foreground hides it without changing
        // the viewport Xiaohongshu sees.
        webView = WebView(this).apply { setBackgroundColor(Color.WHITE) }
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
        }

        ViewCompat.setOnApplyWindowInsetsListener(foreground) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                dp(14) + bars.left,
                dp(12) + bars.top,
                dp(14) + bars.right,
                dp(10) + bars.bottom
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
            background = roundedBackground(Color.WHITE, 8, Color.rgb(199, 204, 211))
        }
        foreground.addView(
            urlInput,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
        )

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val openButton = makeMainButton("打开并提取", true).apply {
            setOnClickListener { openAndExtract() }
        }
        val scanButton = makeMainButton("重新扫描", false).apply {
            setOnClickListener { scanCurrentPage() }
        }
        buttonRow.addView(
            openButton,
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(5) }
        )
        buttonRow.addView(
            scanButton,
            LinearLayout.LayoutParams(0, dp(44), 0.54f).apply { leftMargin = dp(5) }
        )
        foreground.addView(
            buttonRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply {
                topMargin = dp(10)
            }
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

        captionCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground(Color.WHITE, 10)
            visibility = View.GONE
        }
        val captionHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        captionHeader.addView(
            TextView(this).apply {
                text = "笔记文案"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(36, 36, 36))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        captionHeader.addView(
            Button(this).apply {
                text = "复制文案"
                isAllCaps = false
                textSize = 13f
                setTextColor(Color.rgb(41, 156, 235))
                minHeight = 0
                minimumHeight = 0
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(12), 0, dp(12), 0)
                background = roundedBackground(Color.rgb(239, 248, 255), 8)
                setOnClickListener {
                    if (xhsCaption.isNotBlank()) copyToClipboard(xhsCaption, "文案已复制")
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36))
        )
        captionCard.addView(captionHeader)
        captionText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(55, 55, 55))
            setLineSpacing(0f, 1.18f)
            maxLines = 5
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(8), 0, 0)
        }
        captionCard.addView(captionText)
        foreground.addView(
            captionCard,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        )

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
            setOnClickListener { saveAllVisibleResources() }
        }
        resultHeader.addView(
            saveAllButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38))
        )
        foreground.addView(
            resultHeader,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        )

        resultsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(
                resultsContainer,
                ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        foreground.addView(
            scroll,
            LinearLayout.LayoutParams(0, 0).also {
                it.width = ViewGroup.LayoutParams.MATCH_PARENT
                it.height = 0
                it.weight = 1f
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
        renderResults()
    }

    private fun makeMainButton(label: String, primary: Boolean): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        setTextColor(if (primary) Color.rgb(34, 34, 34) else Color.rgb(55, 55, 55))
        background = roundedBackground(
            if (primary) Color.rgb(236, 240, 246) else Color.rgb(247, 248, 250),
            7,
            Color.rgb(198, 203, 210)
        )
    }

    @Suppress("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            loadsImagesAutomatically = true
            loadWithOverviewMode = true
            useWideViewPort = true
            // Keep Android WebView identity. v0.9 was able to load XHS content with this path.
            userAgentString = "$userAgentString HDResourceExtractor/0.13"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.addJavascriptInterface(NativeBridge(), "HDNative")

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
                request?.url?.toString()?.let(::observeNetworkResource)
                return super.shouldInterceptRequest(view, request)
            }

            @Suppress("DEPRECATION")
            override fun shouldInterceptRequest(
                view: WebView?,
                url: String?
            ): WebResourceResponse? {
                url?.let(::observeNetworkResource)
                return super.shouldInterceptRequest(view, url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!url.isNullOrBlank() && url != "about:blank") currentPageUrl = url
                if (isXhsSession) scheduleStructuredScans()
            }
        }
    }

    private fun shouldBlockNavigation(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        return try {
            when (Uri.parse(raw).scheme?.lowercase(Locale.US).orEmpty()) {
                "http", "https", "about" -> false
                "xhsdiscover", "xiaohongshu", "intent" -> {
                    if (isXhsSession) {
                        // Do not rewrite the route. Keep the already-loaded HTTP page and continue
                        // parsing/observing it. This avoids the v0.12 regression.
                        runOnUiThread {
                            statusText.text = "已拦截 App 跳转，继续读取当前笔记页面…"
                        }
                        handler.postDelayed({ runSharedParser() }, 120)
                        handler.postDelayed({ runSharedParser() }, 900)
                        handler.postDelayed({ finalizeXhs() }, 2400)
                    }
                    true
                }
                else -> true
            }
        } catch (_: Exception) {
            true
        }
    }

    private fun scheduleStructuredScans() {
        statusText.text = "正在读取小红书笔记数据…"
        runSharedParser()
        handler.postDelayed({ runSharedParser() }, 700)
        handler.postDelayed({ runSharedParser() }, 1800)
        handler.postDelayed({ runSharedParser() }, 3600)
        handler.postDelayed({ runSharedParser() }, 5600)
        handler.postDelayed({ finalizeXhs() }, 6800)
    }

    private fun runSharedParser() {
        if (!isXhsSession) return
        parserAttempts += 1
        val script = buildString {
            append(sharedParserSource)
            append("\n;(function(){try{")
            append("var r=window.HDXhsParser.extract();")
            append("HDNative.onParsed(JSON.stringify(r));")
            append("}catch(e){HDNative.onParserError(String((e&&e.stack)||e));}})();")
        }
        webView.evaluateJavascript(script, null)
    }

    private inner class NativeBridge {
        @JavascriptInterface
        fun onParsed(json: String) {
            try {
                val parsed = JSONObject(json)
                val ok = parsed.optBoolean("ok", false)
                val type = parsed.optString("type")
                val caption = parsed.optString("caption").trim()

                val exactImages = mutableListOf<String>()
                val images = parsed.optJSONArray("images")
                if (images != null) {
                    for (i in 0 until images.length()) {
                        val obj = images.optJSONObject(i) ?: continue
                        imageUrlFromParserObject(obj)?.let(exactImages::add)
                    }
                }

                val cover = parsed.optJSONObject("cover")?.let(::imageUrlFromParserObject).orEmpty()
                val origin = cleanHttpUrl(parsed.optString("originVideoUrl")).orEmpty()

                val playback = mutableListOf<String>()
                parsed.optJSONArray("playbackUrls")?.let { array ->
                    for (i in 0 until array.length()) {
                        cleanHttpUrl(array.optString(i))?.let(playback::add)
                    }
                }

                runOnUiThread {
                    if (caption.isNotBlank()) xhsCaption = caption
                    if (ok) {
                        structuredReady = true
                        xhsStructuredType = type
                        if (type == "image") {
                            structuredImages.clear()
                            structuredImages.addAll(exactImages.distinct())
                        }
                        if (type == "video") {
                            if (cover.isNotBlank()) xhsCoverUrl = cover
                            if (origin.isNotBlank()) xhsOriginVideoUrl = origin
                        }
                    } else if (exactImages.isNotEmpty()) {
                        // DOM fallback returned by the same parser. Keep it separate from exact state.
                        parserFallbackImages.clear()
                        parserFallbackImages.addAll(exactImages.distinct())
                    }
                    playbackVideos.addAll(playback)
                    renderResults()
                    updateStatus(final = false)
                }
            } catch (e: Exception) {
                onParserError(e.message ?: "解析结果 JSON 无效")
            }
        }

        @JavascriptInterface
        fun onParserError(message: String) {
            parserLastError = message.take(500)
        }
    }

    private fun imageUrlFromParserObject(obj: JSONObject): String? {
        cleanHttpUrl(obj.optString("originalUrl"))?.let { return it }
        val display = obj.optJSONArray("displayUrls") ?: return null
        for (i in 0 until display.length()) {
            cleanHttpUrl(display.optString(i))?.let { return it }
        }
        return null
    }

    private fun observeNetworkResource(raw: String) {
        if (!isXhsSession) return
        val url = cleanHttpUrl(raw) ?: return
        val lower = url.lowercase(Locale.US)
        val host = try { Uri.parse(url).host?.lowercase(Locale.US).orEmpty() } catch (_: Exception) { "" }

        if (host.endsWith("xhscdn.com") || host == "ci.xiaohongshu.com") {
            if (isLikelyPlaybackVideo(lower)) {
                synchronized(playbackVideos) { playbackVideos.add(url) }
                return
            }
            collectNetworkImage(url)
        }
    }

    private fun isLikelyPlaybackVideo(lowerUrl: String): Boolean =
        lowerUrl.contains("sns-video") ||
            lowerUrl.contains(".mp4") ||
            lowerUrl.contains(".m3u8") ||
            lowerUrl.contains("/video/")

    private fun collectNetworkImage(raw: String) {
        val lower = raw.lowercase(Locale.US)
        if (lower.contains("avatar") || lower.contains("emoji") || lower.contains("icon") ||
            lower.contains("logo") || lower.contains("qrcode")
        ) return

        val host = try { Uri.parse(raw).host?.lowercase(Locale.US).orEmpty() } catch (_: Exception) { "" }
        val likely = host == "ci.xiaohongshu.com" ||
            host.contains("sns-webpic") || host.contains("sns-img") ||
            lower.contains("!h5_") || lower.contains("/1040g")
        if (!likely) return

        val original = xhsOriginalImageUrl(raw)
        val key = canonicalImageKey(original)
        if (key.isBlank()) return

        var score = 0
        if (host == "ci.xiaohongshu.com") score += 500
        if (host.contains("sns-webpic") || host.contains("sns-img")) score += 300
        if (lower.contains("/1040g")) score += 240
        if (lower.contains("!h5_")) score += 160
        if (original.startsWith("https://ci.xiaohongshu.com/")) score += 350

        synchronized(networkImages) {
            val old = networkImageScores[key] ?: Int.MIN_VALUE
            if (score > old) {
                networkImages[key] = original
                networkImageScores[key] = score
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
                val ti = segments.indexOfFirst { it.matches(Regex("[0-9]{12}")) }
                if (ti >= 0 && ti + 2 < segments.size &&
                    segments[ti + 1].matches(Regex("[0-9a-fA-F]{32}"))
                ) {
                    val payload = segments.drop(ti + 2).toMutableList()
                    if (payload.isNotEmpty()) {
                        payload[payload.lastIndex] = payload.last().substringBefore('!').substringBefore('?')
                        if (payload.last().isNotBlank()) {
                            "https://ci.xiaohongshu.com/" + payload.joinToString("/")
                        } else url
                    } else url
                } else url
            } else url
        } catch (_: Exception) {
            url
        }
    }

    private fun canonicalImageKey(url: String): String {
        val clean = url.substringBefore('#').substringBefore('?').substringBefore('!')
        return try {
            val last = Uri.parse(clean).lastPathSegment.orEmpty()
            if (last.length >= 18) last else clean
        } catch (_: Exception) {
            clean
        }
    }

    private fun currentImages(): List<String> {
        if (structuredImages.isNotEmpty()) return structuredImages.toList()
        if (parserFallbackImages.isNotEmpty()) return parserFallbackImages.toList()
        if (!finalized) return emptyList()
        return synchronized(networkImages) {
            networkImages.entries
                .sortedByDescending { networkImageScores[it.key] ?: 0 }
                .map { it.value }
                .distinct()
                .take(20)
        }
    }

    private fun finalizeXhs() {
        if (!isXhsSession) return
        finalized = true
        runOnUiThread {
            renderResults()
            updateStatus(final = true)
        }
    }

    private fun updateStatus(final: Boolean) {
        val images = currentImages()
        val playbackCount = synchronized(playbackVideos) { playbackVideos.size }
        statusText.text = when {
            xhsOriginVideoUrl.isNotBlank() ->
                "已读取 1 个小红书无水印原视频${if (xhsCoverUrl.isNotBlank()) " · 封面图已保留" else ""}"
            xhsStructuredType == "video" ->
                "已识别视频笔记，但暂未读取到无水印原视频${if (playbackCount > 0) " · 已检测到播放流" else ""}"
            structuredImages.isNotEmpty() ->
                "已识别 ${structuredImages.size} 张小红书无水印原图"
            parserFallbackImages.isNotEmpty() ->
                "结构化数据未就绪，已从正文 DOM 恢复 ${parserFallbackImages.size} 张图片"
            final && images.isNotEmpty() ->
                "结构化数据未就绪，已从网络资源恢复 ${images.size} 张候选图片"
            !final -> "正在读取小红书笔记数据…"
            parserLastError.isNotBlank() -> "暂未读取到笔记结构；网络候选也为空"
            else -> "暂未读取到笔记资源"
        }
    }

    private fun renderResults() {
        if (!::resultsContainer.isInitialized) return
        resultsContainer.removeAllViews()

        captionCard.visibility = if (xhsCaption.isBlank()) View.GONE else View.VISIBLE
        if (xhsCaption.isNotBlank()) captionText.text = xhsCaption

        val images = currentImages()
        val hasVideo = xhsOriginVideoUrl.isNotBlank()
        val hasCover = xhsCoverUrl.isNotBlank()
        val playbackOnly = !hasVideo && synchronized(playbackVideos) { playbackVideos.isNotEmpty() }

        resultTitle.text = when {
            hasVideo -> "无水印视频（1）${if (hasCover) " · 封面图（1）" else ""}"
            images.isNotEmpty() -> "无水印原图（${images.size}）"
            hasCover -> "封面图（1）"
            else -> "提取结果"
        }

        saveAllButton.visibility = if (hasVideo || hasCover || images.isNotEmpty()) View.VISIBLE else View.GONE
        saveAllButton.text = when {
            hasVideo && hasCover -> "保存视频+封面"
            hasVideo -> "保存视频"
            images.isNotEmpty() -> "保存全部图片"
            else -> "保存封面"
        }

        if (hasVideo) {
            resultsContainer.addView(makeVideoCard())
            if (hasCover) {
                addSectionLabel("封面图")
                resultsContainer.addView(makeImageCard(xhsCoverUrl, 0, true))
            }
            return
        }

        if (images.isNotEmpty()) {
            addImageGrid(images)
            return
        }

        if (playbackOnly && xhsStructuredType == "video") {
            resultsContainer.addView(
                makeMessageCard("检测到视频播放流，但没有取得无水印原视频源。播放流不会冒充原视频。")
            )
            if (hasCover) {
                addSectionLabel("封面图")
                resultsContainer.addView(makeImageCard(xhsCoverUrl, 0, true))
            }
            return
        }

        resultsContainer.addView(
            makeMessageCard(if (finalized) "没有读取到可用资源" else "正在解析…")
        )
    }

    private fun addSectionLabel(text: String) {
        resultsContainer.addView(
            TextView(this).apply {
                this.text = text
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(45, 45, 45))
                setPadding(0, dp(12), 0, dp(8))
            }
        )
    }

    private fun addImageGrid(images: List<String>) {
        var index = 0
        while (index < images.size) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (column in 0..1) {
                val itemIndex = index + column
                if (itemIndex < images.size) {
                    row.addView(
                        makeImageCard(images[itemIndex], itemIndex, false),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (column == 0) rightMargin = dp(5) else leftMargin = dp(5)
                        }
                    )
                } else {
                    row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                }
            }
            resultsContainer.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            )
            index += 2
        }
    }

    private fun makeVideoCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(Color.WHITE, 10)
            setPadding(0, 0, 0, dp(4))

            val preview = ImageView(this@MainActivityV13).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.rgb(25, 25, 25))
                if (xhsCoverUrl.isNotBlank()) load(xhsCoverUrl) { crossfade(true) }
            }
            addView(
                preview,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220))
            )
            addView(
                TextView(this@MainActivityV13).apply {
                    text = "无水印原视频"
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.rgb(40, 40, 40))
                    setPadding(dp(12), dp(10), dp(12), dp(4))
                }
            )
            addView(makeResourceButton("下载视频") { downloadOriginalVideo() })
            addView(makeResourceButton("复制视频链接") {
                copyToClipboard(xhsOriginVideoUrl, "视频链接已复制")
            })
        }
    }

    private fun makeImageCard(url: String, index: Int, isCover: Boolean): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(Color.WHITE, 10)
            val image = ImageView(this@MainActivityV13).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.rgb(235, 237, 240))
                load(url) { crossfade(true) }
            }
            addView(
                image,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (isCover) 220 else 190))
            )
            addView(makeResourceButton(if (isCover) "下载封面图" else "下载图片") {
                if (enqueueImageDownload(url, index, isCover)) {
                    Toast.makeText(this@MainActivityV13, "已加入下载队列", Toast.LENGTH_SHORT).show()
                }
            })
            addView(makeResourceButton(if (isCover) "复制封面链接" else "复制图片链接") {
                copyToClipboard(url, "链接已复制")
            })
        }
    }

    private fun makeResourceButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setTextColor(Color.rgb(55, 55, 55))
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(12), 0, dp(12), 0)
        background = roundedBackground(Color.WHITE, 0, Color.rgb(235, 237, 240))
        setOnClickListener { action() }
    }

    private fun makeMessageCard(message: String): TextView = TextView(this).apply {
        text = message
        gravity = Gravity.CENTER
        textSize = 14f
        setTextColor(Color.rgb(150, 150, 150))
        background = roundedBackground(Color.WHITE, 10)
        setPadding(dp(20), dp(34), dp(20), dp(34))
    }

    private fun openAndExtract() {
        val raw = urlInput.text?.toString()?.trim().orEmpty()
        val url = extractFirstUrl(raw)
        if (url == null) {
            Toast.makeText(this, "没有找到有效链接", Toast.LENGTH_SHORT).show()
            return
        }
        resetExtractionState()
        currentPageUrl = url
        isXhsSession = isXhsUrl(url)
        urlInput.setText(url)
        statusText.text = if (isXhsSession) "正在打开小红书笔记…" else "正在打开网页…"
        webView.loadUrl(url)
    }

    private fun scanCurrentPage() {
        if (currentPageUrl.isBlank()) {
            openAndExtract()
            return
        }
        finalized = false
        statusText.text = "重新读取当前页面…"
        if (isXhsSession) {
            runSharedParser()
            handler.postDelayed({ runSharedParser() }, 800)
            handler.postDelayed({ finalizeXhs() }, 2600)
        }
    }

    private fun resetExtractionState() {
        handler.removeCallbacksAndMessages(null)
        structuredImages.clear()
        parserFallbackImages.clear()
        synchronized(networkImages) {
            networkImages.clear()
            networkImageScores.clear()
        }
        synchronized(playbackVideos) { playbackVideos.clear() }
        xhsCaption = ""
        xhsCoverUrl = ""
        xhsOriginVideoUrl = ""
        xhsStructuredType = ""
        structuredReady = false
        finalized = false
        parserAttempts = 0
        parserLastError = ""
        captionCard.visibility = View.GONE
        renderResults()
    }

    private fun downloadOriginalVideo() {
        val url = xhsOriginVideoUrl
        if (url.isBlank()) {
            Toast.makeText(this, "没有无水印原视频源", Toast.LENGTH_SHORT).show()
            return
        }
        val cookies = mapOf(url to CookieManager.getInstance().getCookie(url).orEmpty())
        statusText.text = "正在下载并验证无水印原视频…"
        XhsVideoDownloader(this).download(
            candidates = listOf(url),
            userAgent = webView.settings.userAgentString.orEmpty(),
            referer = currentPageUrl,
            cookies = cookies
        ) { result ->
            runOnUiThread {
                statusText.text = result.message
                Toast.makeText(
                    this,
                    result.message,
                    if (result.success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun saveAllVisibleResources() {
        var queued = 0
        if (xhsOriginVideoUrl.isNotBlank()) {
            downloadOriginalVideo()
            queued += 1
        }
        if (xhsCoverUrl.isNotBlank()) {
            if (enqueueImageDownload(xhsCoverUrl, 0, true)) queued += 1
        } else {
            currentImages().forEachIndexed { index, url ->
                if (enqueueImageDownload(url, index, false)) queued += 1
            }
        }
        if (queued > 0) {
            Toast.makeText(this, "已开始保存 $queued 个资源", Toast.LENGTH_SHORT).show()
        }
    }

    private fun enqueueImageDownload(url: String, index: Int, isCover: Boolean): Boolean {
        return try {
            val fileName = if (isCover) {
                "xhs_cover_${System.currentTimeMillis()}.jpg"
            } else {
                "xhs_image_${index + 1}_${System.currentTimeMillis()}.jpg"
            }
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(fileName)
                .setDescription("HDResourceExtractor")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_PICTURES,
                    "HDResourceExtractor/$fileName"
                )
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            webView.settings.userAgentString?.takeIf { it.isNotBlank() }?.let {
                request.addRequestHeader("User-Agent", it)
            }
            if (currentPageUrl.isNotBlank()) request.addRequestHeader("Referer", currentPageUrl)
            CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let {
                request.addRequestHeader("Cookie", it)
            }
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        pendingSharedText = shared
        val url = extractFirstUrl(shared)
        if (url != null) {
            urlInput.setText(url)
            handler.postDelayed({ openAndExtract() }, 120)
        }
    }

    private fun autoDetectClipboardLink() {
        if (!::urlInput.isInitialized) return
        val detected = extractFirstUrl(readClipboardText()) ?: return
        if (detected == lastClipboardUrl) return
        val existing = urlInput.text?.toString()?.trim().orEmpty()
        if (existing.isNotBlank() && existing != clipboardAutofilledUrl) return
        lastClipboardUrl = detected
        clipboardAutofilledUrl = detected
        urlInput.setText(detected)
        statusText.text = "已检测到剪贴板链接，点“打开并提取”"
    }

    private fun readClipboardText(): String {
        return try {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractFirstUrl(text: String): String? {
        return Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
            .find(text)
            ?.value
            ?.trimEnd('。', '，', ',', '.', ')', '）', ']', '】', '}', '；', ';')
    }

    private fun isXhsUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        return lower.contains("xhslink.cn") || lower.contains("xiaohongshu.com")
    }

    private fun cleanHttpUrl(raw: String): String? {
        var value = raw
            .replace("\\u002F", "/", ignoreCase = true)
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .trim()
        if (value.startsWith("//")) value = "https:$value"
        if (value.startsWith("http://")) value = "https://" + value.removePrefix("http://")
        return value.takeIf { it.startsWith("https://") }
    }

    private fun copyToClipboard(text: String, toast: String) {
        if (text.isBlank()) return
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("HDResourceExtractor", text))
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
    }
}
