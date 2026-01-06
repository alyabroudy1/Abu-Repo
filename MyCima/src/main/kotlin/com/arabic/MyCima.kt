package com.arabic

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.delay

class MyCima : MainAPI() {
    override var mainUrl = "https://my-cima.video"
    override var name = "MyCima"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/category/movies/page/" to "أفلام",
        "$mainUrl/category/series/page/" to "مسلسلات",
        "$mainUrl/category/arabic-movies/page/" to "أفلام عربية",
        "$mainUrl/category/arabic-series/page/" to "مسلسلات عربية"
    )

    // Helper method to simulate the "ScraperInterceptor" logic:
    // Retry on 403/503 with slight header modification or delay.
    private suspend fun getSafe(url: String, headers: Map<String, String>? = null): NiceResponse {
        return try {
            app.get(url, headers = headers ?: emptyMap())
        } catch (e: Exception) {
            // Retry once with a "cache bust" or slight tweak
            val newHeaders = (headers ?: emptyMap()).toMutableMap()
            newHeaders["Cache-Control"] = "no-cache"
            newHeaders["Pragma"] = "no-cache"
            
            // ReCaptcha placeholder: 
            // If we detected a captcha keys here, we would use: 
            // val token = ReCaptcha.getCaptchaToken(url, "SITE_KEY")
            // newHeaders["Recaptcha-Token"] = token
            
            // Small delay to be "human-like"
            delay(1000) 
            
            app.get(url, headers = newHeaders)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = getSafe(request.data + page).document
        val home = document.select(".post-item, .movie-item, article.item").mapNotNull { 
            it.toSearchResult() 
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a") ?: return null
        val title = selectFirst("h3, .title, h2")?.text()?.trim() 
            ?: anchor.attr("title").ifBlank { return null }
        
        val href = anchor.attr("href")
        if (href.isBlank()) return null
        
        val posterUrl = selectFirst("img")?.let { img ->
            img.attr("data-src").ifBlank { 
                img.attr("src").ifBlank { 
                    img.attr("data-lazy-src") 
                }
            }
        }
        
        val year = Regex("(\\d{4})").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val quality = selectFirst(".quality, .ql")?.text()
        
        val isSeries = href.contains("series") || href.contains("مسلسل") || 
                       href.contains("season") || href.contains("episode")
        
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year = year
                this.quality = getSearchQuality(quality)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = year
                this.quality = getSearchQuality(quality)
            }
        }
    }

    private fun getSearchQuality(quality: String?): SearchQuality? {
        return when {
            quality == null -> null
            quality.contains("1080") -> SearchQuality.HD
            quality.contains("720") -> SearchQuality.HD
            quality.contains("BLURAY", ignoreCase = true) -> SearchQuality.BlueRay
            quality.contains("WEB", ignoreCase = true) -> SearchQuality.WebRip
            else -> null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = getSafe("$mainUrl/?s=$query").document
        return document.select(".post-item, .movie-item, article.item, .search-result").mapNotNull { 
            it.toSearchResult() 
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = getSafe(url).document
        
        val title = document.selectFirst("h1.title, h1, .post-title")?.text()?.trim()
            ?: return null
            
        val poster = document.selectFirst(".poster img, img.poster, .thumbnail img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
        }
        
        val description = document.selectFirst(".description, .story, .plot, .synopsis, p.content")?.text()
        val year = document.selectFirst(".year, span.year")?.text()?.toIntOrNull()
            ?: Regex("(\\d{4})").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val tags = document.select(".genre a, .genres a, .tags a").map { it.text() }
        
        // Check for episodes
        val episodes = mutableListOf<Episode>()
        
        // Get seasons
        document.select(".seasons a, .season-list a, a.season").forEach { season ->
            val seasonHref = season.attr("href")
            val seasonNum = Regex("(\\d+)").find(season.text())?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            
            if (seasonHref.isNotBlank()) {
                try {
                    val seasonDoc = getSafe(seasonHref).document
                    seasonDoc.select(".episodes a, .episode-list a, a.episode").forEach { ep ->
                        val epHref = ep.attr("href")
                        val epTitle = ep.text().trim()
                        if (epHref.isNotBlank()) {
                            val episodeNum = Regex("(\\d+)").find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                            episodes.add(newEpisode(epHref) {
                                this.name = epTitle
                                this.season = seasonNum
                                this.episode = episodeNum
                            })
                        }
                    }
                } catch (e: Exception) {
                    // Skip problematic seasons
                }
            }
        }
        
        // Direct episode list
        if (episodes.isEmpty()) {
            document.select(".episodes a, .episode-list a, a.episode").forEach { ep ->
                val epHref = ep.attr("href")
                val epTitle = ep.text().trim()
                if (epHref.isNotBlank()) {
                    val episodeNum = Regex("(\\d+)").find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    episodes.add(newEpisode(epHref) {
                        this.name = epTitle
                        this.episode = episodeNum
                    })
                }
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
        val document = getSafe(data).document
        
        // Find server buttons/tabs
        document.select(".servers a, .server-list a, button[data-url], li[data-url]").forEach { server ->
            val serverUrl = server.attr("data-url").ifBlank { 
                server.attr("data-link").ifBlank { 
                    server.attr("href") 
                }
            }
            if (serverUrl.isNotBlank() && serverUrl.startsWith("http")) {
                loadExtractor(serverUrl, mainUrl, subtitleCallback, callback)
            }
        }
        
        // Find iframes
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank() && src.startsWith("http")) {
                loadExtractor(src, mainUrl, subtitleCallback, callback)
            }
        }
        
        // Direct video sources
        document.select("video source, source").forEach { source ->
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
        
        // Parse scripts
        document.select("script").forEach { script ->
            val scriptData = script.data()
            
            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").findAll(scriptData).forEach { match ->
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name - HLS",
                        url = match.groupValues[1],
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                    }
                )
            }
            
            Regex("""["'](https?://[^"']+\.mp4[^"']*)["']""").findAll(scriptData).forEach { match ->
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name - MP4",
                        url = match.groupValues[1],
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
