package com.mazekine.rss.shell

import com.rometools.rome.feed.rss.Channel
import com.rometools.rome.feed.rss.Description
import com.rometools.rome.feed.rss.Guid
import com.rometools.rome.feed.rss.Item
import com.rometools.rome.feed.rss.Source
import com.rometools.rome.io.WireFeedOutput
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.Date

private val logger = LoggerFactory.getLogger("RssService")

fun Application.rssShellModule(config: AppConfig, database: Database) {
    install(CallLogging)

    routing {
        get("/health") {
            val payload = """{"status":"ok","version":"${config.app.name} ${config.app.version}"}"""
            call.respondText(payload, ContentType.Application.Json)
        }

        get("/articles/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
            if (id == null) {
                call.respondText("Invalid article id", status = HttpStatusCode.BadRequest)
                return@get
            }
            val url = database.findArticleUrl(id)
            if (url == null) {
                call.respondText("Not found", status = HttpStatusCode.NotFound)
            } else {
                call.respondRedirect(url, permanent = false)
            }
        }

        get("/rss/{externalId}") {
            val externalId = call.parameters["externalId"]
            if (externalId.isNullOrBlank()) {
                call.respondText("Missing external id", status = HttpStatusCode.BadRequest)
                return@get
            }
            val feed = database.findActiveFeed(externalId)
            if (feed == null) {
                call.respondText("Feed not found", status = HttpStatusCode.NotFound)
                return@get
            }

            val articles = database.findArticlesForFeed(feed.id)
            val baseUrl = config.baseUrl()
            val feedUrl = "$baseUrl/rss/${feed.externalId}"
            val rss = buildRss(config, feedUrl, baseUrl, feed, articles)
            call.respondText(rss, ContentType.Application.Xml.withCharset(Charsets.UTF_8))
        }
    }
}

private fun buildRss(
    config: AppConfig,
    feedUrl: String,
    baseUrl: String,
    feed: Feed,
    articles: List<Article>
): String {
    val channel = Channel("rss_2.0").apply {
        title = feed.title
        description = feed.description
        link = feed.link ?: feedUrl
        language = config.feedDefaults.language
        generator = "${config.app.name} ${config.app.version}"
        ttl = secondsToMinutes(config.feedDefaults.ttlSeconds)
        lastBuildDate = Date.from(Instant.now())
        items = articles.map { it.toItem(baseUrl) }
    }

    val output = WireFeedOutput()
    val xml = output.outputString(channel)
    logger.debug("Generated RSS for feed ${feed.externalId} with ${articles.size} articles")
    return xml
}

private fun Article.toItem(baseUrl: String): Item {
    val guidUrl = "$baseUrl/articles/$id"
    val item = Item()
    item.title = title ?: url ?: "Untitled"
    item.link = url
    item.guid = Guid().apply {
        value = guidUrl
        isPermaLink = true
    }
    val published = pubDate ?: createdAt
    item.pubDate = Date.from(published)
    note?.let {
        item.description = Description().apply {
            type = "text/plain"
            value = it
        }
    }
    outletName?.let {
        item.source = Source().apply {
            url = this@toItem.url
            value = it
        }
    }
    return item
}

private fun secondsToMinutes(seconds: Int): Int {
    if (seconds <= 0) return 0
    return (seconds + 59) / 60
}