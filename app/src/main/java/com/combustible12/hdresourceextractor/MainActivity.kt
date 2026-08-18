package com.combustible12.hdresourceextractor

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
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

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.WHITE)
        }

        urlInput = EditText(this).apply {
            hint = "粘贴网页链接"
            maxLines = 2
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val openButton = Button(this).apply {
            text = "打开并提取"
            setOnClickListener { openUrl(urlInput.text.toString()) }
        }

        val scanButton = Button(this).apply {
            text = "重新扫描"
            setOnClickListener { scanDomResources() }
        }

        buttonRow.addView(openButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttonRow.addView(scanButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        statusText = TextView(this).apply {
            text = "等待网页链接"
            setPadding(4, 8, 4, 8)
        }

        webView = WebView(this)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, visibleResources)
        listView = ListView(this).apply {
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, position, _ -> downloadResource(visibleResources[position]) }
            setOnItemLongClickListener { _, _, position, _ ->
                copyToClipboard(visibleResources[position])
                true
            }
        }

        root.addView(urlInput, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        root.addView(buttonRow, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        root.addView(statusText, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        root.addView(webView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.15f))
        root.addView(listView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.85f))

        setContentView(root)
    }

    @Suppress("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "$userAgentString HDResourceExtractor/0.1"
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        webView.addJavascriptInterface(ResourceBridge(), "HDExtractor")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                request?.url?.toString()?.let { collectResource(it) }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                statusText.text = "页面已加载，正在扫描高清资源…"
                scanDomResources()
            }
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val url = extractFirstUrl(sharedText)
            if (url != null) {
                urlInput.setText(url)
                openUrl(url)
            }
        }
    }

    private fun extractFirstUrl(text: String): String? {
        return Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
            .find(text)
            ?.value
            ?.trimEnd('.', ',', '，', '。', ')', '）', ']', '】')
    }

    private fun openUrl(raw: String) {
        val url = extractFirstUrl(raw.trim()) ?: raw.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(this, "请输入 http/https 网页链接", Toast.LENGTH_SHORT).show()
            return
        }

        resources.clear()
        visibleResources.clear()
        adapter.notifyDataSetChanged()
        statusText.text = "正在加载网页…"
        webView.loadUrl(url)
    }

    private fun scanDomResources() {
        val script = """
            (function() {
              const found = new Set();
              const add = (u) => {
                if (!u) return;
                try { found.add(new URL(u, document.baseURI).href); } catch(e) {}
              };

              document.querySelectorAll('img').forEach(el => {
                add(el.src); add(el.currentSrc);
                if (el.srcset) el.srcset.split(',').forEach(x => add(x.trim().split(/\\s+/)[0]));
                ['data-src','data-original','data-lazy-src','data-url'].forEach(a => add(el.getAttribute(a)));
              });

              document.querySelectorAll('video, audio, source').forEach(el => {
                add(el.src); add(el.currentSrc); add(el.getAttribute('src'));
              });

              document.querySelectorAll('[style]').forEach(el => {
                const s = el.getAttribute('style') || '';
                const re = /url\\([\"']?([^\"')]+)[\"']?\\)/g;
                let m; while ((m = re.exec(s)) !== null) add(m[1]);
              });

              try {
                performance.getEntriesByType('resource').forEach(e => add(e.name));
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
                statusText.text = "已发现 ${visibleResources.size} 个图片/视频候选资源（页面扫描 $count 项）"
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
            statusText.text = "已发现 ${visibleResources.size} 个图片/视频候选资源"
        }
    }

    private fun isMediaCandidate(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("blob:")) return false
        val clean = url.substringBefore('?').substringBefore('#').lowercase(Locale.US)
        val ext = listOf(
            ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".avif", ".heic",
            ".mp4", ".webm", ".mov", ".m4v", ".m3u8", ".ts", ".mpd",
            ".mp3", ".m4a", ".aac", ".wav", ".ogg"
        )
        if (ext.any { clean.endsWith(it) }) return true

        val lower = url.lowercase(Locale.US)
        return listOf("image", "video", "media", "poster", "original", "origin", "source").any { lower.contains(it) }
    }

    private fun downloadResource(url: String) {
        if (url.startsWith("blob:")) {
            Toast.makeText(this, "blob: 资源不能直接交给系统下载；后续版本可加入 blob 转存", Toast.LENGTH_LONG).show()
            return
        }

        try {
            val uri = Uri.parse(url)
            val guessedName = URLDecoder.decode(uri.lastPathSegment ?: "resource", "UTF-8")
                .takeIf { it.isNotBlank() }
                ?: "resource"

            val request = DownloadManager.Request(uri)
                .setTitle(guessedName)
                .setDescription("HDResourceExtractor")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, guessedName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(this, "已交给系统下载：$guessedName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "下载失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("resource url", text))
        Toast.makeText(this, "资源链接已复制", Toast.LENGTH_SHORT).show()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        webView.apply {
            loadUrl("about:blank")
            stopLoading()
            removeAllViews()
            destroy()
        }
        super.onDestroy()
    }
}
