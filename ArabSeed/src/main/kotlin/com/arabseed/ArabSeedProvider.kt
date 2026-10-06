package com.arabseed

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class ArabSeed : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://arabseeds.watch"
    override var name = "ArabSeed"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override val mainPage = mainPageOf(
        "$mainUrl/category/films/" to "الأفلام",
        "$mainUrl/category/tv/" to "المسلسلات",
        "$mainUrl/category/films/foreign-movies/" to "أفلام أجنبي",
        "$mainUrl/category/films/arabic-movies/" to "أفلام عربي",
        "$mainUrl/category/tv/foreign-series/" to "مسلسلات أجنبي",
        "$mainUrl/category/tv/turkish-series/" to "مسلسلات تركي",
        "$mainUrl/category/tv/arabic-series/" to "مسلسلات عربي",
        "$mainUrl/category/tv/asian-series/" to "مسلسلات آسيوية",
        "$mainUrl/category/anime/" to "أنمي"
    )
    private fun cleanTitle(raw: String): String {
        var t = raw.trim()
        t = t.replace(Regex("""\s*الحلقة\s*\d+.*"""), "")
        t = t.replace("مشاهدة ", "")
        t = t.replace(" مترجمة", "")
        t = t.replace(" مترجم", "")
        return t.trim()
    }
    private fun seasonFromText(text: String): Int {
        val digits = Regex("""الموسم\s*(\d+)""").find(text)
        if (digits != null) {
            val n = digits.groupValues[1].toIntOrNull()
            if (n != null) {
                return n
            }
        }
        val after = text.substringAfter("الموسم")
        if (after.contains("الاول") || after.contains("الأول")) return 1
        if (after.contains("الثاني") || after.contains("الثانى")) return 2
        if (after.contains("الثالث")) return 3
        if (after.contains("الرابع")) return 4
        if (after.contains("الخامس")) return 5
        if (after.contains("السادس")) return 6
        if (after.contains("السابع")) return 7
        if (after.contains("الثامن")) return 8
        if (after.contains("التاسع")) return 9
        if (after.contains("العاشر")) return 10
        return 1
    }
    private fun Element.toSearchResponse(): SearchResponse? {
        val href = fixUrl(this.attr("href").trim())
        if (href.isEmpty()) {
            return null
        }

        var rawTitle = this.attr("title")
        if (rawTitle.isEmpty()) {
            rawTitle = this.select("h3").text()
        }
        val title = cleanTitle(rawTitle)
        if (title.isEmpty()) {
            return null
        }
        var poster: String? = null
        val img = this.selectFirst("img")
        if (img != null) {
            poster = img.attr("data-src")
            if (poster.isNullOrEmpty()) {
                poster = img.attr("src")
            }
        }
        if (rawTitle.contains("فيلم")) {
            return newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        } else {
            return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        var url = request.data
        if (page > 1) {
            url = request.data + "page/" + page + "/"
        }
        val doc = app.get(url).document
        val list = ArrayList<SearchResponse>()
        for (card in doc.select("a.movie__block")) {
            val item = card.toSearchResponse()
            if (item != null) {
                list.add(item)
            }
        }
        return newHomePageResponse(request.name, list.distinctBy { it.name })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/find/?word=$encoded").document
        val list = ArrayList<SearchResponse>()
        for (card in doc.select("a.movie__block")) {
            val item = card.toSearchResponse()
            if (item != null) {
                list.add(item)
            }
        }
        return list.distinctBy { it.name }
    }

    private suspend fun addEpisodes(doc: Document, season: Int, list: ArrayList<Episode>) {
        val links = doc.select("ul.episodes__list li a")
        for (a in links) {
            val href = fixUrl(a.attr("href").trim())
            val number = a.select(".epi__num b").text().trim().toIntOrNull()
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
        val doc = app.get(url).document
        var rawTitle = doc.select("h1.post__name").text()
        if (rawTitle.isEmpty()) {
            rawTitle = doc.select("title").text().split(" | ")[0]
        }
        val title = cleanTitle(rawTitle)
        var poster = doc.select(".poster__single img").attr("src")
        if (poster.isEmpty()) {
            poster = doc.select("meta[property=og:image]").attr("content")
        }
        var plot = doc.select(".post__story p").text().trim()
        if (plot.isEmpty()) {
            plot = doc.select("p.post__content").text().trim()
        }
        var year: Int? = null
        val yearTag = doc.selectFirst("a[href*=release-year]")
        if (yearTag != null) {
            year = yearTag.text().trim().toIntOrNull()
        }
        var rating: Int? = null
        val rateText = doc.select(".rate__txt").text()
        val rateMatch = Regex("""\d+(\.\d+)?""").find(rateText)
        if (rateMatch != null) {
            val d = rateMatch.value.toDoubleOrNull()
            if (d != null) {
                rating = (d + 0.5).toInt()
            }
        }
        val tags = ArrayList<String>()
        for (g in doc.select("ul.tags__list a[href*=/genre/]")) {
            tags.add(g.text().trim())
        }
        val actors = ArrayList<ActorData>()
        for (p in doc.select(".persons__list li a")) {
            val actorName = p.select(".name").text().trim()
            if (actorName.isNotEmpty()) {
                actors.add(ActorData(actor = Actor(actorName)))
            }
        }
        val hasEpisodes = doc.select("ul.episodes__list li a").isNotEmpty()
        val isMovie = rawTitle.contains("فيلم") || !hasEpisodes

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.actors = actors
                this.score = Score.from10(rating)
            }
        } else {
            val episodes = ArrayList<Episode>()
            var currentSeason = seasonFromText(rawTitle)
            val selected = doc.selectFirst("#seasons__list ul li.selected")
            if (selected != null) {
                currentSeason = seasonFromText(selected.text())
            }
            addEpisodes(doc, currentSeason, episodes)
            for (item in doc.select("#seasons__list ul li")) {
                if (item.hasClass("selected")) {
                    continue
                }
                val a = item.selectFirst("a")
                if (a == null) {
                    continue
                }
                val seasonUrl = fixUrl(a.attr("href").trim())
                val seasonNumber = seasonFromText(item.text())
                try {
                    val seasonDoc = app.get(seasonUrl).document
                    addEpisodes(seasonDoc, seasonNumber, episodes)
                } catch (e: Exception) {
                }
            }

            val sorted = episodes.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode }))

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sorted) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.actors = actors
                this.score = Score.from10(rating)
            }
        }
    }
    private fun addCandidate(raw: String, list: ArrayList<String>) {
        val url = raw.trim()
        if (url.isEmpty()) {
            return
        }
        if (url.contains("youtube") || url.contains("imdb.com")) {
            return
        }
        var full = url
        if (url.startsWith("//")) {
            full = "https:" + url
        }
        if (!full.startsWith("http")) {
            return
        }
        if (!list.contains(full)) {
            list.add(full)
        }
    }
    private fun collectLinks(doc: Document, list: ArrayList<String>) {
        for (frame in doc.select("iframe")) {
            var src = frame.attr("src")
            if (src.isEmpty()) {
                src = frame.attr("data-src")
            }
            addCandidate(src, list)
        }
        val attributes = listOf("data-link", "data-url", "data-embed")
        for (attr in attributes) {
            for (el in doc.select("[$attr]")) {
                addCandidate(el.attr(attr), list)
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
        if (!watchUrl.endsWith("/watch/")) {
            watchUrl = watchUrl.trimEnd('/') + "/watch/"
        }

        val watchDoc = app.get(watchUrl, referer = data).document
        val candidates = ArrayList<String>()
        collectLinks(watchDoc, candidates)
        if (candidates.isEmpty()) {
            var postId = watchDoc.select("#like__post").attr("data-id")
            if (postId.isEmpty()) {
                try {
                    postId = app.get(data).document.select("#like__post").attr("data-id")
                } catch (e: Exception) {
                    postId = ""
                }
            }
            if (postId.isNotEmpty()) {
                try {
                    val embedDoc = app.get("$mainUrl/embeds/?id=$postId", referer = watchUrl).document
                    collectLinks(embedDoc, candidates)
                } catch (e: Exception) {
                }
            }
        }

        var found = false
        val siteHost = mainUrl.substringAfter("//")

        for (link in candidates) {
            if (link.contains(siteHost)) {
                try {
                    val innerDoc = app.get(link, referer = watchUrl).document
                    val inner = ArrayList<String>()
                    collectLinks(innerDoc, inner)
                    for (innerLink in inner) {
                        loadExtractor(innerLink, "$mainUrl/", subtitleCallback, callback)
                        found = true
                    }
                    for (source in innerDoc.select("source[src]")) {
                        val videoUrl = source.attr("abs:src").trim()
                        if (videoUrl.isNotEmpty()) {
                            callback.invoke(
                                newExtractorLink(this.name, this.name, videoUrl) {
                                    this.referer = "$mainUrl/"
                                    this.quality = Qualities.Unknown.value
                                    this.type = ExtractorLinkType.VIDEO
                                }
                            )
                            found = true
                        }
                    }
                } catch (e: Exception) {
                }
            } else {
                loadExtractor(link, "$mainUrl/", subtitleCallback, callback)
                found = true
            }
        }
        for (source in watchDoc.select("source[src]")) {
            val videoUrl = source.attr("abs:src").trim()
            if (videoUrl.isNotEmpty()) {
                callback.invoke(
                    newExtractorLink(this.name, this.name, videoUrl) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                        this.type = ExtractorLinkType.VIDEO
                    }
                )
                found = true
            }
        }

        return found
    }
}