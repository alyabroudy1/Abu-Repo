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
        val document = app.get("$mainUrl/?s=$query", headers = headers).document
        return document.select(".movie, .card, article.item, .film-card, .search-result, .box--item").mapNotNull { 
            it.toSearchResult() 
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document

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
        // Movies/Episodes usually have the player on the /watch page
        val watchUrl = if (data.endsWith("/watch")) data else "$data/watch"
        val document = app.get(watchUrl, headers = headers).document

        // 1. Parse Server List (.server--item)
        // The structure is: <li class="server--item">...</li> <li class="embed-src" data-src="..."></li>
        val serverItems = document.select(".server--item")
        serverItems.forEach { serverItem ->
            val embedSrc = serverItem.nextElementSibling()
            if (embedSrc != null && embedSrc.hasClass("embed-src")) {
                val src = embedSrc.attr("data-src")
                if (src.isNotBlank()) {
                    loadExtractor(src, mainUrl, subtitleCallback, callback)
                }
            }
        }

        // 2. Check for Iframe (often the first server is pre-loaded into #serverIframe or .player--iframe iframe)
        document.select("iframe[name='player_iframe'], #serverIframe, .player--iframe iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) {
                loadExtractor(src, mainUrl, subtitleCallback, callback)
            }
        }

        // 3. Download Links (often contain valid streams or direct files)
        document.select("div.downloads a.download--item, a.download--direct").forEach { dl ->
            val href = dl.attr("href")
            if (href.isNotBlank() && !href.startsWith("javascript")) {
                 loadExtractor(href, mainUrl, subtitleCallback, callback)
            }
        }
        
        return true
    }
}
