package com.arabic

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.delay

class CimaLeek : MainAPI() {
    override var mainUrl = "https://cimaleek.to"
    override var name = "CimaLeek"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "$mainUrl/movies-list/?page=" to "أفلام",
        "$mainUrl/series-list/?page=" to "مسلسلات",
        "$mainUrl/category/anime-movies/?page=" to "أفلام انمي",
        "$mainUrl/category/anime-series/?page=" to "مسلسلات انمي",
        "$mainUrl/category/asian-aflam/?page=" to "أفلام آسيوية",
        "$mainUrl/category/asian-series/?page=" to "مسلسلات آسيوية"
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
            
            // Small delay to be "human-like"
            delay(1000) 
            
            app.get(url, headers = newHeaders)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = getSafe(request.data + page).document
        val home = document.select(".MovieBlock, .movie-card, article").mapNotNull { 
            it.toSearchResult() 
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a") ?: return null
        val title = selectFirst("h3, .title, span.title")?.text()?.trim() 
            ?: anchor.attr("title").ifBlank { anchor.text() }
        if (title.isBlank()) return null
        
        val href = anchor.attr("href")
        if (href.isBlank()) return null
        
        val posterUrl = selectFirst("img")?.let { img ->
            img.attr("data-src").ifBlank { 
                img.attr("src").ifBlank { 
                    img.attr("data-lazy-src") 
                }
            }
        }
        
        val quality = selectFirst(".quality, .ql")?.text()
        
        val isSeries = href.contains("/series/") || href.contains("/seasons/") || 
                       href.contains("/episodes/")
        
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.quality = getSearchQuality(quality)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
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
            quality.contains("HDCAM", ignoreCase = true) -> SearchQuality.HdCam
            quality.contains("CAM", ignoreCase = true) -> SearchQuality.Cam
            else -> null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = getSafe("$mainUrl/?s=$query").document
        return document.select(".MovieBlock, .movie-card, article").mapNotNull { 
            it.toSearchResult() 
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = getSafe(url).document
        
        val title = document.selectFirst("h1.Title, h1.title, .post-title h1")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: return null
            
        val poster = document.selectFirst(".poster img, .Poster img, img.poster")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
        }
        
        val description = document.selectFirst(".story, .description, .plot, .synopsis")?.text()
        val year = document.selectFirst(".year, span.year")?.text()?.toIntOrNull()
            ?: Regex("(\\d{4})").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val tags = document.select(".genres a, .genre a, .category a").map { it.text() }
        val duration = document.selectFirst(".runtime, .duration")?.text()?.let {
            Regex("(\\d+)").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
        
        // Check for seasons/episodes
        val episodes = mutableListOf<Episode>()
        
        // If this is a series page, get seasons
        document.select(".seasons-list a, .Seasons a, a.season").forEach { season ->
            val seasonHref = season.attr("href")
            val seasonNum = Regex("(\\d+)").find(season.text())?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            
            if (seasonHref.isNotBlank()) {
                try {
                    val seasonDoc = getSafe(seasonHref).document
                    seasonDoc.select(".episodes-list a, .EpsList a, a.episode").forEach { ep ->
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
        
        // Direct episode list (for season pages)
        if (episodes.isEmpty()) {
            document.select(".episodes-list a, .EpsList a, a.episode").forEach { ep ->
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
                this.duration = duration
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
                this.duration = duration
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
        
        // Find server tabs/buttons
        document.select(".servers a, .serversList li, li[data-url], button[data-url]").forEach { server ->
            val serverUrl = server.attr("data-url").ifBlank { 
                server.attr("data-link").ifBlank { 
                    server.attr("href") 
                }
            }
            if (serverUrl.isNotBlank() && serverUrl.startsWith("http")) {
                loadExtractor(serverUrl, mainUrl, subtitleCallback, callback)
            }
        }
        
        // Find iframes directly on the page
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank() && src.startsWith("http")) {
                loadExtractor(src, mainUrl, subtitleCallback, callback)
            }
        }
        
        // Check for video.js or direct video sources
        document.select("video source, source").forEach { source ->
            val src = source.attr("src")
            if (src.isNotBlank() && (src.contains(".mp4") || src.contains(".m3u8"))) {
                val quality = when {
                    src.contains("1080") -> Qualities.P1080.value
                    src.contains("720") -> Qualities.P720.value
                    src.contains("480") -> Qualities.P480.value
                    src.contains("360") -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = src,
                        type = if (src.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.quality = quality
                    }
                )
            }
        }
        
        // Parse inline scripts for video URLs
        document.select("script").forEach { script ->
            val scriptData = script.data()
            
            // Look for HLS streams
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
            
            // Look for MP4 files
            Regex("""["'](https?://[^"']+\.mp4[^"']*)["']""").findAll(scriptData).forEach { match ->
                val mp4Url = match.groupValues[1]
                val quality = when {
                    mp4Url.contains("1080") -> Qualities.P1080.value
                    mp4Url.contains("720") -> Qualities.P720.value
                    mp4Url.contains("480") -> Qualities.P480.value
                    else -> Qualities.Unknown.value
                }
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name - MP4",
                        url = mp4Url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.quality = quality
                    }
                )
            }
        }
        
        return true
    }
}
