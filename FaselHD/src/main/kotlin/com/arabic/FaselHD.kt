package com.arabic

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.nicehttp.requestCreator
import org.jsoup.nodes.Element
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.ArrayList

class FaselHD : MainAPI() {
    override var mainUrl = "https://www.faselhds.biz"
    private val alternativeUrl = "https://faselhd.club"
    override var name = "FaselHD"
    override val hasMainPage = true
    override var lang = "ar"
    override val usesWebView = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    private val cfKiller = CloudflareKiller()

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }

    override val mainPage = mainPageOf(
        "$mainUrl/all-movies/page/" to "جميع الافلام",
        "$mainUrl/movies_top_views/page/" to "الافلام الاعلي مشاهدة",
        "$mainUrl/dubbed-movies/page/" to "الأفلام المدبلجة",
        "$mainUrl/movies_top_imdb/page/" to "الافلام الاعلي تقييما IMDB",
        "$mainUrl/series/page/" to "مسلسلات",
        "$mainUrl/recent_series/page/" to "المضاف حديثا",
        "$mainUrl/anime/page/" to "الأنمي",
    )

    private fun Element.toSearchResult(): SearchResponse? {
        val url = select("div.postDiv a").attr("href")
        if (url.isBlank()) return null
        
        val posterUrl = select("div.postDiv a div img").attr("data-src").ifBlank {
            select("div.postDiv a div img").attr("src")
        }
        val title = select("div.postDiv a div img").attr("alt")
        val quality = select(".quality").first()?.text()?.replace("1080p |-".toRegex(), "")
        val type = if (title.contains("فيلم")) TvType.Movie else TvType.TvSeries
        
        return newMovieSearchResponse(
            title.replace("الموسم الأول|برنامج|فيلم|مترجم|اون لاين|مسلسل|مشاهدة|انمي|أنمي".toRegex(), ""),
            url,
            type
        ) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(quality)
            this.posterHeaders = cfKiller.getCookieHeaders(alternativeUrl).toMap()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        var doc = app.get(request.data + page).document
        if (doc.select("title").text() == "Just a moment...") {
            doc = app.get(
                request.data.replace(mainUrl, alternativeUrl) + page,
                interceptor = cfKiller,
                timeout = 120
            ).document
        }
        val list = doc.select("div[id=\"postList\"] div[class=\"col-xl-2 col-lg-2 col-md-3 col-sm-3\"]")
            .mapNotNull { element ->
                element.toSearchResult()
            }
        return newHomePageResponse(request.name, list)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.replace(" ", "+")
        var d = app.get("$mainUrl/?s=$q").document
        if (d.select("title").text() == "Just a moment...") {
            d = app.get("$alternativeUrl/?s=$q", interceptor = cfKiller, timeout = 120).document
        }
        return d.select("div[id=\"postList\"] div[class=\"col-xl-2 col-lg-2 col-md-3 col-sm-3\"]")
            .mapNotNull {
                it.toSearchResult()
            }
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = url
            .replace("faselhd.cloud", "faselhds.biz")
            .replace("faselhd.center", "faselhds.biz")
            .replace("faselhd.club", "faselhds.biz")
        
        var doc = app.get(fixedUrl).document
        if (doc.select("title").text() == "Just a moment...") {
            doc = app.get(fixedUrl, interceptor = cfKiller, timeout = 120).document
        }
        
        val isMovie = doc.select("div.epAll").isEmpty()
        val posterUrl = doc.select("div.posterImg img").attr("src")
            .ifEmpty { doc.select("div.seasonDiv.active img").attr("data-src") }

        val year = doc.select("div[id=\"singleList\"] div[class=\"col-xl-6 col-lg-6 col-md-6 col-sm-6\"]")
            .firstOrNull { it.text().contains("سنة|موعد".toRegex()) }
            ?.text()?.getIntFromText()

        val title = doc.select("title").text()
            .replace(" - فاصل إعلاني", "")
            .replace("الموسم الأول|برنامج|فيلم|مترجم|اون لاين|مسلسل|مشاهدة|انمي|أنمي|$year".toRegex(), "")

        val duration = doc.select("div[id=\"singleList\"] div[class=\"col-xl-6 col-lg-6 col-md-6 col-sm-6\"]")
            .firstOrNull { it.text().contains("مدة|توقيت".toRegex()) }
            ?.text()?.getIntFromText()

        val tags = doc.select("div[id=\"singleList\"] div[class=\"col-xl-6 col-lg-6 col-md-6 col-sm-6\"]:contains(تصنيف الفيلم) a")
            .map { it.text() }

        val recommendations = doc.select("div#postList div.postDiv").mapNotNull {
            it.toSearchResult()
        }

        val synopsis = doc.select("div.singleDesc p").text()

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = synopsis
                this.duration = duration
                this.tags = tags
                this.recommendations = recommendations
                this.posterHeaders = cfKiller.getCookieHeaders(alternativeUrl).toMap()
            }
        } else {
            val episodes = ArrayList<Episode>()
            doc.select("div.epAll a").map {
                episodes.add(
                    newEpisode(it.attr("href")) {
                        this.name = it.text()
                        this.season = doc.select("div.seasonDiv.active div.title").text().getIntFromText() ?: 1
                        this.episode = it.text().getIntFromText()
                    }
                )
            }
            val seasonEpisodes = coroutineScope {
                doc.select("div[id=\"seasonList\"] div[class=\"col-xl-2 col-lg-3 col-md-6\"] div.seasonDiv")
                    .not(".active")
                    .map { seasonElement ->
                        async {
                            val id = seasonElement.attr("onclick").replace(".*\\/\\?p=|'".toRegex(), "")
                            var s = app.get("$mainUrl/?p=$id").document
                            if (s.select("title").text() == "Just a moment...") {
                                s = app.get("$alternativeUrl/?p=$id", interceptor = cfKiller).document
                            }
                            s.select("div.epAll a").map {
                                newEpisode(it.attr("href")) {
                                    this.name = it.text()
                                    this.season =
                                        s.select("div.seasonDiv.active div.title").text().getIntFromText()
                                    this.episode = it.text().getIntFromText()
                                }
                            }
                        }
                    }.awaitAll().flatten()
            }
            episodes.addAll(seasonEpisodes)
            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes.distinct().sortedBy { it.episode }
            ) {
                this.duration = duration
                this.posterUrl = posterUrl
                this.year = year
                this.plot = synopsis
                this.tags = tags
                this.recommendations = recommendations
                this.posterHeaders = cfKiller.getCookieHeaders(alternativeUrl).toMap()
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fixedData = data
            .replace("faselhd.cloud", "faselhds.biz")
            .replace("faselhd.center", "faselhds.biz")
            .replace("faselhd.club", "faselhds.biz")

        var doc = app.get(fixedData).document
        if (doc.select("title").text() == "Just a moment...") {
            doc = app.get(fixedData, interceptor = cfKiller).document
        }

        // Method 1: Try to get download link
        val downloadUrl = doc.select(".downloadLinks a").attr("href")
        
        // Method 2: Get iframe URL for WebView resolution
        val iframeUrl = doc.select("iframe[name=\"player_iframe\"]").attr("src")

        // Process download link
        if (downloadUrl.isNotBlank()) {
            try {
                val player = app.get(downloadUrl, interceptor = cfKiller, referer = mainUrl, timeout = 120).document
                val directLink = player.select("div.dl-link a").attr("href")
                if (directLink.isNotBlank()) {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "$name Download Source",
                            url = directLink,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            } catch (e: Exception) {
                // Download method failed, continue to iframe method
            }
        }

        // Process iframe with WebViewResolver - This bypasses ad redirects!
        if (iframeUrl.isNotBlank()) {
            try {
                val webView = WebViewResolver(
                    Regex("""\.m3u8""")
                ).resolveUsingWebView(
                    requestCreator("GET", iframeUrl, referer = mainUrl)
                ).first

                if (webView?.url != null) {
                    M3u8Helper.generateM3u8(
                        this.name,
                        webView.url.toString(),
                        referer = mainUrl
                    ).toList().forEach(callback)
                }
            } catch (e: Exception) {
                // WebView method failed
            }
        }

        // Fallback: Try to find m3u8/mp4 links directly in page scripts
        val html = doc.html()
        
        // Find m3u8 URLs
        Regex("""["'](https?://[^"']*\.m3u8[^"']*)["']""").findAll(html).forEach { match ->
            val url = match.groupValues[1]
            try {
                M3u8Helper.generateM3u8(
                    this.name,
                    url,
                    referer = mainUrl
                ).toList().forEach(callback)
            } catch (e: Exception) {
                // M3u8 generation failed for this URL
            }
        }

        // Find mp4 URLs
        Regex("""["'](https?://[^"']*\.mp4[^"']*)["']""").findAll(html).forEach { match ->
            val url = match.groupValues[1]
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "$name - MP4",
                    url = url,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        return true
    }
}
