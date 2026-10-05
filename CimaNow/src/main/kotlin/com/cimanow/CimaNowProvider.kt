package com.cimanow

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class CimaNow : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://cimanow.cc"
    override var name = "CimaNow"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    private val cfKiller = CloudflareKiller()
    override val mainPage = mainPageOf(
        "$mainUrl/category/%d8%a7%d9%84%d8%a7%d9%81%d9%84%d8%a7%d9%85/" to "الأفلام",
        "$mainUrl/category/%d8%a7%d9%84%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa/" to "المسلسلات"
    )
    private fun readNumber(text: String): Int {
        val found = Regex("""\d+""").find(text)
        if (found == null) {
            return 1
        }
        val number = found.value.toIntOrNull()
        if (number == null) {
            return 1
        }
        return number
    }
    private fun Element.toSearchResponse(): SearchResponse? {
        val linkTag = this.selectFirst("a")
        if (linkTag == null) {
            return null
        }
        val href = fixUrl(linkTag.attr("href").trim())
        if (href.isEmpty()) {
            return null
        }
        var title = ""
        val titleTag = this.selectFirst("li[aria-label=title]")
        if (titleTag != null) {
            title = titleTag.ownText().trim()
        }
        if (title.isEmpty()) {
            return null
        }
        val tabs = this.select("li[aria-label=tab]")
        for (tab in tabs) {
            val tabText = tab.text().trim()
            if (tabText.contains("الموسم")) {
                title = title + " " + tabText
            }
        }
        var poster: String? = null
        val imgTag = this.selectFirst("img")
        if (imgTag != null) {
            poster = imgTag.attr("data-src")
            if (poster.isNullOrEmpty()) {
                poster = imgTag.attr("src")
            }
        }
        val hasEpisode = this.select("li[aria-label=episode]").isNotEmpty()
        val isSeries = hasEpisode || href.contains("/selary/")

        if (isSeries) {
            return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            return newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + "page/" + page + "/"
        val doc = app.get(url, interceptor = cfKiller).document
        val list = ArrayList<SearchResponse>()
        for (card in doc.select("article[aria-label=post]")) {
            val item = card.toSearchResponse()
            if (item != null) {
                list.add(item)
            }
        }
        return newHomePageResponse(request.name, list)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val result = ArrayList<SearchResponse>()
        for (page in 1..3) {
            var url = mainUrl + "/?s=" + encoded
            if (page > 1) {
                url = mainUrl + "/page/" + page + "/?s=" + encoded
            }
            try {
                val doc = app.get(url, interceptor = cfKiller).document
                val cards = doc.select("article[aria-label=post]")
                if (cards.isEmpty()) {
                    break
                }
                for (card in cards) {
                    val item = card.toSearchResponse()
                    if (item != null) {
                        result.add(item)
                    }
                }
            } catch (e: Exception) {
                break
            }
        }
        return result.distinctBy { it.name }
    }

    private suspend fun addEpisodes(doc: Document, season: Int, list: ArrayList<Episode>) {
        val links = doc.select("ul#episodes li a")
        for (a in links) {
            val href = fixUrl(a.attr("href").trim())
            val number = a.text().trim().toIntOrNull()
            list.add(
                newEpisode(href) {
                    this.name = "الحلقة " + number
                    this.season = season
                    this.episode = number
                }
            )
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, interceptor = cfKiller).document
        var title = ""
        val h1 = doc.selectFirst("h1")
        if (h1 != null) {
            title = h1.text()
        }
        title = title.replace(Regex("""\s*الحلقة.*"""), "").trim()
        if (title.isEmpty()) {
            title = doc.select("title").text().split(" | ")[0]
        }
        val poster = doc.select("meta[property=og:image]").attr("content")

        var plot = ""
        val plotTag = doc.selectFirst("ul#details li p")
        if (plotTag != null) {
            plot = plotTag.text().substringBefore("مشاهدة وتحميل").trim()
        }

        var year: Int? = null
        val yearTag = doc.selectFirst("a[href*=release-year]")
        if (yearTag != null) {
            year = yearTag.text().trim().toIntOrNull()
        }
        val trailerUrl = doc.select("iframe").attr("data-src")
        val recommendations = ArrayList<SearchResponse>()
        for (a in doc.select("ul#related li a")) {
            val recHref = fixUrl(a.attr("href").trim())
            val images = a.select("img")
            if (images.isEmpty() || recHref.isEmpty()) {
                continue
            }
            val lastImg = images.last()
            if (lastImg == null) {
                continue
            }
            val recName = lastImg.attr("alt")
            val recPoster = lastImg.attr("src")
            if (recHref.contains("/selary/")) {
                recommendations.add(
                    newTvSeriesSearchResponse(recName, recHref, TvType.TvSeries) {
                        this.posterUrl = recPoster
                    }
                )
            } else {
                recommendations.add(
                    newMovieSearchResponse(recName, recHref, TvType.Movie) {
                        this.posterUrl = recPoster
                    }
                )
            }
        }
        val hasEpisodes = doc.select("ul#episodes li").isNotEmpty()
        val hasSeasons = doc.select("section[aria-label=seasons]").isNotEmpty()
        val isSeries = hasEpisodes || hasSeasons || url.contains("/selary/")

        if (isSeries) {
            val episodes = ArrayList<Episode>()
            val currentSeason = readNumber(doc.select("span[aria-label=season-title]").text())
            addEpisodes(doc, currentSeason, episodes)
            val seasonItems = doc.select("section[aria-label=seasons] ul li")
            for (item in seasonItems) {
                if (item.hasClass("active")) {
                    continue
                }
                val a = item.selectFirst("a")
                if (a == null) {
                    continue
                }
                val seasonUrl = fixUrl(a.attr("href").trim())
                val seasonNumber = readNumber(a.text())
                try {
                    val seasonDoc = app.get(seasonUrl, interceptor = cfKiller).document
                    addEpisodes(seasonDoc, seasonNumber, episodes)
                } catch (e: Exception) {
                }
            }

            val sorted = episodes.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode }))

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sorted) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.recommendations = recommendations
                if (trailerUrl.isNotEmpty()) {
                    addTrailer(trailerUrl)
                }
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.recommendations = recommendations
                if (trailerUrl.isNotEmpty()) {
                    addTrailer(trailerUrl)
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var watchUrl = data
        if (!watchUrl.endsWith("/watching/")) {
            watchUrl = watchUrl.trimEnd('/') + "/watching/"
        }

        val doc = app.get(watchUrl, referer = "$mainUrl/", interceptor = cfKiller).document
        var found = false
        for (a in doc.select("li[aria-label=quality] a")) {
            val href = a.attr("href").trim()
            if (href.isEmpty()) {
                continue
            }
            var quality = Qualities.Unknown.value
            val digits = Regex("""\d{3,4}""").find(a.text())
            if (digits != null) {
                val value = digits.value.toIntOrNull()
                if (value == 360 || value == 480 || value == 720 || value == 1080) {
                    quality = value
                }
            }

            if (href.contains("cimanowtv")) {
                callback.invoke(
                    newExtractorLink(this.name, this.name + " " + quality, href) {
                        this.referer = "$mainUrl/"
                        this.quality = quality
                    }
                )
                found = true
            } else {
                loadExtractor(href, "$mainUrl/", subtitleCallback, callback)
                found = true
            }
        }
        for (frame in doc.select("iframe")) {
            var src = frame.attr("src")
            if (src.isEmpty()) {
                src = frame.attr("data-src")
            }
            if (src.isEmpty() || src.contains("youtube")) {
                continue
            }
            loadExtractor(fixUrl(src), "$mainUrl/", subtitleCallback, callback)
            found = true
        }

        return found
    }
}