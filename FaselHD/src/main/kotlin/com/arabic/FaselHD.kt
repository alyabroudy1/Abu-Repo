package com.arabic

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.util.Base64

class FaselHD : MainAPI() {
    override var mainUrl = "https://faselhd.cloud"
    override var name = "FaselHD"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "ar-SA,ar;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
        "Sec-Ch-Ua" to "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"",
        "Sec-Ch-Ua-Mobile" to "?0",
        "Sec-Ch-Ua-Platform" to "\"Windows\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85/page/" to "أفلام",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa/page/" to "مسلسلات",
        "$mainUrl/category/%d8%a7%d9%86%d9%8a%d9%85%d9%8a/page/" to "انمي",
        "$mainUrl/category/%d8%a7%d8%b3%d9%8a%d9%88%d9%8a/page/" to "اسيوي"
    )

    // Helper method to simulate the "ScraperInterceptor" logic:
    // Retry on 403/503 with slight header modification or delay.
    private suspend fun getSafe(url: String, headers: Map<String, String> = this.headers): com.lagradost.cloudstream3.NiceResponse {
        return try {
            app.get(url, headers = headers)
        } catch (e: Exception) {
            // Check for common specific errors if possible, or just retry blindly for now as a "robust" measure
            // for 403/Cloudflare type issues.
            // Note: simple generic threading delay is bad practice in coroutines, but manageable for a single retry.
            // Ideally check e.statusCode if available in the specific exception type.
            
            // Retry once with a "cache bust" or slight tweak
            val newHeaders = headers.toMutableMap()
            newHeaders["Cache-Control"] = "no-cache"
            newHeaders["Pragma"] = "no-cache"
            
            // Small delay to be "human-like"
            kotlinx.coroutines.delay(1000) 
            
            app.get(url, headers = newHeaders)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = getSafe(request.data + page).document
        val home = document.select(".postDiv, .box--item").mapNotNull { 
            it.toSearchResult() 
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a") ?: return null
        val title = selectFirst("h3, .title, a[title], .box--title")?.text()?.trim() 
            ?: anchor.attr("title").ifBlank { return null }
        
        val href = anchor.attr("href")
        if (href.isBlank()) return null
        
        val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"
        
        val posterUrl = selectFirst("img")?.let { img ->
            img.attr("data-image").ifBlank {
                img.attr("data-src").ifBlank { 
                    img.attr("src").ifBlank { 
                        img.attr("data-lazy-src") 
                    }
                }
            }
        }
        val year = Regex("(\\d{4})").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
        
        return if (href.contains("/مسلسل-") || href.contains("series") || href.contains("season")) {
            newTvSeriesSearchResponse(title, fullHref, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, fullHref, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = year
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = getSafe("$mainUrl/?s=$query").document
        return document.select(".movie, .card, article.item, .film-card, .search-result, .box--item").mapNotNull { 
            it.toSearchResult() 
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = getSafe(url).document

        val title = document.selectFirst("h1.postTitle, h1.title, .post-title h1, .box--title")?.text()
            ?.substringBefore(" مترجم") ?: document.selectFirst("title")?.text()?.substringBefore(" مترجم") ?: return null

        val poster = document.selectFirst("div.poster img, .posterBlock img, .single--poster img")?.let { img ->
            img.attr("src").ifBlank { img.attr("data-image") }
        }

        val plot = document.selectFirst("div.post--content--inner, div.story, div.singlePostContent p")?.text()
            ?.trim()

        val year = document.selectFirst("ul.terms--and--metas li:contains(السنه) a")?.text()?.toIntOrNull()
            ?: Regex("(\\d{4})").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val tags = document.select("ul.genres a, span.genres a").map { it.text() }

        val recommendations = document.select(".box--item").mapNotNull { it.toSearchResult() }

        // Check if it's a series
        val episodes = document.select("div.episodes--list--side a, .episodesList a, .episodes-list a").mapNotNull { ep ->
            val epTitle = ep.text()
            val epHref = ep.attr("href")
            if (epHref.isBlank()) return@mapNotNull null
            
            // Extract episode number
            val episodeNum = Regex("(\\d+)").find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(epHref) {
                this.name = epTitle
                this.episode = episodeNum
            }
        }

        return if (episodes.isNotEmpty() || url.contains("series") || url.contains("season")) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Attempt to load from the main URL first, then try the /watch variant if needed or if main has no links
        var document = getSafe(data).document
        
        // Helper to run extraction on a document
        suspend fun extractFromDoc(doc: org.jsoup.nodes.Document) {
             // 1. Broad Iframe Search
            // "iframe" matches ANY iframe. We filter by Src.
            doc.select("iframe").forEach { iframe ->
                val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                // Accept any http source, filter in loadExtractor
                if (src.isNotBlank() && src.startsWith("http")) {
                     loadExtractor(src, mainUrl, subtitleCallback, callback)
                }
            }
            
            // 2. Broad Button/Link Search for servers
            // Many sites use <a href/data-url> or <button data-url> for servers
            doc.select(".server--item, .servers a, .server-item a, li.server a, a[data-url], button[data-url]").forEach { element ->
                 val url = element.attr("data-url").ifBlank { 
                     element.attr("data-src").ifBlank { 
                         element.attr("href") 
                     }
                 }
                 if (url.isNotBlank() && url.startsWith("http") && !url.contains("facebook") && !url.contains("twitter")) {
                     loadExtractor(url, mainUrl, subtitleCallback, callback)
                 }
            }
            
            // 3. Look for "embed-src" hidden inputs/divs often used in FaselHD
             doc.select(".embed-src").forEach { element ->
                val src = element.attr("data-src").ifBlank { element.attr("data-url") }
                if (src.isNotBlank()) {
                     loadExtractor(src, mainUrl, subtitleCallback, callback)
                }
            }

            // 4. Regex for scripts (The "Nuclear Option")
            // Instead of selecting specific scripts, we get the WHOLE html and regex it.
            // This covers variables in <head>, <body>, or inline events.
            val html = doc.html()
            
            // M3U8
            Regex("""["'](https?://[^"']*\.m3u8[^"']*)["']""").findAll(html).forEach { match ->
                val url = match.groupValues[1]
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name - Auto",
                        url = url,
                        type = ExtractorLinkType.M3U8,
                        referer = mainUrl,
                        quality = Qualities.Unknown.value
                    )
                )
            }
            // MP4
            Regex("""["'](https?://[^"']*\.mp4[^"']*)["']""").findAll(html).forEach { match ->
                 val url = match.groupValues[1]
                 callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name - Auto",
                        url = url,
                        type = ExtractorLinkType.VIDEO,
                        referer = mainUrl,
                        quality = Qualities.Unknown.value
                    )
                )
            }
            
             // 5. Base64 hash logic (Legacy/Specific FaselHD feature)
            doc.selectFirst("#play-video")?.attr("href")?.let { href ->
                val hash = href.substringAfter("hash=", "").substringBefore("&")
                if (hash.isNotBlank()) {
                     try {
                         val decodedUrl = String(Base64.decode(hash, Base64.DEFAULT))
                         loadExtractor(decodedUrl, mainUrl, subtitleCallback, callback)
                     } catch (e: Exception) { }
                }
            }
        }
        
        // Run on main page
        extractFromDoc(document)
        
        // If it's not a /watch url, and we found nothing (or even if we did, to be safe), check /watch
        if (!data.endsWith("/watch")) {
             val watchUrl = "$data/watch"
             try {
                // We use app.get directly here to avoid double-wrapping or just rely on getSafe
                val watchDoc = getSafe(watchUrl).document
                extractFromDoc(watchDoc)
             } catch (e: Exception) {
                 // The /watch page might not exist, ignore
             }
        }

        return true
    }
}
