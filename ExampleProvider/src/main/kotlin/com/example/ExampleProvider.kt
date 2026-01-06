package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class ExampleProvider : MainAPI() {
    override var mainUrl = "https://example.com"
    override var name = "ExampleProvider"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val data = listOf(
            HomePageList(
                "Featured",
                listOf(
                    newMovieSearchResponse("Example Movie", "example_movie", TvType.Movie) {
                        this.posterUrl = "https://via.placeholder.com/150"
                    }
                ),
                isHorizontalImages = true
            )
        )
        return newHomePageResponse(data, false)
    }

    override suspend fun load(url: String): LoadResponse? {
        return newMovieLoadResponse("Example Movie", url, TvType.Movie, url) {
            this.posterUrl = "https://via.placeholder.com/150"
            this.plot = "This is an example movie."
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback.invoke(
            newExtractorLink(
                this.name,
                "Example Source",
                "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
                INFER_TYPE
            ) {
                this.referer = "https://example.com"
                this.quality = Qualities.P1080.value
            }
        )
        return true
    }
}
