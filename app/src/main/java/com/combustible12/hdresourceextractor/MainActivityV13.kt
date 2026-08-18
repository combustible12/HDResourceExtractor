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
import coil.load
import org.json.JSONObject
import java.util.Locale

/**
 * v0.13 clean pipeline.
 *
 * Images, caption and original video are deliberately independent:
 * - the shared JS parser returns structured note data;
 * - network observation is always kept as an image fallback;
 * - playback video URLs are observed only for diagnostics and never masquerade as original;
 * - the only downloadable "original video" is originVideoKey -> sns-video-bd.xhscdn.com.
 */
class MainActivityV13 : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlInput: EditText
    private lateinit var statusText: TextView
    private lateinit var captionBox: LinearLayout
    private lateinit var captionText: TextView
    private lateinit var resultTitle: TextView
    private lateinit var saveAllButton: Button
    private lateinit var resultList: LinearLayout

    private val handler = Handler(Looper.getMainLooper())

    private val structuredImages = mutableListOf<String>()
    private val domFallbackImages = mutableListOf<String>()
    private val networkImages = linkedMapOf<String, String>()
    private val networkImageScores = mutableMapOf<String, Int>()
    private val playbackVideos = linkedSetOf<String>()

    private var caption = ""
    private var coverUrl = ""
    private var originVideoUrl = ""
    private var structuredType = ""
    private var currentPageUrl = ""
    private var xhsSession = false
    private var finalized = false
    private var lastClipboardUrl = ""
    private var clipboardAutofillUrl = ""

    private val parserSource: String by lazy {
        assets.open("xhs_parser.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        configureWebView()
        consumeShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeShare(intent)
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed({ detectClipboardLink() }, 180)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("HDNative")
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            stroke?.let { setStroke(dp(1), it) }
        }

    private fun buildUi() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(247, 248, 250))
        }

        // Full-size WebView is kept behind an opaque foreground. This preserves the mobile
        // viewport while removing the webpage preview from the app UI.
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
            setPadding(dp(14), dp(18), dp(14), dp(12))
            setBackgroundColor(Color.rgb(247, 248, 250))
        }

        foreground.addView(
            TextView(this).apply {
                text = "HDResourceExtractor"
                textSize = 21f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(31, 31, 31))
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        )

        urlInput = EditText(this).apply {
            hint = "粘贴网页链接"
            isSingleLine = true
            textSize = 15f
            setPadding(dp(12), 0, dp(12), 0)
            setTextColor(Color.rgb(35, 35, 35))
            setHintTextColor(Color.rgb(145, 150, 156))
            background = rounded(Color.WHITE, 8, Color.rgb(199, 204, 211))
        }
        foreground.addView(
            urlInput,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
        )

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        buttons.addView(
            actionButton("打开并提取", true) { openAndExtract() },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(5) }
        )
        buttons.addView(
            actionButton("重新扫描", false) { rescan() },
            LinearLayout.LayoutParams(0, dp(44), 0.56f).apply { leftMargin = dp(5) }
        )
        foreground.addView(
            buttons,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply {
                topMargin = dp(10)
            }
        )

        statusText = TextView(this).apply {
            text = "等待网页链接"
            textSize = 13f
            setTextColor(Color.rgb(95, 95, 95))
        }
        foreground.addView(
            statusText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
        )

        captionBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(Color.WHITE, 10)
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
                setTextColor(Color.rgb(38, 38, 38))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        captionHeader.addView(
            miniButton("复制文案") {
                if (caption.isNotBlank()) copyText(caption, "文案已复制")
            }
        )
        captionBox.addView(captionHeader)
        captionText = TextView(this).apply {
            textSize = 14f
            maxLines = 5
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(Color.rgb(55, 55, 55))
            setPadding(0, dp(8), 0, 0)
        }
        captionBox.addView(captionText)
        foreground.addView(
            captionBox,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
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
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(Color.rgb(41, 156, 235), 8)
            visibility = View.GONE
            setOnClickListener { saveAll() }
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

        resultList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(
                resultList,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        foreground.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        root.addView(
            foreground,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)
        render()
    }

    private fun actionButton(label: String, primary: Boolean, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setTextColor(Color.rgb(45, 45, 45))
            background = rounded(
                if (primary) Color.rgb(236, 240, 246) else Color.rgb(247, 248, 250),
                7,
                Color.rgb(198, 203, 210)
            )
            setOnClickListener { action() }
        }

    private fun miniButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        setTextColor(Color.rgb(41, 156, 235))
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(10), 0, dp(10), 0)
        background = rounded(Color.rgb(239, 248, 255), 7)
        setOnClickListener { action() }
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
            userAgentString = "$userAgentString HDResourceExtractor/0.13"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.addJavascriptInterface(NativeBridge(), "HDNative")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean = blockAppNavigation(request?.url?.toString())

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                blockAppNavigation(url)

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
                if (xhsSession) scheduleParser()
            }
        }
    }

    private fun blockAppNavigation(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        return try {
            when (Uri.parse(raw).scheme?.lowercase(Locale.US).orEmpty()) {
                "http", "https", "about" -> false
                "xhsdiscover", "xiaohongshu", "intent" -> {
                    if (xhsSession) {
                        runOnUiThread {
                            statusText.text = "已拦截 App 跳转，继续读取当前笔记页面…"
                        }
                        handler.postDelayed({ runParser() }, 100)
                        handler.postDelayed({ runParser() }, 800)
                        handler.postDelayed({ finalizeResults() }, 2600)
                    }
                    true
                }
                else -> true
            }
        } catch (_: Exception) {
            true
        }
    }

    private fun scheduleParser() {
        statusText.text = "正在读取小红书笔记数据…"
        runParser()
        handler.postDelayed({ runParser() }, 700)
        handler.postDelayed({ runParser() }, 1800)
        handler.postDelayed({ runParser() }, 3600)
        handler.postDelayed({ runParser() }, 5600)
        handler.postDelayed({ finalizeResults() }, 6800)
    }

    private fun runParser() {
        if (!xhsSession) return
        val script = buildString {
            append(parserSource)
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
                val result = JSONObject(json)
                val ok = result.optBoolean("ok", false)
                val type = result.optString("type")
                val newCaption = result.optString("caption").trim()
                val exactImages = mutableListOf<String>()

                result.optJSONArray("images")?.let { array ->
                    for (i in 0 until array.length()) {
                        parserImageUrl(array.optJSONObject(i))?.let(exactImages::add)
                    }
                }

                val newCover = parserImageUrl(result.optJSONObject("cover")).orEmpty()
                val newOrigin = cleanHttpUrl(result.optString("originVideoUrl")).orEmpty()
                val playback = mutableListOf<String>()
                result.optJSONArray("playbackUrls")?.let { array ->
                    for (i in 0 until array.length()) {
                        cleanHttpUrl(array.optString(i))?.let(playback::add)
                    }
                }

                runOnUiThread {
                    if (newCaption.isNotBlank()) caption = newCaption
                    if (ok) {
                        structuredType = type
                        if (type == "image" && exactImages.isNotEmpty()) {
                            structuredImages.clear()
                            structuredImages.addAll(exactImages.distinct())
                        }
                        if (type == "video") {
                            if (newCover.isNotBlank()) coverUrl = newCover
                            if (newOrigin.isNotBlank()) originVideoUrl = newOrigin
                        }
                    } else if (exactImages.isNotEmpty()) {
                        domFallbackImages.clear()
                        domFallbackImages.addAll(exactImages.distinct())
                    }
                    synchronized(playbackVideos) { playbackVideos.addAll(playback) }
                    render()
                    updateStatus(false)
                }
            } catch (e: Exception) {
                onParserError(e.message ?: "解析结果无效")
            }
        }

        @JavascriptInterface
        fun onParserError(message: String) {
            runOnUiThread {
                if (!finalized) statusText.text = "结构化解析暂未就绪，网络兜底仍在继续…"
            }
        }
    }

    private fun parserImageUrl(obj: JSONObject?): String? {
        if (obj == null) return null
        cleanHttpUrl(obj.optString("originalUrl"))?.let { return it }
        obj.optJSONArray("displayUrls")?.let { array ->
            for (i in 0 until array.length()) {
                cleanHttpUrl(array.optString(i))?.let { return it }
            }
        }
        return null
    }

    private fun observeNetworkResource(raw: String) {
        if (!xhsSession) return
        val url = cleanHttpUrl(raw) ?: return
        val host = try { Uri.parse(url).host?.lowercase(Locale.US).orEmpty() } catch (_: Exception) { "" }
        if (!host.endsWith("xhscdn.com") && host != "ci.xiaohongshu.com") return

        val lower = url.lowercase(Locale.US)
        if (lower.contains("sns-video") || lower.contains(".mp4") ||
            lower.contains(".m3u8") || lower.contains("/video/")
        ) {
            synchronized(playbackVideos) { playbackVideos.add(url) }
            return
        }

        if (lower.contains("avatar") || lower.contains("emoji") || lower.contains("icon") ||
            lower.contains("logo") || lower.contains("qrcode")
        ) return

        val likelyImage = host == "ci.xiaohongshu.com" ||
            host.contains("sns-webpic") || host.contains("sns-img") ||
            lower.contains("!h5_") || lower.contains("/1040g")
        if (!likelyImage) return

        val original = originalImageUrl(url)
        val key = imageKey(original)
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

    private fun originalImageUrl(url: String): String {
        return try {
            val uri = Uri.parse(url)
            val host = uri.host?.lowercase(Locale.US).orEmpty()
            if (host == "ci.xiaohongshu.com") {
                url.substringBefore('?').substringBefore('#')
            } else if (host.endsWith("xhscdn.com")) {
                val segments = uri.pathSegments
                val timeIndex = segments.indexOfFirst { it.matches(Regex("[0-9]{12}")) }
                if (timeIndex >= 0 && timeIndex + 2 < segments.size &&
                    segments[timeIndex + 1].matches(Regex("[0-9a-fA-F]{32}"))
                ) {
                    val payload = segments.drop(timeIndex + 2).toMutableList()
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

    private fun imageKey(url: String): String {
        val clean = url.substringBefore('#').substringBefore('?').substringBefore('!')
        return try {
            val last = Uri.parse(clean).lastPathSegment.orEmpty()
            if (last.length >= 18) last else clean
        } catch (_: Exception) {
            clean
        }
    }

    private fun visibleImages(): List<String> {
        if (structuredImages.isNotEmpty()) return structuredImages.toList()
        if (domFallbackImages.isNotEmpty()) return domFallbackImages.toList()
        if (!finalized) return emptyList()
        return synchronized(networkImages) {
            networkImages.entries
                .sortedByDescending { networkImageScores[it.key] ?: 0 }
                .map { it.value }
                .distinct()
                .take(20)
        }
    }

    private fun finalizeResults() {
        if (!xhsSession) return
        finalized = true
        runOnUiThread {
            render()
            updateStatus(true)
        }
    }

    private fun updateStatus(final: Boolean) {
        val images = visibleImages()
        val playbackCount = synchronized(playbackVideos) { playbackVideos.size }
        statusText.text = when {
            originVideoUrl.isNotBlank() ->
                "已读取 1 个小红书无水印原视频${if (coverUrl.isNotBlank()) " · 封面图已保留" else ""}"
            structuredType == "video" ->
                "已识别视频笔记，但未读取到无水印原视频${if (playbackCount > 0) " · 播放流仅作兜底诊断" else ""}"
            structuredImages.isNotEmpty() ->
                "已识别 ${structuredImages.size} 张小红书无水印原图"
            domFallbackImages.isNotEmpty() ->
                "结构化数据未就绪，已从正文恢复 ${domFallbackImages.size} 张图片"
            final && images.isNotEmpty() ->
                "结构化数据未就绪，已从网络恢复 ${images.size} 张候选图片"
            final -> "暂未读取到笔记资源"
            else -> "正在读取小红书笔记数据…"
        }
    }

    private fun render() {
        if (!::resultList.isInitialized) return
        resultList.removeAllViews()

        captionBox.visibility = if (caption.isBlank()) View.GONE else View.VISIBLE
        if (caption.isNotBlank()) captionText.text = caption

        val images = visibleImages()
        val hasVideo = originVideoUrl.isNotBlank()
        val hasCover = coverUrl.isNotBlank()

        resultTitle.text = when {
            hasVideo && hasCover -> "无水印视频（1） · 封面图（1）"
            hasVideo -> "无水印视频（1）"
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

        when {
            hasVideo -> {
                resultList.addView(videoCard())
                if (hasCover) {
                    section("封面图")
                    resultList.addView(imageCard(coverUrl, 0, true))
                }
            }
            images.isNotEmpty() -> addImageGrid(images)
            structuredType == "video" && synchronized(playbackVideos) { playbackVideos.isNotEmpty() } -> {
                resultList.addView(messageCard("检测到视频播放流，但没有取得无水印原视频源。播放流不会冒充原视频。"))
                if (hasCover) {
                    section("封面图")
                    resultList.addView(imageCard(coverUrl, 0, true))
                }
            }
            else -> resultList.addView(messageCard(if (finalized) "没有读取到可用资源" else "正在解析…"))
        }
    }

    private fun section(label: String) {
        resultList.addView(
            TextView(this).apply {
                text = label
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
                        imageCard(images[itemIndex], itemIndex, false),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (column == 0) rightMargin = dp(5) else leftMargin = dp(5)
                        }
                    )
                } else {
                    row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                }
            }
            resultList.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            )
            index += 2
        }
    }

    private fun videoCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Color.WHITE, 10)
        val preview = ImageView(this@MainActivityV13).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(28, 28, 28))
            if (coverUrl.isNotBlank()) load(coverUrl) { crossfade(true) }
        }
        addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))

        val actions = LinearLayout(this@MainActivityV13).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        actions.addView(
            resourceButton("下载视频", true) { downloadOriginalVideo() },
            LinearLayout.LayoutParams(0, dp(54), 1f).apply { rightMargin = dp(5) }
        )
        actions.addView(
            resourceButton("复制链接", false) { copyText(originVideoUrl, "视频链接已复制") },
            LinearLayout.LayoutParams(0, dp(54), 1f).apply { leftMargin = dp(5) }
        )
        addView(
            actions,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
                leftMargin = dp(8)
                rightMargin = dp(8)
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
        )
    }

    private fun imageCard(url: String, index: Int, isCover: Boolean): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 10)
            addView(
                ImageView(this@MainActivityV13).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(Color.rgb(235, 237, 240))
                    load(url) { crossfade(true) }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(if (isCover) 220 else 190)
                )
            )

            if (isCover) {
                val actions = LinearLayout(this@MainActivityV13).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                actions.addView(
                    resourceButton("保存封面", true) {
                        if (enqueueImage(url, index, true)) {
                            Toast.makeText(this@MainActivityV13, "封面已加入下载队列", Toast.LENGTH_SHORT).show()
                        }
                    },
                    LinearLayout.LayoutParams(0, dp(54), 1f).apply { rightMargin = dp(5) }
                )
                actions.addView(
                    resourceButton("复制链接", false) { copyText(url, "封面链接已复制") },
                    LinearLayout.LayoutParams(0, dp(54), 1f).apply { leftMargin = dp(5) }
                )
                addView(
                    actions,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
                        leftMargin = dp(8)
                        rightMargin = dp(8)
                        topMargin = dp(8)
                        bottomMargin = dp(8)
                    }
                )
            } else {
                addView(
                    resourceButton("下载图片", true) {
                        if (enqueueImage(url, index, false)) {
                            Toast.makeText(this@MainActivityV13, "已加入下载队列", Toast.LENGTH_SHORT).show()
                        }
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
                        leftMargin = dp(6)
                        rightMargin = dp(6)
                        topMargin = dp(6)
                    }
                )
                addView(
                    resourceButton("复制图片链接", false) { copyText(url, "链接已复制") },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
                        leftMargin = dp(6)
                        rightMargin = dp(6)
                        topMargin = dp(5)
                        bottomMargin = dp(6)
                    }
                )
            }
        }

    private fun resourceButton(label: String, primary: Boolean, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            gravity = Gravity.CENTER
            minHeight = dp(48)
            minimumHeight = dp(48)
            minWidth = 0
            minimumWidth = 0
            setTextColor(if (primary) Color.WHITE else Color.rgb(55, 55, 55))
            setPadding(dp(12), 0, dp(12), 0)
            background = if (primary) {
                rounded(Color.rgb(41, 156, 235), 8)
            } else {
                rounded(Color.rgb(247, 248, 250), 8, Color.rgb(210, 214, 220))
            }
            setOnClickListener { action() }
        }

    private fun messageCard(message: String): TextView = TextView(this).apply {
        text = message
        gravity = Gravity.CENTER
        textSize = 14f
        setTextColor(Color.rgb(145, 145, 145))
        setPadding(dp(20), dp(32), dp(20), dp(32))
        background = rounded(Color.WHITE, 10)
    }

    private fun openAndExtract() {
        val url = firstUrl(urlInput.text?.toString().orEmpty())
        if (url == null) {
            Toast.makeText(this, "没有找到有效链接", Toast.LENGTH_SHORT).show()
            return
        }
        resetState()
        currentPageUrl = url
        xhsSession = isXhs(url)
        urlInput.setText(url)
        statusText.text = if (xhsSession) "正在打开小红书笔记…" else "正在打开网页…"
        webView.loadUrl(url)
    }

    private fun rescan() {
        if (currentPageUrl.isBlank()) {
            openAndExtract()
            return
        }
        finalized = false
        statusText.text = "重新读取当前页面…"
        if (xhsSession) {
            runParser()
            handler.postDelayed({ runParser() }, 800)
            handler.postDelayed({ finalizeResults() }, 2600)
        }
    }

    private fun resetState() {
        handler.removeCallbacksAndMessages(null)
        structuredImages.clear()
        domFallbackImages.clear()
        synchronized(networkImages) {
            networkImages.clear()
            networkImageScores.clear()
        }
        synchronized(playbackVideos) { playbackVideos.clear() }
        caption = ""
        coverUrl = ""
        originVideoUrl = ""
        structuredType = ""
        finalized = false
        if (::captionBox.isInitialized) captionBox.visibility = View.GONE
        render()
    }

    private fun downloadOriginalVideo() {
        val url = originVideoUrl
        if (url.isBlank()) {
            Toast.makeText(this, "没有无水印原视频源", Toast.LENGTH_SHORT).show()
            return
        }
        val cookies = mapOf(url to CookieManager.getInstance().getCookie(url).orEmpty())
        statusText.text = "正在下载视频…"
        XhsVideoDownloader(this).download(
            candidates = listOf(url),
            userAgent = webView.settings.userAgentString.orEmpty(),
            referer = currentPageUrl,
            cookies = cookies,
            status = { message ->
                runOnUiThread { statusText.text = message }
            }
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

    private fun saveAll() {
        var queued = 0
        if (originVideoUrl.isNotBlank()) {
            downloadOriginalVideo()
            queued += 1
        }
        if (coverUrl.isNotBlank()) {
            if (enqueueImage(coverUrl, 0, true)) queued += 1
        } else {
            visibleImages().forEachIndexed { index, url ->
                if (enqueueImage(url, index, false)) queued += 1
            }
        }
        if (queued > 0) {
            Toast.makeText(this, "已开始保存 $queued 个资源", Toast.LENGTH_SHORT).show()
        }
    }

    private fun enqueueImage(url: String, index: Int, isCover: Boolean): Boolean {
        return try {
            val name = if (isCover) {
                "xhs_cover_${System.currentTimeMillis()}.jpg"
            } else {
                "xhs_image_${index + 1}_${System.currentTimeMillis()}.jpg"
            }
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(name)
                .setDescription("HDResourceExtractor")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_PICTURES,
                    "HDResourceExtractor/$name"
                )
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            webView.settings.userAgentString?.takeIf { it.isNotBlank() }?.let {
                request.addRequestHeader("User-Agent", it)
            }
            currentPageUrl.takeIf { it.isNotBlank() }?.let {
                request.addRequestHeader("Referer", it)
            }
            CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let {
                request.addRequestHeader("Cookie", it)
            }
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun consumeShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val url = firstUrl(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()) ?: return
        urlInput.setText(url)
        handler.postDelayed({ openAndExtract() }, 120)
    }

    private fun detectClipboardLink() {
        if (!::urlInput.isInitialized) return
        val clipboard = try {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        } catch (_: Exception) {
            ""
        }
        val url = firstUrl(clipboard) ?: return
        if (url == lastClipboardUrl) return
        val existing = urlInput.text?.toString()?.trim().orEmpty()
        if (existing.isNotBlank() && existing != clipboardAutofillUrl) return
        lastClipboardUrl = url
        clipboardAutofillUrl = url
        urlInput.setText(url)
        statusText.text = "已检测到剪贴板链接，点“打开并提取”"
    }

    private fun firstUrl(text: String): String? =
        Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
            .find(text)
            ?.value
            ?.trimEnd('。', '，', ',', '.', ')', '）', ']', '】', '}', '；', ';')

    private fun isXhs(url: String): Boolean {
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

    private fun copyText(text: String, toast: String) {
        if (text.isBlank()) return
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("HDResourceExtractor", text))
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
    }
}
