package com.arabic

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

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
        "Accept-Language" to "en-US,en;q=0.5",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85/page/" to "أفلام",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa/page/" to "مسلسلات",
        "$mainUrl/category/%d8%a7%d9%86%d9%8a%d9%85%d9%8a/page/" to "انمي",
        "$mainUrl/category/%d8%a7%d8%b3%d9%8a%d9%88%d9%8a/page/" to "اسيوي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page, headers = headers).document
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
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = year
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query", headers = headers).document
        return document.select(".movie, .card, article.item, .film-card, .search-result, .box--item").mapNotNull { 
            it.toSearchResult() 
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.postTitle, h1.title, .post-title h1, .box--title")?.text()
            ?: document.selectFirst("title")?.text()?.substringBefore(" مترجم")
            ?: return null
            
        val poster = document.selectFirst("div.poster img, .posterBlock img")?.attr("src")
        val description = document.selectFirst("div.story, div.singlePostContent p, .synopsis")?.text()
        val year = Regex("(\\d{4})").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val tags = document.select("span.genres a, .singleInfoP a").map { it.text() }
        
        // Check if it's a series by looking for episodes
        val episodes = document.select("div.seasonDiv a, .epsList a, a.epAll").mapNotNull { ep ->
            val epTitle = ep.text()
            val epHref = ep.attr("href")
            if (epHref.isBlank()) return@mapNotNull null
            
            val episodeNum = Regex("(\\d+)").find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(epHref) {
                this.name = epTitle
                this.episode = episodeNum
            }
        }
        
        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // Find iframe sources
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) {
                loadExtractor(src, mainUrl, subtitleCallback, callback)
            }
        }
        
        // Find direct video sources
        document.select("source, video source").forEach { source ->
            val src = source.attr("src")
            if (src.isNotBlank() && (src.contains(".mp4") || src.contains(".m3u8"))) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = src,
                        type = if (src.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                    }
                )
            }
        }
        
        // Find player div with data attributes
        document.select("div[data-url], div[data-src]").forEach { div ->
            val src = div.attr("data-url").ifBlank { div.attr("data-src") }
            if (src.isNotBlank()) {
                loadExtractor(src, mainUrl, subtitleCallback, callback)
            }
        }
        
        // Look for embedded player scripts
        val scripts = document.select("script").map { it.data() }
        scripts.forEach { script ->
            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").findAll(script).forEach { match ->
                val m3u8Url = match.groupValues[1]
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name - HLS",
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                    }
                )
            }
            Regex("""["'](https?://[^"']+\.mp4[^"']*)["']""").findAll(script).forEach { match ->
                val mp4Url = match.groupValues[1]
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name - MP4",
                        url = mp4Url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                    }
                )
            }
        }
        
        return true
    }
}
