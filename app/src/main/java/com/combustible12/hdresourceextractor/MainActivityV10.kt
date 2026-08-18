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

class MainActivityV10 : AppCompatActivity() {

    private data class VideoCandidate(
        val url: String,
        val source: String,
        val height: Int,
        val bitrate: Long,
        val size: Long
    )

    private lateinit var urlInput: EditText
    private lateinit var statusText: TextView
    private lateinit var webView: WebView
    private lateinit var captionCard: LinearLayout
    private lateinit var captionText: TextView
    private lateinit var resultHeader: LinearLayout
    private lateinit var resultTitle: TextView
    private lateinit var saveAllButton: Button
    private lateinit var resultsContainer: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private val xhsImages = linkedMapOf<String, String>()
    private val originalVideos = linkedMapOf<String, VideoCandidate>()
    private val structuredVideoFallbacks = linkedMapOf<String, VideoCandidate>()
    private val genericResources = linkedSetOf<String>()

    private var currentPageUrl = ""
    private var isXhsSession = false
    private var xhsCaption = ""
    private var xhsCoverUrl = ""
    private var clipboardAutofilledUrl = ""
    private var lastClipboardUrl = ""
    private var parsingFinished = false

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
            visibility = View.INVISIBLE
            setBackgroundColor(Color.WHITE)
        }
        root.addView(
            webView,
            FrameLayout.LayoutParams(dp(1), dp(1)).apply {
                gravity = Gravity.BOTTOM or Gravity.END
            }
        )

        val foreground = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(247, 248, 250))
        }

        ViewCompat.setOnApplyWindowInsetsListener(foreground) { view, insets ->
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
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(31, 31, 31))
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

        buttonRow.addView(
            makeMainButton("打开并提取", true).apply {
                setOnClickListener { openAndExtract() }
            },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(5) }
        )
        buttonRow.addView(
            makeMainButton("重新扫描", false).apply {
                setOnClickListener { scanCurrentPage() }
            },
            LinearLayout.LayoutParams(0, dp(44), 0.54f).apply { leftMargin = dp(5) }
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

        resultHeader = LinearLayout(this).apply {
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
            text = "保存全部"
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
            setOnClickListener { saveAllResults() }
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
                if (primary) Color.rgb(230, 234, 240) else Color.rgb(245, 246, 248),
                7,
                Color.rgb(190, 195, 202)
            )
        }

    private fun renderResults() {
        if (!::resultsContainer.isInitialized) return
        resultsContainer.removeAllViews()

        val originals = synchronized(originalVideos) { originalVideos.values.toList() }
        val fallbacks = synchronized(structuredVideoFallbacks) {
            structuredVideoFallbacks.values.toList()
        }
        val images = synchronized(xhsImages) { xhsImages.values.toList() }
        val hasStructuredVideo = originals.isNotEmpty() || fallbacks.isNotEmpty()

        if (hasStructuredVideo) {
            xhsCoverUrl = images.firstOrNull().orEmpty()
        } else {
            xhsCoverUrl = ""
        }

        val bodyImages = if (hasStructuredVideo) emptyList() else images
        val hasAnything = hasStructuredVideo || xhsCoverUrl.isNotBlank() || bodyImages.isNotEmpty()

        resultTitle.text = when {
            hasStructuredVideo && originals.isNotEmpty() -> "无水印视频（1）"
            hasStructuredVideo -> "视频（1）"
            bodyImages.isNotEmpty() -> "无水印原图（${bodyImages.size}）"
            else -> "提取结果"
        }

        saveAllButton.visibility = if (hasAnything) View.VISIBLE else View.GONE
        saveAllButton.text = when {
            hasStructuredVideo && xhsCoverUrl.isNotBlank() -> "保存视频+封面"
            hasStructuredVideo -> "保存视频"
            bodyImages.isNotEmpty() -> "保存全部图片"
            else -> "保存全部"
        }

        if (!hasAnything) {
            resultsContainer.addView(
                TextView(this).apply {
                    text = if (parsingFinished) "没有读取到可用资源" else "解析完成后，资源会在这里预览"
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTextColor(Color.rgb(145, 145, 145))
                    setPadding(dp(12), dp(34), dp(12), dp(34))
                    background = roundedBackground(Color.WHITE, 10)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            return
        }

        if (hasStructuredVideo) {
            addSectionTitle(
                if (originals.isNotEmpty()) "无水印视频" else "视频",
                if (originals.isNotEmpty()) "来源：originVideoKey" else "未读取到 originVideoKey"
            )
            val preview = xhsCoverUrl
            resultsContainer.addView(
                createVideoCard(preview, originals.isNotEmpty()),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(12) }
            )

            if (xhsCoverUrl.isNotBlank()) {
                addSectionTitle("封面图", "独立保留，可单独下载")
                resultsContainer.addView(
                    createImageCard(xhsCoverUrl, 0, isCover = true),
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(12) }
                )
            }
        }

        if (bodyImages.isNotEmpty()) {
            addSectionTitle("无水印原图（${bodyImages.size}）", null)
            bodyImages.chunked(2).forEachIndexed { rowIndex, pair ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.TOP
                }
                pair.forEachIndexed { columnIndex, url ->
                    val index = rowIndex * 2 + columnIndex
                    row.addView(
                        createImageCard(url, index, false),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (columnIndex == 0) rightMargin = dp(5) else leftMargin = dp(5)
                        }
                    )
                }
                if (pair.size == 1) {
                    row.addView(
                        View(this),
                        LinearLayout.LayoutParams(0, dp(1), 1f).apply { leftMargin = dp(5) }
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
    }

    private fun addSectionTitle(title: String, subtitle: String?) {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(3), 0, dp(7))
        }
        wrap.addView(
            TextView(this).apply {
                text = title
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(36, 36, 36))
            }
        )
        if (!subtitle.isNullOrBlank()) {
            wrap.addView(
                TextView(this).apply {
                    text = subtitle
                    textSize = 12f
                    setTextColor(Color.rgb(120, 120, 120))
                }
            )
        }
        resultsContainer.addView(wrap)
    }

    private fun createVideoCard(previewUrl: String, hasOrigin: Boolean): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(Color.WHITE, 10)
            clipToOutline = true
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(45, 48, 54))
        }
        if (previewUrl.isNotBlank()) {
            image.load(previewUrl) {
                crossfade(true)
                placeholder(ColorDrawable(Color.rgb(45, 48, 54)))
                error(ColorDrawable(Color.rgb(45, 48, 54)))
            }
        }
        card.addView(
            image,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(250))
        )

        card.addView(
            makeCardAction(if (hasOrigin) "下载无水印视频" else "下载视频") {
                downloadStructuredVideo()
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        )
        card.addView(divider())
        card.addView(
            makeCardAction("复制视频链接") {
                val url = rankedVideoCandidates().firstOrNull()?.url
                if (url.isNullOrBlank()) {
                    Toast.makeText(this, "没有可复制的视频链接", Toast.LENGTH_SHORT).show()
                } else {
                    copyToClipboard(url, "视频链接已复制")
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        )
        return card
    }

    private fun createImageCard(url: String, index: Int, isCover: Boolean): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(Color.WHITE, 10)
            clipToOutline = true
        }
        card.addView(
            ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.rgb(235, 237, 240))
                load(url) {
                    crossfade(true)
                    placeholder(ColorDrawable(Color.rgb(235, 237, 240)))
                    error(ColorDrawable(Color.rgb(224, 227, 231)))
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (isCover) dp(300) else dp(220)
            )
        )
        card.addView(
            makeCardAction(if (isCover) "下载封面图" else "下载图片") {
                enqueueImageDownload(url, index, isCover)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
        )
        card.addView(divider())
        card.addView(
            makeCardAction(if (isCover) "复制封面链接" else "复制图片链接") {
                copyToClipboard(url, if (isCover) "封面链接已复制" else "图片链接已复制")
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
        )
        return card
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(Color.rgb(238, 238, 238))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
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
            userAgentString = "$userAgentString HDResourceExtractor/0.10"
            loadWithOverviewMode = true
            useWideViewPort = true
            loadsImagesAutomatically = true
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.addJavascriptInterface(Bridge(), "HDExtractor")

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
                request?.url?.toString()?.let { observeGenericResource(it) }
                return super.shouldInterceptRequest(view, request)
            }

            @Suppress("DEPRECATION")
            override fun shouldInterceptRequest(
                view: WebView?,
                url: String?
            ): WebResourceResponse? {
                url?.let { observeGenericResource(it) }
                return super.shouldInterceptRequest(view, url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!url.isNullOrBlank() && url != "about:blank") currentPageUrl = url
                if (isXhsSession) {
                    statusText.text = "正在读取小红书笔记结构化数据…"
                    scanXhsStructured()
                    handler.postDelayed({ scanXhsStructured() }, 700)
                    handler.postDelayed({ scanXhsStructured() }, 1800)
                    handler.postDelayed({ scanXhsStructured() }, 3600)
                    handler.postDelayed({ finalizeXhs() }, 4800)
                } else {
                    scanGenericDom()
                }
            }
        }
    }

    private fun shouldBlockNavigation(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        return try {
            val scheme = Uri.parse(raw).scheme?.lowercase(Locale.US).orEmpty()
            when (scheme) {
                "http", "https", "about" -> false
                "xhsdiscover", "intent", "xiaohongshu" -> {
                    if (isXhsSession) {
                        statusText.text = "已拦截 App 跳转，继续读取笔记数据…"
                        handler.postDelayed({ scanXhsStructured() }, 120)
                        handler.postDelayed({ scanXhsStructured() }, 900)
                        handler.postDelayed({ finalizeXhs() }, 2200)
                    }
                    true
                }
                else -> true
            }
        } catch (_: Exception) {
            true
        }
    }

    private fun scanXhsStructured() {
        if (!isXhsSession) return
        val script = """
            (function() {
              const cleanUrl = (u) => {
                if (!u || typeof u !== 'string') return '';
                u = String(u).replace(/\\u002F/gi, '/').replace(/\\\//g, '/').replace(/&amp;/g, '&').trim();
                if (u.startsWith('//')) u = 'https:' + u;
                return u;
              };

              const state = window.__INITIAL_STATE__ || null;
              const candidates = [];
              try {
                if (state && state.noteData && state.noteData.data && state.noteData.data.noteData) {
                  candidates.push(state.noteData.data.noteData);
                }
              } catch(e) {}
              try {
                if (state && state.note && state.note.noteDetailMap) {
                  const map = state.note.noteDetailMap;
                  const path = String(location.pathname || '').split('/');
                  const noteId = path.length >= 3 ? path[2].split('?')[0] : '';
                  if (noteId && map[noteId] && map[noteId].note) candidates.push(map[noteId].note);
                  Object.keys(map).forEach(k => {
                    if (map[k] && map[k].note) candidates.push(map[k].note);
                  });
                }
              } catch(e) {}
              try {
                if (state && state.noteData && state.noteData.noteData) candidates.push(state.noteData.noteData);
              } catch(e) {}

              const seen = typeof WeakSet !== 'undefined' ? new WeakSet() : null;
              let best = null;
              let bestScore = -1;
              const walk = (obj, depth) => {
                if (!obj || typeof obj !== 'object' || depth > 7) return;
                try {
                  if (seen && seen.has(obj)) return;
                  if (seen) seen.add(obj);
                } catch(e) {}
                let score = 0;
                try { if (obj.video) score += 20; } catch(e) {}
                try { if (Array.isArray(obj.imageList)) score += 12; } catch(e) {}
                try { if (obj.noteId || obj.id) score += 4; } catch(e) {}
                try { if (obj.title || obj.desc) score += 4; } catch(e) {}
                try {
                  if (obj.video && obj.video.consumer && obj.video.consumer.originVideoKey) score += 50;
                } catch(e) {}
                if (score > bestScore) {
                  bestScore = score;
                  best = obj;
                }
                let keys = [];
                try { keys = Object.keys(obj); } catch(e) { return; }
                for (let i = 0; i < keys.length && i < 160; i++) {
                  const key = keys[i];
                  let child = null;
                  try { child = obj[key]; } catch(e) { continue; }
                  if (child && typeof child === 'object') walk(child, depth + 1);
                }
              };

              candidates.forEach(n => {
                let score = 0;
                try { if (n.video) score += 20; } catch(e) {}
                try { if (Array.isArray(n.imageList)) score += 12; } catch(e) {}
                try { if (n.video && n.video.consumer && n.video.consumer.originVideoKey) score += 50; } catch(e) {}
                if (score > bestScore) { best = n; bestScore = score; }
              });
              if (!best && state) walk(state, 0);
              const note = best;

              const emitImageUrl = (u, w, h, source) => {
                u = cleanUrl(u);
                if (!/^https?:/i.test(u)) return;
                try { HDExtractor.addImage(u, Number(w || 0), Number(h || 0), String(source || '')); } catch(e) {}
              };
              const emitImageObject = (item, source) => {
                if (!item || typeof item !== 'object') return;
                const w = Number(item.width || item.imageWidth || 0);
                const h = Number(item.height || item.imageHeight || 0);
                ['urlDefault','urlPre','url','src','originalUrl'].forEach(k => {
                  try { emitImageUrl(item[k], w, h, source); } catch(e) {}
                });
                try {
                  (item.infoList || []).forEach(info => {
                    emitImageUrl(info && (info.url || info.urlDefault), w, h, source);
                  });
                } catch(e) {}
              };

              if (note) {
                try {
                  const title = String(note.title || '').trim();
                  const desc = String(note.desc || '').trim();
                  const tags = Array.isArray(note.tagList) ? note.tagList.map(x => x && x.name).filter(Boolean) : [];
                  try { HDExtractor.setCaption(title, desc, tags.join(' ')); } catch(e) {}
                } catch(e) {}

                try {
                  const key = note.video && note.video.consumer && note.video.consumer.originVideoKey;
                  if (key) {
                    const cleanKey = String(key).replace(/^\\/+/, '');
                    HDExtractor.addOriginalVideo('https://sns-video-bd.xhscdn.com/' + cleanKey);
                  }
                } catch(e) {}

                try {
                  const stream = note.video && note.video.media && note.video.media.stream;
                  if (stream && typeof stream === 'object') {
                    Object.keys(stream).forEach(codec => {
                      const list = Array.isArray(stream[codec]) ? stream[codec] : [];
                      list.forEach(item => {
                        if (!item) return;
                        const height = Number(item.height || 0);
                        const bitrate = Number(item.videoBitrate || item.bitrate || 0);
                        const size = Number(item.size || 0);
                        const urls = [];
                        if (Array.isArray(item.backupUrls)) urls.push.apply(urls, item.backupUrls);
                        if (item.masterUrl) urls.push(item.masterUrl);
                        urls.forEach(u => {
                          u = cleanUrl(u);
                          if (/^https?:/i.test(u)) {
                            try { HDExtractor.addStructuredVideo(u, height, bitrate, size); } catch(e) {}
                          }
                        });
                      });
                    });
                  }
                } catch(e) {}

                try {
                  if (Array.isArray(note.imageList)) {
                    note.imageList.forEach(item => emitImageObject(item, 'state-imageList'));
                  }
                } catch(e) {}
                try { emitImageObject(note.cover, 'state-cover'); } catch(e) {}
                try { emitImageObject(note.video && note.video.image, 'state-video-image'); } catch(e) {}
              }

              try {
                document.querySelectorAll('.note-image-box img, .media-container img, img').forEach(el => {
                  const w = Number(el.naturalWidth || el.width || 0);
                  const h = Number(el.naturalHeight || el.height || 0);
                  emitImageUrl(el.currentSrc || el.src, w, h, 'dom');
                });
              } catch(e) {}
              try {
                const meta = document.querySelector('meta[property="og:image"], meta[name="twitter:image"]');
                if (meta) emitImageUrl(meta.getAttribute('content'), 0, 0, 'meta-cover');
              } catch(e) {}
              try {
                const titleEl = document.querySelector('#detail-title');
                const descEl = document.querySelector('#detail-desc');
                const title = titleEl ? String(titleEl.innerText || titleEl.textContent || '').trim() : '';
                const desc = descEl ? String(descEl.innerText || descEl.textContent || '').trim() : '';
                if (title || desc) HDExtractor.setCaption(title, desc, '');
              } catch(e) {}

              try { HDExtractor.doneStructured(Boolean(note)); } catch(e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private inner class Bridge {
        @JavascriptInterface
        fun addOriginalVideo(url: String) {
            val cleaned = cleanHttpUrl(url) ?: return
            if (!cleaned.contains("sns-video", ignoreCase = true)) return
            synchronized(originalVideos) {
                originalVideos[cleaned] = VideoCandidate(cleaned, "originVideoKey", 0, 0, 0)
            }
        }

        @JavascriptInterface
        fun addStructuredVideo(url: String, height: Int, bitrate: Long, size: Long) {
            val cleaned = cleanHttpUrl(url) ?: return
            if (!cleaned.contains("xhscdn.com", ignoreCase = true) &&
                !cleaned.contains("xiaohongshu.com", ignoreCase = true)
            ) return
            synchronized(structuredVideoFallbacks) {
                val old = structuredVideoFallbacks[cleaned]
                val candidate = VideoCandidate(cleaned, "structured-stream", height, bitrate, size)
                if (old == null || videoQuality(candidate) > videoQuality(old)) {
                    structuredVideoFallbacks[cleaned] = candidate
                }
            }
        }

        @JavascriptInterface
        fun addImage(url: String, width: Int, height: Int, source: String) {
            collectXhsImage(url, width, height, source)
        }

        @JavascriptInterface
        fun setCaption(title: String, desc: String, tags: String) {
            val parts = mutableListOf<String>()
            title.trim().takeIf { it.isNotBlank() }?.let(parts::add)
            desc.trim().takeIf { it.isNotBlank() && it != title.trim() }?.let(parts::add)
            tags.trim().takeIf { it.isNotBlank() }?.let(parts::add)
            val combined = parts.joinToString("\n\n").trim()
            if (combined.isBlank()) return
            runOnUiThread {
                xhsCaption = combined
                captionText.text = combined
                captionCard.visibility = View.VISIBLE
            }
        }

        @JavascriptInterface
        fun doneStructured(foundNote: Boolean) {
            handler.postDelayed({ finalizeXhs() }, if (foundNote) 120 else 350)
        }
    }

    private fun collectXhsImage(rawUrl: String, width: Int, height: Int, source: String) {
        val cleaned = cleanHttpUrl(rawUrl) ?: return
        val lower = cleaned.lowercase(Locale.US)
        val host = try { Uri.parse(cleaned).host?.lowercase(Locale.US).orEmpty() } catch (_: Exception) { "" }
        if (!host.endsWith("xhscdn.com") && host != "ci.xiaohongshu.com") return
        if (host.contains("avatar") || lower.contains("/avatar") || lower.contains("emoji") ||
            lower.contains("icon") || lower.contains("logo") || lower.contains("qrcode")
        ) return

        val likelyMain = host == "ci.xiaohongshu.com" ||
            host.contains("sns-webpic") ||
            host.contains("sns-img") ||
            lower.contains("!h5_") ||
            lower.contains("/1040g") ||
            source.startsWith("state") ||
            (source == "dom" && maxOf(width, height) >= 600 && minOf(width, height) >= 300)
        if (!likelyMain) return

        val original = xhsOriginalImageUrl(cleaned)
        val key = canonicalImageKey(original)
        if (key.isBlank()) return
        synchronized(xhsImages) {
            if (!xhsImages.containsKey(key)) xhsImages[key] = original
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
                val timestampIndex = segments.indexOfFirst { it.matches(Regex("[0-9]{12}")) }
                if (timestampIndex < 0 || timestampIndex + 2 >= segments.size) return url
                val hash = segments[timestampIndex + 1]
                if (!hash.matches(Regex("[0-9a-fA-F]{32}"))) return url
                val payload = segments.drop(timestampIndex + 2).toMutableList()
                if (payload.isEmpty()) return url
                val imageId = payload.last().substringBefore('!').substringBefore('?')
                if (imageId.isBlank()) return url
                payload[payload.lastIndex] = imageId
                "https://ci.xiaohongshu.com/" + payload.joinToString("/")
            } else {
                url
            }
        } catch (_: Exception) {
            url
        }
    }

    private fun canonicalImageKey(url: String): String {
        val cleaned = url.substringBefore('#').substringBefore('?').substringBefore('!')
        return try {
            val segment = Uri.parse(cleaned).lastPathSegment.orEmpty()
            if (segment.length >= 18) segment else cleaned
        } catch (_: Exception) {
            cleaned
        }
    }

    private fun cleanHttpUrl(raw: String): String? {
        var value = raw
            .replace("\\u002F", "/", ignoreCase = true)
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .trim()
        if (value.startsWith("//")) value = "https:$value"
        return value.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun finalizeXhs() {
        if (!isXhsSession) return
        parsingFinished = true
        runOnUiThread {
            renderResults()
            val originals = synchronized(originalVideos) { originalVideos.size }
            val fallbackCount = synchronized(structuredVideoFallbacks) { structuredVideoFallbacks.size }
            val imageCount = synchronized(xhsImages) { xhsImages.size }
            statusText.text = when {
                originals > 0 -> "已识别 1 个小红书无水印视频${if (imageCount > 0) " · 封面图已保留" else ""}"
                fallbackCount > 0 -> "识别到视频，但未读取到 originVideoKey；不会把页面播放流冒充无水印视频"
                imageCount > 0 -> "已识别 $imageCount 张小红书无水印原图"
                else -> "暂未读取到笔记资源，可点“重新扫描”再试"
            }
        }
    }

    private fun rankedVideoCandidates(): List<VideoCandidate> {
        val originals = synchronized(originalVideos) { originalVideos.values.toList() }
        if (originals.isNotEmpty()) return originals
        return synchronized(structuredVideoFallbacks) {
            structuredVideoFallbacks.values
                .sortedWith(
                    compareByDescending<VideoCandidate> { it.height }
                        .thenByDescending { it.bitrate }
                        .thenByDescending { it.size }
                )
        }
    }

    private fun videoQuality(candidate: VideoCandidate): Long =
        candidate.height.toLong() * 1_000_000_000L +
            candidate.bitrate.coerceAtMost(999_999_999L) +
            (candidate.size / 1024L).coerceAtMost(999_999L)

    private fun downloadStructuredVideo() {
        val originals = synchronized(originalVideos) { originalVideos.values.toList() }
        val candidates = if (originals.isNotEmpty()) originals else rankedVideoCandidates()
        if (candidates.isEmpty()) {
            Toast.makeText(this, "没有可用的视频源", Toast.LENGTH_SHORT).show()
            return
        }

        val urls = candidates.map { it.url }.distinct()
        val cookies = urls.associateWith { CookieManager.getInstance().getCookie(it).orEmpty() }
        statusText.text = if (originals.isNotEmpty()) {
            "正在下载 originVideoKey 无水印视频…"
        } else {
            "正在下载结构化视频源…"
        }
        Toast.makeText(this, "正在验证并下载视频…", Toast.LENGTH_SHORT).show()

        XhsVideoDownloader(this).download(
            candidates = urls,
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

    private fun enqueueImageDownload(url: String, index: Int, isCover: Boolean): Boolean {
        return try {
            val extension = guessImageExtension(url)
            val fileName = if (isCover) {
                "xhs_cover_${System.currentTimeMillis()}.$extension"
            } else {
                "xhs_${System.currentTimeMillis()}_${index + 1}.$extension"
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
            CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let {
                request.addRequestHeader("Cookie", it)
            }
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(
                this,
                if (isCover) "封面图已加入下载" else "图片已加入下载",
                Toast.LENGTH_SHORT
            ).show()
            true
        } catch (e: Exception) {
            Toast.makeText(this, "图片下载失败：${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun saveAllResults() {
        val originals = synchronized(originalVideos) { originalVideos.isNotEmpty() }
        val fallbacks = synchronized(structuredVideoFallbacks) { structuredVideoFallbacks.isNotEmpty() }
        val images = synchronized(xhsImages) { xhsImages.values.toList() }
        val hasVideo = originals || fallbacks
        if (hasVideo) {
            downloadStructuredVideo()
            images.firstOrNull()?.let { enqueueImageDownload(it, 0, true) }
        } else {
            images.forEachIndexed { index, url -> enqueueImageDownload(url, index, false) }
        }
    }

    private fun guessImageExtension(url: String): String {
        val lower = url.lowercase(Locale.US)
        return when {
            lower.contains(".png") -> "png"
            lower.contains(".webp") || lower.contains("webp") -> "webp"
            lower.contains(".gif") -> "gif"
            else -> "jpg"
        }
    }

    private fun scanGenericDom() {
        val script = """
            (function() {
              const urls = new Set();
              const add = (u) => {
                if (!u || typeof u !== 'string') return;
                try { urls.add(new URL(u, document.baseURI).href); } catch(e) {}
              };
              document.querySelectorAll('img, video, source').forEach(el => {
                add(el.currentSrc); add(el.src); add(el.getAttribute && el.getAttribute('src'));
              });
              try {
                const meta = document.querySelectorAll('meta[property="og:image"], meta[property="og:video"]');
                meta.forEach(x => add(x.getAttribute('content')));
              } catch(e) {}
              urls.forEach(u => { try { HDExtractor.addGeneric(u); } catch(e) {} });
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun observeGenericResource(url: String) {
        if (!isXhsSession && isGenericMediaUrl(url)) {
            synchronized(genericResources) { genericResources.add(url) }
        }
    }

    private fun isGenericMediaUrl(url: String): Boolean {
        val lower = url.substringBefore('?').substringBefore('#').lowercase(Locale.US)
        return listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".mp4", ".webm", ".mov", ".m4v")
            .any { lower.endsWith(it) }
    }

    private fun scanCurrentPage() {
        if (isXhsSession) {
            statusText.text = "正在重新读取结构化数据…"
            scanXhsStructured()
            handler.postDelayed({ scanXhsStructured() }, 700)
            handler.postDelayed({ finalizeXhs() }, 1800)
        } else {
            scanGenericDom()
        }
    }

    private fun openAndExtract() {
        val raw = urlInput.text?.toString()?.trim().orEmpty()
        val url = extractFirstUrl(raw) ?: run {
            Toast.makeText(this, "没有找到有效链接", Toast.LENGTH_SHORT).show()
            return
        }
        urlInput.setText(url)
        currentPageUrl = url
        isXhsSession = isXhsUrl(url)
        clearResults()
        statusText.text = if (isXhsSession) "正在打开小红书链接…" else "正在打开网页…"
        webView.loadUrl(url)
    }

    private fun clearResults() {
        handler.removeCallbacksAndMessages(null)
        synchronized(xhsImages) { xhsImages.clear() }
        synchronized(originalVideos) { originalVideos.clear() }
        synchronized(structuredVideoFallbacks) { structuredVideoFallbacks.clear() }
        synchronized(genericResources) { genericResources.clear() }
        xhsCaption = ""
        xhsCoverUrl = ""
        parsingFinished = false
        if (::captionCard.isInitialized) {
            captionText.text = ""
            captionCard.visibility = View.GONE
        }
        renderResults()
    }

    private fun isXhsUrl(url: String): Boolean {
        return try {
            val host = Uri.parse(url).host?.lowercase(Locale.US).orEmpty()
            host == "xhslink.cn" || host.endsWith("xiaohongshu.com") || host.endsWith("xhscdn.com")
        } catch (_: Exception) {
            false
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        extractFirstUrl(shared)?.let {
            urlInput.setText(it)
            clipboardAutofilledUrl = it
            statusText.text = "已接收分享链接，点“打开并提取”"
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
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (!clipboard.hasPrimaryClip()) return ""
            clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractFirstUrl(text: String): String? {
        val match = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE).find(text) ?: return null
        return match.value.trimEnd('。', '，', ',', '.', '！', '!', '？', '?', ';', '；', ')', '）', ']', '】')
    }

    private fun copyToClipboard(text: String, message: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("HDResourceExtractor", text))
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
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
