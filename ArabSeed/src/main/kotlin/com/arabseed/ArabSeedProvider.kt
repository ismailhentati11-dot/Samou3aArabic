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
class ArabSeedServer(val name: String, val url: String)

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
    private fun cleanLink(raw: String): String {
        val url = raw.trim()
        if (url.isEmpty()) {
            return ""
        }
        if (url.contains("youtube") || url.contains("imdb.com")) {
            return ""
        }
        var full = url
        if (url.startsWith("//")) {
            full = "https:" + url
        }
        if (!full.startsWith("http")) {
            return ""
        }
        return full
    }
    private fun addServer(name: String, raw: String, list: ArrayList<ArabSeedServer>) {
        val url = cleanLink(raw)
        if (url.isEmpty()) {
            return
        }
        for (s in list) {
            if (s.url == url) {
                return
            }
        }
        list.add(ArabSeedServer(name, url))
    }
    private fun collectServers(doc: Document, list: ArrayList<ArabSeedServer>) {
        for (li in doc.select("li[data-link]")) {
            addServer(li.select("span").text().trim(), li.attr("data-link"), list)
        }
        for (frame in doc.select("iframe")) {
            var src = frame.attr("src")
            if (src.isEmpty()) {
                src = frame.attr("data-src")
            }
            addServer("", src, list)
        }
        val attributes = listOf("data-url", "data-embed")
        for (attr in attributes) {
            for (el in doc.select("[$attr]")) {
                addServer("", el.attr(attr), list)
            }
        }
    }
    private fun hunterDecode(h: String, n: String, t: Int, e: Int): String {
        val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ+/"
        val digits = chars.substring(0, e)
        val delimiter = n[e]
        val out = StringBuilder()
        var i = 0
        while (i < h.length) {
            val piece = StringBuilder()
            while (i < h.length && h[i] != delimiter) {
                piece.append(h[i])
                i++
            }
            var part = piece.toString()
            for (j in n.indices) {
                part = part.replace(n[j].toString(), j.toString())
            }
            var value = 0L
            var power = 1L
            for (k in part.length - 1 downTo 0) {
                val d = digits.indexOf(part[k])
                if (d != -1) {
                    value += d * power
                }
                power *= e
            }
            out.append((value - t).toInt().toChar())
            i++
        }
        val bytes = out.toString().toByteArray(Charsets.ISO_8859_1)
        return String(bytes, Charsets.UTF_8)
    }
    private fun decodeAllBlocks(html: String): String {
        val result = StringBuilder()
        val blockRegex = Regex("""\}\("([^"]+)",\s*(\d+),\s*"([^"]+)",\s*(\d+),\s*(\d+),\s*(\d+)\)\)""")
        for (m in blockRegex.findAll(html)) {
            try {
                val h = m.groupValues[1]
                val n = m.groupValues[3]
                val t = m.groupValues[4].toInt()
                val e = m.groupValues[5].toInt()
                if (e < n.length) {
                    result.append(hunterDecode(h, n, t, e))
                    result.append("\n")
                }
            } catch (ex: Exception) {
            }
        }
        return result.toString()
    }
    private suspend fun resolveGovid(
        server: ArabSeedServer,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            val playHtml = app.get(server.url, referer = referer).text
            val playerMatch = Regex("""https?://govid\.live/e/[^"'\s<>\\]+""").find(playHtml)
            if (playerMatch == null) {
                return false
            }
            val playerUrl = playerMatch.value.replace("&amp;", "&")
            val playerHtml = app.get(playerUrl, referer = "https://govid.live/").text
            var allText = playerHtml + "\n" + decodeAllBlocks(playerHtml)
            allText = allText.replace("\\/", "/")

            val videoLinks = ArrayList<String>()
            val absoluteRegex = Regex("""https?://[^"'\s<>\\]+\.(?:m3u8|mp4)[^"'\s<>\\]*""")
            for (m in absoluteRegex.findAll(allText)) {
                if (!videoLinks.contains(m.value)) {
                    videoLinks.add(m.value)
                }
            }
            val relativeRegex = Regex("""["'](/[^"'\s<>\\]+\.(?:m3u8|mp4)[^"'\s<>\\]*)["']""")
            for (m in relativeRegex.findAll(allText)) {
                val full = "https://govid.live" + m.groupValues[1]
                if (!videoLinks.contains(full)) {
                    videoLinks.add(full)
                }
            }

            var label = this.name
            if (server.name.isNotEmpty()) {
                label = this.name + " " + server.name
            }

            for (video in videoLinks) {
                callback.invoke(
                    newExtractorLink(this.name, label, video) {
                        this.referer = "https://govid.live/"
                        this.quality = Qualities.Unknown.value
                        if (video.contains(".m3u8")) {
                            this.type = ExtractorLinkType.M3U8
                        } else {
                            this.type = ExtractorLinkType.VIDEO
                        }
                    }
                )
                found = true
            }
        } catch (e: Exception) {
        }
        return found
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
        val servers = ArrayList<ArabSeedServer>()
        collectServers(watchDoc, servers)
        if (servers.isEmpty()) {
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
                    collectServers(embedDoc, servers)
                } catch (e: Exception) {
                }
            }
        }

        var found = false
        val siteHost = mainUrl.substringAfter("//")

        for (server in servers) {
            if (server.url.contains("govid.live")) {
                val ok = resolveGovid(server, watchUrl, callback)
                if (ok) {
                    found = true
                } else {
                    loadExtractor(server.url, "$mainUrl/", subtitleCallback, callback)
                }
            } else if (server.url.contains(siteHost)) {
                try {
                    val innerDoc = app.get(server.url, referer = watchUrl).document
                    val inner = ArrayList<ArabSeedServer>()
                    collectServers(innerDoc, inner)
                    for (innerServer in inner) {
                        if (innerServer.url.contains("govid.live")) {
                            val ok = resolveGovid(innerServer, watchUrl, callback)
                            if (ok) {
                                found = true
                            }
                        } else {
                            loadExtractor(innerServer.url, "$mainUrl/", subtitleCallback, callback)
                            found = true
                        }
                    }
                } catch (e: Exception) {
                }
            } else {
                loadExtractor(server.url, "$mainUrl/", subtitleCallback, callback)
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