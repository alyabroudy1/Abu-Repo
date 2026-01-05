package com.arabic

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.util.Base64

class FaselHD : MainAPI() {
    override var mainUrl = "https://www.faselhds.biz"
    override var name = "FaselHD"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    override val usesWebView = true


    override val mainPage = mainPageOf(
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85/page/" to "أفلام",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa/page/" to "مسلسلات",
        "$mainUrl/category/%d8%a7%d9%86%d9%8a%d9%85%d9%8a/page/" to "انمي",
        "$mainUrl/category/%d8%a7%d8%b3%d9%8a%d9%88%d9%8a/page/" to "اسيوي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
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
        val document = app.get("$mainUrl/?s=$query").document
        return document.select(".movie, .card, article.item, .film-card, .search-result, .box--item").mapNotNull { 
            it.toSearchResult() 
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        // Use default app.get which handles cookies/headers automatically often better than hardcoded ones
        val fixedUrl = url.replace("faselhd.cloud", "www.faselhds.biz").replace("faselhd.center", "www.faselhds.biz")
        val document = app.get(fixedUrl).document

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
        // Force rewrite legacy domain to new one
        val fixedData = data.replace("faselhd.cloud", "www.faselhds.biz").replace("faselhd.center", "www.faselhds.biz")

        // Attempt to load from the main URL first, then try the /watch variant if needed or if main has no links
        var document = app.get(fixedData).document
        
        // Helper to run extraction on a document
        suspend fun extractFromDoc(doc: org.jsoup.nodes.Document) {
             if (doc.title().contains("Just a moment", ignoreCase = true)) {
                 // Warning: This means Cloudflare might be blocking us and CloudStream's auto-bypass didn't work.
                 // We can't do much here without a WebView, but logging/throwing might help the user understand.
                 // For now, we continue hoping regex matches something, but it likely won't.
             }

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

            // 4. Specific OnClick Search (From omerFlex_3 discovery)
            // Looks for onclick="player_iframe.location.href = '...'"
            val html = doc.html()
            Regex("""player_iframe\.location\.href\s*=\s*['"]([^'"]+)['"]""").findAll(html).forEach { match ->
                val url = match.groupValues[1]
                if (url.startsWith("http")) {
                     loadExtractor(url, mainUrl, subtitleCallback, callback)
                }
            }

            // 5. Broad Regex for M3U8/MP4 (The "Nuclear Option")
            // This covers variables in <head>, <body>, or inline events.
            
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
            
             // 6. Base64 hash logic (Legacy/Specific FaselHD feature)
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
        if (!fixedData.endsWith("/watch")) {
             val watchUrl = "$fixedData/watch"
             try {
                // We use app.get directly here to avoid double-wrapping or just rely on getSafe
                val watchDoc = app.get(watchUrl).document
                extractFromDoc(watchDoc)
             } catch (e: Exception) {
                 // The /watch page might not exist, ignore
             }
        }

        return true
    }
}
