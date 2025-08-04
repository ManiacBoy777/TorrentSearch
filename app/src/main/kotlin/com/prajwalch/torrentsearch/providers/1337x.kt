package com.prajwalch.torrentsearch.providers

import com.prajwalch.torrentsearch.models.Category
import com.prajwalch.torrentsearch.models.InfoHashOrMagnetUri
import com.prajwalch.torrentsearch.models.Torrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class ThirteenThreeSevenX : SearchProvider {
    override val info = SearchProviderInfo(
        id = "1337x",
        name = "1337x",
        url = "https://1337x.to",
        specializedCategory = Category.All, // adjust if you're scoping to a specific category
        safetyStatus = SearchProviderSafetyStatus.Safe,
        enabled = true,
    )

    override suspend fun search(query: String, context: SearchContext): List<Torrent> {
        // 1337x search uses /search/{query}/1/ for first page
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val requestUrl = "${info.url}/search/$encoded/1/"
        val responseHtml = context.httpClient.get(url = requestUrl)

        return withContext(Dispatchers.Default) {
            parseSearchResults(html = responseHtml, context = context)
        }
    }

    private suspend fun parseSearchResults(html: String, context: SearchContext): List<Torrent> {
        val doc = Jsoup.parse(html)
        // The search results are in a table; selector may need tweaking if site changes.
        // TODO: Verify the correct table selector on live 1337x.to (e.g., table[class*=table-list] etc.)
        val rows = doc.select("table > tbody > tr")
        if (rows.isEmpty()) return emptyList()

        val torrents = mutableListOf<Torrent>()
        for (tr in rows) {
            val detailAnchor = tr.selectFirst("a[href^=/torrent/]") ?: continue
            val torrentName = detailAnchor.ownText().ifBlank { detailAnchor.text() }

            // Build detail page URL
            val detailPath = detailAnchor.attr("href")
            val detailUrl = "${info.url}$detailPath"

            // Seeds / leeches / size may be present in the row; attempt to extract.
            // The exact column indexes may vary; adjust if the live DOM differs.
            val seeders = tr.selectFirst("td:nth-child(3)")?.ownText()?.replace(",", "")?.toUIntOrNull() ?: 0u
            val leechers = tr.selectFirst("td:nth-child(4)")?.ownText()?.replace(",", "")?.toUIntOrNull() ?: 0u
            val size = tr.selectFirst("td:nth-child(5)")?.ownText() ?: "Unknown"
            val uploadDate = tr.selectFirst("td:nth-child(6)")?.ownText() ?: ""

            // Fetch detail page to get magnet link (1337x puts magnet on detail page). 2
            val detailHtml = context.httpClient.get(url = detailUrl)
            val magnetUri = parseMagnetFromDetailPage(detailHtml) ?: continue

            val torrent = Torrent(
                name = torrentName,
                size = size,
                seeders = seeders,
                peers = leechers,
                providerId = info.id,
                providerName = info.name,
                uploadDate = uploadDate,
                category = info.specializedCategory,
                descriptionPageUrl = detailUrl,
                infoHashOrMagnetUri = InfoHashOrMagnetUri.MagnetUri(magnetUri),
            )
            torrents += torrent
        }

        return torrents
    }

    /** Extract magnet link from the detail page HTML. */
    private fun parseMagnetFromDetailPage(html: String): String? {
        val doc: Document = Jsoup.parse(html)
        // Based on scraping guides, the magnet link is an <a> with href starting with magnet:
        // older userscripts targeted something like: ul > li > a[onclick="javascript: count(this);"]
        // but the reliable check is href^="magnet:"
        // TODO: Confirm if any intermediate JavaScript redirection is required; if so, emulate or fetch the final href.
        val magnetAnchor = doc.selectFirst("a[href^=magnet:]") ?: return null
        return magnetAnchor.attr("href")
    }
}