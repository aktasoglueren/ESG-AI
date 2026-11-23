package com.esgmuhendislik.esgai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLDecoder

/**
 * API-key-free web research. Uses the public DuckDuckGo HTML results page,
 * then reads a small number of result pages and returns source URLs.
 * Search providers may rate-limit automated requests; failures are non-fatal.
 */
object WebResearch {
    data class Source(val title: String, val url: String)
    data class Result(val context: String, val sources: List<Source>, val notice: String)

    suspend fun searchAndRead(query: String): Result = withContext(Dispatchers.IO) {
        require(query.isNotBlank()) { "Arama metni boş." }
        val results = try {
            val doc = Jsoup.connect("https://html.duckduckgo.com/html/")
                .data("q", query.take(240))
                .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/122.0 Mobile Safari/537.36")
                .referrer("https://duckduckgo.com/")
                .timeout(15000)
                .maxBodySize(2 * 1024 * 1024)
                .get()

            doc.select(".result").mapNotNull { item ->
                val link = item.selectFirst("a.result__a") ?: return@mapNotNull null
                val rawUrl = link.attr("href")
                val url = unwrapSearchUrl(rawUrl)
                if (!url.startsWith("https://") || url.contains("duckduckgo.com")) return@mapNotNull null
                val title = link.text().trim().take(180)
                val snippet = item.selectFirst(".result__snippet")?.text()?.trim().orEmpty()
                if (title.isBlank()) null else Triple(title, url, snippet)
            }.distinctBy { it.second }.take(4)
        } catch (error: Exception) {
            return@withContext Result("", emptyList(), "API'siz web araması şu anda yanıt vermedi: ${error.message ?: "arama engellendi"}. Yerel model yine çalışabilir.")
        }

        if (results.isEmpty()) {
            return@withContext Result("", emptyList(), "Web araması sonuç döndürmedi; yanıt yerel modelle hazırlanacak.")
        }

        val sources = mutableListOf<Source>()
        val context = StringBuilder()
        for ((title, url, snippet) in results.take(3)) {
            val pageText = runCatching {
                val page = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/122.0 Mobile Safari/537.36")
                    .referrer("https://duckduckgo.com/")
                    .timeout(9000)
                    .maxBodySize(2 * 1024 * 1024)
                    .get()
                page.select("script,style,nav,footer,header,aside,form,svg,iframe,noscript").remove()
                val article = page.selectFirst("main, article")
                (article?.text()?.takeIf { it.length > 160 } ?: page.body()?.text().orEmpty())
                    .replace(Regex("\\s+"), " ")
                    .take(2600)
            }.getOrDefault("")
            val usableText = pageText.ifBlank { snippet }
            if (usableText.isNotBlank()) {
                sources += Source(title, url)
                context.append("\n[Kaynak: ").append(title).append("]\n")
                    .append(usableText).append("\n")
            }
        }

        if (sources.isEmpty()) Result("", emptyList(), "Arama bulundu ancak sayfalar okunamadı. Yerel modelle devam ediliyor.")
        else Result(context.toString().take(8500), sources, "")
    }

    private fun unwrapSearchUrl(raw: String): String {
        if (raw.startsWith("https://") && !raw.contains("duckduckgo.com")) return raw
        val encoded = Regex("[?&]uddg=([^&]+)").find(raw)?.groupValues?.getOrNull(1) ?: return raw
        return runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault("")
    }
}
