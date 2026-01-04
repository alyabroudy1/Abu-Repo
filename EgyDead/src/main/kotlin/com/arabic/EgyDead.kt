package com.arabic

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class EgyDead : MainAPI() {
    override var mainUrl = "https://egydead.skin"
    override var name = "EgyDead"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "$mainUrl/page/movies?page=" to "أفلام",
        "$mainUrl/episode/?page=" to "أحدث الحلقات",
        "$mainUrl/category/arabic-movies/?page=" to "أفلام عربية",
        "$mainUrl/category/anime-movie/?page=" to "أفلام انمي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div.Block--Item, article.MovieBlock, .blockItem").mapNotNull { 
            it.toSearchResult() 
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("h3, .title, a")?.text()?.trim() ?: return null
        val href = selectFirst("a")?.attr("href") ?: return null
        val posterUrl = selectFirst("img")?.let { 
            it.attr("data-src").ifBlank { it.attr("src") }
        }
        val year = Regex("(\\d{4})").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val quality = selectFirst(".quality, .ql")?.text()
        
        val isSeries = href.contains("/series/") || href.contains("/season/") || 
                       href.contains("مسلسل") || href.contains("/episode/")
        
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
            quality.contains("HDCAM") -> SearchQuality.HdCam
            quality.contains("CAM") -> SearchQuality.Cam
            quality.contains("BLURAY") -> SearchQuality.BlueRay
            quality.contains("WEB") -> SearchQuality.WebRip
            else -> null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.Block--Item, article.MovieBlock, .blockItem").mapNotNull { 
            it.toSearchResult() 
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.Title, .post-title h1, h1")?.text()?.trim()
            ?: return null
            
        val poster = document.selectFirst(".poster img, .Poster img, img.Poster")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }
        val description = document.selectFirst(".story p, .Description, .synopsis, .plot")?.text()
        val year = document.selectFirst(".year, .Year")?.text()?.toIntOrNull()
            ?: Regex("(\\d{4})").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val tags = document.select(".genres a, .category a").map { it.text() }
        
        // Check for episodes
        val episodes = mutableListOf<Episode>()
        
        // Season page - get all episodes
        document.select(".EpsList a, .episodes-list a, a.episode").forEach { ep ->
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
        
        // Series page - get seasons first
        document.select(".Seasons a, .seasons-list a").forEach { season ->
            val seasonHref = season.attr("href")
            if (seasonHref.isNotBlank()) {
                val seasonDoc = app.get(seasonHref).document
                val seasonNum = Regex("(\\d+)").find(season.text())?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                seasonDoc.select(".EpsList a, .episodes-list a, a.episode").forEach { ep ->
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
        
        // Find watch servers
        document.select(".WatchServers a, .serversList a, .servers a, li[data-link]").forEach { server ->
            val serverUrl = server.attr("data-link").ifBlank { 
                server.attr("href").ifBlank { server.attr("data-url") }
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
        
        // Check for download links that might contain direct videos
        document.select(".DownloadLinks a, .downloads a, a.download").forEach { dl ->
            val dlUrl = dl.attr("href")
            if (dlUrl.contains(".mp4") || dlUrl.contains(".m3u8")) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name - ${dl.text()}",
                        url = dlUrl,
                        type = if (dlUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                    }
                )
            }
        }
        
        // Parse scripts for video URLs
        document.select("script").forEach { script ->
            val scriptData = script.data()
            
            // Find m3u8 URLs
            Regex("""["'](https?://[^"']*\.m3u8[^"']*)["']""").findAll(scriptData).forEach { match ->
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
            
            // Find mp4 URLs
            Regex("""["'](https?://[^"']*\.mp4[^"']*)["']""").findAll(scriptData).forEach { match ->
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
