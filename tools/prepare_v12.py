from pathlib import Path
import re

path = Path('app/src/main/java/com/combustible12/hdresourceextractor/MainActivityV11.kt')
text = path.read_text(encoding='utf-8')

text = text.replace(
    '    private var clipboardAutofilledUrl = ""\n',
    '    private var clipboardAutofilledUrl = ""\n    private var xhsDeepLinkFallbackLoaded = false\n'
)

text = text.replace(
    '            userAgentString = "$userAgentString HDResourceExtractor/0.11"\n',
    '            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36 HDResourceExtractor/0.12"\n'
)

pattern = re.compile(
    r'    private fun shouldBlockNavigation\(raw: String\?\): Boolean \{.*?\n    \}\n\n    private fun scheduleXhsScans\(\)',
    re.S,
)

replacement = r'''    private fun shouldBlockNavigation(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        return try {
            val uri = Uri.parse(raw)
            val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()
            when (scheme) {
                "http", "https", "about" -> false
                "xhsdiscover", "xiaohongshu" -> {
                    if (isXhsSession) handleXhsDeepLink(uri, raw)
                    true
                }
                "intent" -> {
                    if (isXhsSession) {
                        harvestXhsDeepLinkPayload(raw)
                        statusText.text = "已拦截应用跳转，正在解析携带数据…"
                        handler.postDelayed({ scanXhsStructured() }, 120)
                        handler.postDelayed({ finalizeXhs() }, 1800)
                    }
                    true
                }
                else -> true
            }
        } catch (_: Exception) {
            true
        }
    }

    private fun handleXhsDeepLink(uri: Uri, raw: String) {
        harvestXhsDeepLinkPayload(raw)
        val noteId = extractXhsNoteId(uri, raw)
        if (noteId.isNotBlank() && !xhsDeepLinkFallbackLoaded) {
            xhsDeepLinkFallbackLoaded = true
            val builder = Uri.parse("https://www.xiaohongshu.com/explore/$noteId").buildUpon()
            listOf("xsec_token", "xsec_source").forEach { key ->
                try {
                    uri.getQueryParameter(key)?.takeIf { it.isNotBlank() }?.let {
                        builder.appendQueryParameter(key, it)
                    }
                } catch (_: Exception) {}
            }
            val h5Url = builder.build().toString()
            statusText.text = "已捕获小红书笔记，正在打开 H5 数据页…"
            handler.postDelayed({
                currentPageUrl = h5Url
                webView.loadUrl(h5Url)
            }, 100)
            handler.postDelayed({ scanXhsStructured() }, 900)
            handler.postDelayed({ scanXhsStructured() }, 2200)
            handler.postDelayed({ finalizeXhs() }, 5000)
        } else {
            statusText.text = if (noteId.isNotBlank()) {
                "已捕获笔记深链，正在解析携带数据…"
            } else {
                "已捕获小红书深链，正在读取携带数据…"
            }
            handler.postDelayed({ scanXhsStructured() }, 100)
            handler.postDelayed({ scanXhsStructured() }, 800)
            handler.postDelayed({ finalizeXhs() }, 2200)
        }
    }

    private fun extractXhsNoteId(uri: Uri, raw: String): String {
        val idRegex = Regex("^[0-9a-fA-F]{24}$")
        try {
            uri.pathSegments.firstOrNull { idRegex.matches(it) }?.let { return it }
        } catch (_: Exception) {}
        val names = listOf("noteId", "note_id", "source_note_id", "target_note_id", "id")
        for (name in names) {
            try {
                val value = uri.getQueryParameter(name).orEmpty()
                if (idRegex.matches(value)) return value
            } catch (_: Exception) {}
        }
        return Regex("(?<![0-9a-fA-F])([0-9a-fA-F]{24})(?![0-9a-fA-F])")
            .find(raw)?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun harvestXhsDeepLinkPayload(raw: String) {
        var decoded = raw
        repeat(4) {
            decoded = try { Uri.decode(decoded) } catch (_: Exception) { decoded }
        }
        val normalized = decoded
            .replace("\\u002F", "/", ignoreCase = true)
            .replace("\\/", "/")
            .replace("\\\"", "\"")

        val originRegex = Regex("[\\\"']originVideoKey[\\\"']\\s*:\\s*[\\\"']([^\\\"']+)[\\\"']", RegexOption.IGNORE_CASE)
        originRegex.findAll(normalized).forEach { match ->
            val key = match.groupValues.getOrNull(1).orEmpty().trim().trimStart('/')
            if (key.isNotBlank()) {
                val url = "https://sns-video-bd.xhscdn.com/$key"
                synchronized(originalVideos) {
                    originalVideos[url] = VideoCandidate(url, "originVideoKey-deeplink")
                }
                xhsVideoNoteDetected = true
            }
        }

        val urlRegex = Regex("https?://[^\\s\\\"'<>]+", RegexOption.IGNORE_CASE)
        urlRegex.findAll(normalized).forEach { match ->
            val candidate = match.value
                .trimEnd(')', ']', '}', ',', ';')
                .replace("&amp;", "&")
            if (candidate.contains("xhscdn.com", ignoreCase = true) ||
                candidate.contains("xiaohongshu.com", ignoreCase = true)
            ) {
                if (!isLikelyVideoUrl(candidate)) {
                    collectXhsImage(candidate, 0, 0, "deeplink", isCover = false)
                }
            }
        }

        val title = Regex("[\\\"']title[\\\"']\\s*:\\s*[\\\"']([^\\\"']{1,200})[\\\"']", RegexOption.IGNORE_CASE)
            .find(normalized)?.groupValues?.getOrNull(1).orEmpty().trim()
        val desc = Regex("[\\\"']desc[\\\"']\\s*:\\s*[\\\"']([^\\\"']{1,1500})[\\\"']", RegexOption.IGNORE_CASE)
            .find(normalized)?.groupValues?.getOrNull(1).orEmpty().trim()
        if (title.isNotBlank() || desc.isNotBlank()) {
            val combined = listOf(title, desc).filter { it.isNotBlank() }.distinct().joinToString("\n\n")
            runOnUiThread {
                xhsCaption = combined
                captionText.text = combined
                captionCard.visibility = View.VISIBLE
                renderResults()
            }
        } else {
            runOnUiThread { renderResults() }
        }
    }

    private fun scheduleXhsScans()'''

text, count = pattern.subn(replacement, text, count=1)
if count != 1:
    raise SystemExit('Could not replace shouldBlockNavigation block')

text = text.replace(
    '        xhsVideoNoteDetected = false\n        parsingFinished = false\n',
    '        xhsVideoNoteDetected = false\n        parsingFinished = false\n        xhsDeepLinkFallbackLoaded = false\n'
)

text = text.replace('HDResourceExtractor/0.11', 'HDResourceExtractor/0.12')
path.write_text(text, encoding='utf-8')
print('Prepared v0.12 deep-link fallback source')
