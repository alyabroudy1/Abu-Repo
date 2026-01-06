package com.arabic

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.delay

class EgyBest : MainAPI() {
    override var mainUrl = "https://egybest.la"
    override var name = "EgyBest"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/movies?page=" to "أفلام",
        "$mainUrl/series?page=" to "مسلسلات",
        "$mainUrl/movies/arabic?page=" to "أفلام عربية",
        "$mainUrl/series/arabic?page=" to "مسلسلات عربية"
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
        val home = document.select(".movie, .card, article.item, .film-card").mapNotNull { 
            it.toSearchResult() 
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a") ?: return null
        val title = selectFirst("h3, .title, span.title, h2")?.text()?.trim() 
            ?: anchor.attr("title").ifBlank { return null }
        
        val href = anchor.attr("href")
        if (href.isBlank()) return null
        
        val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"
        
        val posterUrl = selectFirst("img")?.let { img ->
            img.attr("data-src").ifBlank { 
                img.attr("src").ifBlank { 
                    img.attr("data-lazy-src") 
                }
            }
        }
        
        val year = selectFirst(".year, span.year")?.text()?.toIntOrNull()
            ?: Regex("(\\d{4})").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val quality = selectFirst(".quality, .ql, span.quality")?.text()
        
        val isSeries = href.contains("/series/") || href.contains("مسلسل") || 
                       href.contains("/show/") || href.contains("/season/")
        
        return if (isSeries) {
            newTvSeriesSearchResponse(title, fullHref, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year = year
                this.quality = getSearchQuality(quality)
            }
        } else {
            newMovieSearchResponse(title, fullHref, TvType.Movie) {
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
            quality.contains("HDCAM", ignoreCase = true) -> SearchQuality.HdCam
            quality.contains("CAM", ignoreCase = true) -> SearchQuality.Cam
            else -> null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = getSafe("$mainUrl/?s=$query").document
        return document.select(".movie, .card, article.item, .film-card, .search-result").mapNotNull { 
            it.toSearchResult() 
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = getSafe(url).document
        
        val title = document.selectFirst("h1.title, h1, .movie-title, .page-title")?.text()?.trim()
            ?: return null
            
        val poster = document.selectFirst(".poster img, img.poster, .movie-img img, .thumbnail img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
        }
        
        val description = document.selectFirst(".story, .description, .plot, .synopsis, .overview")?.text()
        val year = document.selectFirst(".year, span.year, .info .year")?.text()?.toIntOrNull()
            ?: Regex("(\\d{4})").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val tags = document.select(".genre a, .genres a, .tags a, .category a").map { it.text() }
        val duration = document.selectFirst(".runtime, .duration, .time")?.text()?.let {
            Regex("(\\d+)").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
        
        // Check for seasons/episodes
        val episodes = mutableListOf<Episode>()
        
        // Get seasons
        document.select(".seasons a, .season-list a, a.season, .tabs-seasons a").forEach { season ->
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
                            val fullEpHref = if (epHref.startsWith("http")) epHref else "$mainUrl$epHref"
                            episodes.add(newEpisode(fullEpHref) {
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
        
        // Direct episode list on current page
        if (episodes.isEmpty()) {
            document.select(".episodes a, .episode-list a, a.episode, .eps a").forEach { ep ->
                val epHref = ep.attr("href")
                val epTitle = ep.text().trim()
                if (epHref.isNotBlank()) {
                    val episodeNum = Regex("(\\d+)").find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val fullEpHref = if (epHref.startsWith("http")) epHref else "$mainUrl$epHref"
                    episodes.add(newEpisode(fullEpHref) {
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
        
        // EgyBest often uses server tabs
        document.select(".servers a, .server-list a, .watch-servers a, button[data-url]").forEach { server ->
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
        
        // Check for direct download links
        document.select(".download a, .downloads a, a.dl, a[href*='download']").forEach { dl ->
            val dlUrl = dl.attr("href")
            if (dlUrl.contains(".mp4") || dlUrl.contains(".m3u8")) {
                val quality = when {
                    dl.text().contains("1080") || dlUrl.contains("1080") -> Qualities.P1080.value
                    dl.text().contains("720") || dlUrl.contains("720") -> Qualities.P720.value
                    dl.text().contains("480") || dlUrl.contains("480") -> Qualities.P480.value
                    else -> Qualities.Unknown.value
                }
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name - ${dl.text()}",
                        url = dlUrl,
                        type = if (dlUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.quality = quality
                    }
                )
            }
        }
        
        // Parse inline scripts
        document.select("script").forEach { script ->
            val scriptData = script.data()
            
            // Find HLS streams
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
            
            // Find MP4 files
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
