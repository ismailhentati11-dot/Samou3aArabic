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

    // outil pour passer la protection Cloudflare
    private val cfKiller = CloudflareKiller()

    // pages d'accueil : categories du menu du site (avec le numero de page a la fin)
    override val mainPage = mainPageOf(
        "$mainUrl/category/%d8%a7%d9%84%d8%a7%d9%81%d9%84%d8%a7%d9%85/" to "الأفلام",
        "$mainUrl/category/%d8%a7%d9%84%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa/" to "المسلسلات"
    )

    // ---------- OUTILS ----------

    // lit le premier nombre d'un texte (ex: "الموسم 04" -> 4), sinon 1
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

    // transforme une carte (article) du site en resultat de recherche
    private fun Element.toSearchResponse(): SearchResponse? {
        val linkTag = this.selectFirst("a")
        if (linkTag == null) {
            return null
        }
        val href = fixUrl(linkTag.attr("href").trim())
        if (href.isEmpty()) {
            return null
        }

        // titre de la carte (on garde seulement le texte, sans le genre)
        var title = ""
        val titleTag = this.selectFirst("li[aria-label=title]")
        if (titleTag != null) {
            title = titleTag.ownText().trim()
        }
        if (title.isEmpty()) {
            return null
        }

        // si la carte parle d'une saison, on l'ajoute au titre
        val tabs = this.select("li[aria-label=tab]")
        for (tab in tabs) {
            val tabText = tab.text().trim()
            if (tabText.contains("الموسم")) {
                title = title + " " + tabText
            }
        }

        // image de la carte
        var poster: String? = null
        val imgTag = this.selectFirst("img")
        if (imgTag != null) {
            poster = imgTag.attr("data-src")
            if (poster.isNullOrEmpty()) {
                poster = imgTag.attr("src")
            }
        }

        // une carte "episode" ou un lien /selary/ = une serie
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

    // ---------- ACCUEIL ----------

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

    // ---------- RECHERCHE ----------

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val result = ArrayList<SearchResponse>()

        // on lit les 3 premieres pages de resultats
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

        // une serie apparait une fois par episode : on garde un seul resultat par nom
        return result.distinctBy { it.name }
    }

    // ---------- LES EPISODES D'UNE PAGE ----------

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

    // ---------- DETAILS ----------

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, interceptor = cfKiller).document

        // titre (sans le numero d'episode)
        var title = ""
        val h1 = doc.selectFirst("h1")
        if (h1 != null) {
            title = h1.text()
        }
        title = title.replace(Regex("""\s*الحلقة.*"""), "").trim()
        if (title.isEmpty()) {
            title = doc.select("title").text().split(" | ")[0]
        }

        // image, resume, annee
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

        // bande-annonce (youtube)
        val trailerUrl = doc.select("iframe").attr("data-src")

        // recommandations
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

        // est-ce une serie ? (liste d'episodes, liste de saisons ou lien /selary/)
        val hasEpisodes = doc.select("ul#episodes li").isNotEmpty()
        val hasSeasons = doc.select("section[aria-label=seasons]").isNotEmpty()
        val isSeries = hasEpisodes || hasSeasons || url.contains("/selary/")

        if (isSeries) {
            val episodes = ArrayList<Episode>()

            // episodes de la saison de la page actuelle
            val currentSeason = readNumber(doc.select("span[aria-label=season-title]").text())
            addEpisodes(doc, currentSeason, episodes)

            // episodes des autres saisons
            val seasonItems = doc.select("section[aria-label=seasons] ul li")
            for (item in seasonItems) {
                if (item.hasClass("active")) {
                    continue
                }
                val a = item.selectFirst("a")
                if (a == null) {
                    continue
                }
                // attention : le lien contient un espace a la fin, on le retire avec trim()
                val seasonUrl = fixUrl(a.attr("href").trim())
                val seasonNumber = readNumber(a.text())
                try {
                    val seasonDoc = app.get(seasonUrl, interceptor = cfKiller).document
                    addEpisodes(seasonDoc, seasonNumber, episodes)
                } catch (e: Exception) {
                    // si une saison ne charge pas, on passe a la suivante
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

    // ---------- LIENS VIDEO ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // la page de lecture = adresse de la page + /watching/
        var watchUrl = data
        if (!watchUrl.endsWith("/watching/")) {
            watchUrl = watchUrl.trimEnd('/') + "/watching/"
        }

        val doc = app.get(watchUrl, referer = "$mainUrl/", interceptor = cfKiller).document
        var found = false

        // 1) liens de telechargement par qualite
        for (a in doc.select("li[aria-label=quality] a")) {
            val href = a.attr("href").trim()
            if (href.isEmpty()) {
                continue
            }

            // qualite lue dans le texte du lien (360, 480, 720 ou 1080)
            var quality = Qualities.Unknown.value
            val digits = Regex("""\d{3,4}""").find(a.text())
            if (digits != null) {
                val value = digits.value.toIntOrNull()
                if (value == 360 || value == 480 || value == 720 || value == 1080) {
                    quality = value
                }
            }

            if (href.contains("cimanowtv")) {
                // lien direct du site
                callback.invoke(
                    newExtractorLink(this.name, this.name + " " + quality, href) {
                        this.referer = "$mainUrl/"
                        this.quality = quality
                    }
                )
                found = true
            } else {
                // autre hebergeur : on laisse CloudStream trouver le bon extracteur
                loadExtractor(href, "$mainUrl/", subtitleCallback, callback)
                found = true
            }
        }

        // 2) lecteurs integres (iframe), sauf la bande-annonce youtube
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