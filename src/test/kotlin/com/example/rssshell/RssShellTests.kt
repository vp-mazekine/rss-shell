package com.example.rssshell

import com.mazekine.rss.shell.AppConfig
import com.mazekine.rss.shell.AppInfo
import com.mazekine.rss.shell.ConfigLoader
import com.mazekine.rss.shell.Database
import com.mazekine.rss.shell.DbConfig
import com.mazekine.rss.shell.FeedDefaults
import com.mazekine.rss.shell.LoggingConfig
import com.mazekine.rss.shell.ServerConfig
import com.mazekine.rss.shell.TableConfig
import com.mazekine.rss.shell.rssShellModule
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class RssShellTests {
    private fun buildConfig(dbPath: String): AppConfig = AppConfig(
        app = AppInfo("rss-shell", "1.0.0"),
        server = ServerConfig("localhost", 8080),
        db = DbConfig("jdbc:sqlite:$dbPath", null, null),
        tables = TableConfig("direct_feeds", "direct_articles"),
        feedDefaults = FeedDefaults(ttlSeconds = 3600, language = "en"),
        logging = LoggingConfig(level = "INFO", logDir = "logs")
    )

    @Test
    fun `database queries return active feed and exposed articles`() {
        val dir = createTempDirectory().toFile()
        val config = buildConfig(dir.resolve("test.db").absolutePath)
        val database = Database(config)
        database.ensureSchema()

        database.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO ${config.tables.feeds}(external_id,title,description,is_active) VALUES (?,?,?,?)"
            ).use { ps ->
                ps.setString(1, "123e4567-e89b-12d3-a456-426614174000")
                ps.setString(2, "Test Feed")
                ps.setString(3, "Desc")
                ps.setInt(4, 1)
                ps.executeUpdate()
            }

            conn.prepareStatement(
                "INSERT INTO ${config.tables.articles}(feed_id,url,title,expose,created_at) VALUES (?,?,?,?,datetime('now'))"
            ).use { ps ->
                ps.setLong(1, 1)
                ps.setString(2, "https://example.com/a1")
                ps.setString(3, "Article 1")
                ps.setInt(4, 1)
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO ${config.tables.articles}(feed_id,url,title,expose,created_at) VALUES (?,?,?,?,datetime('now','-1 day'))"
            ).use { ps ->
                ps.setLong(1, 1)
                ps.setString(2, "https://example.com/a2")
                ps.setString(3, "Article 2")
                ps.setInt(4, 0)
                ps.executeUpdate()
            }
        }

        val feed = database.findActiveFeed("123e4567-e89b-12d3-a456-426614174000")
        assertNotNull(feed)
        val articles = database.findArticlesForFeed(feed.id)
        assertEquals(1, articles.size)
        assertEquals("https://example.com/a1", articles.first().url)
    }

    @Test
    fun `rss generation builds expected fields`() = testApplication {
        val dir = createTempDirectory().toFile()
        val config = buildConfig(dir.resolve("rss.db").absolutePath)
        val database = Database(config)
        database.ensureSchema()

        database.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO ${config.tables.feeds}(external_id,title,description,link,is_active) VALUES (?,?,?,?,1)"
            ).use { ps ->
                ps.setString(1, "feed-abc")
                ps.setString(2, "Feed Title")
                ps.setString(3, "Feed Desc")
                ps.setString(4, "https://example.com")
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO ${config.tables.articles}(feed_id,url,title,expose,created_at) VALUES (?,?,?,?,datetime('now'))"
            ).use { ps ->
                ps.setLong(1, 1)
                ps.setString(2, "https://example.com/item1")
                ps.setString(3, "Item 1")
                ps.setInt(4, 1)
                ps.executeUpdate()
            }
        }

        application { rssShellModule(config, database) }

        val response = client.get("/rss/feed-abc")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("<title>Feed Title</title>"))
        assertTrue(body.contains("<description>Feed Desc</description>"))
        assertTrue(body.contains("http://localhost:8080/articles/1"))
        assertTrue(body.contains("<ttl>60</ttl>"))
    }

    @Test
    fun `articles endpoint redirects to original url`() = testApplication {
        val dir = createTempDirectory().toFile()
        val config = buildConfig(dir.resolve("redirect.db").absolutePath)
        val database = Database(config)
        database.ensureSchema()

        database.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO ${config.tables.feeds}(external_id,title,is_active) VALUES (?,?,1)"
            ).use { ps ->
                ps.setString(1, "feed-redirect")
                ps.setString(2, "Feed")
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO ${config.tables.articles}(feed_id,url,title,expose,created_at) VALUES (?,?,?,?,datetime('now'))"
            ).use { ps ->
                ps.setLong(1, 1)
                ps.setString(2, "https://example.com/redirect")
                ps.setString(3, "Redirect Me")
                ps.setInt(4, 1)
                ps.executeUpdate()
            }
        }

        application { rssShellModule(config, database) }

        val noRedirectClient = client.config { followRedirects = false }
        val response = noRedirectClient.get("/articles/1") {
            headers.append(HttpHeaders.Accept, ContentType.Text.Plain.toString())
        }
        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("https://example.com/redirect", response.headers[HttpHeaders.Location])
    }

    @Test
    fun `config loader accepts custom file`() {
        val tempDir = createTempDirectory()
        val configFile = tempDir.resolve("custom.conf")
        val dbPath = tempDir.resolve("db.sqlite").toAbsolutePath().toString().replace("\\", "/")
        val content = """
            {
              "rss-shell": {
                "app": { "name": "rss-shell", "version": "1.2.3" },
                "server": { "host": "127.0.0.1", "port": 9999 },
                "db": { "url": "jdbc:sqlite:$dbPath", "user": "", "password": "" },
                "tables": { "feeds": "feeds", "articles": "articles" },
                "feedDefaults": { "ttlSeconds": 120, "language": "en" },
                "logging": { "level": "DEBUG", "logDir": "logs" }
              }
            }
        """.trimIndent()
        configFile.writeText(content)

        val loaded = ConfigLoader.load(arrayOf("--config=${configFile.toAbsolutePath()}"))
        assertEquals("127.0.0.1", loaded.server.host)
        assertEquals(9999, loaded.server.port)
        assertEquals("1.2.3", loaded.app.version)
    }

    @Test
    fun `config loader ignores unrelated flags when searching for explicit path`() {
        val tempDir = createTempDirectory()
        val configFile = tempDir.resolve("custom2.conf")
        configFile.writeText(
            """
            {
              "rss-shell": {
                "app": { "name": "rss-shell", "version": "2.0.0" },
                "server": { "host": "127.0.0.1", "port": 9000 },
                "db": { "url": "jdbc:sqlite::memory:" },
                "tables": { "feeds": "feeds", "articles": "articles" },
                "feedDefaults": { "ttlSeconds": 60, "language": "en" },
                "logging": { "level": "INFO", "logDir": "logs" }
              }
            }
            """.trimIndent()
        )

        val loaded = ConfigLoader.load(arrayOf("--verbose", "-c", configFile.toAbsolutePath().toString()))
        assertEquals(9000, loaded.server.port)
        assertEquals("2.0.0", loaded.app.version)
    }

    @Test
    fun `config loader supports long form separated flag`() {
        val tempDir = createTempDirectory()
        val configFile = tempDir.resolve("custom3.conf")
        configFile.writeText(
            """
            {
              "rss-shell": {
                "app": { "name": "rss-shell", "version": "3.0.0" },
                "server": { "host": "127.0.0.1", "port": 8888 },
                "db": { "url": "jdbc:sqlite::memory:" },
                "tables": { "feeds": "feeds", "articles": "articles" },
                "feedDefaults": { "ttlSeconds": 30, "language": "en" },
                "logging": { "level": "DEBUG", "logDir": "logs" }
              }
            }
            """.trimIndent()
        )

        val loaded = ConfigLoader.load(arrayOf("--config", configFile.toAbsolutePath().toString()))
        assertEquals(8888, loaded.server.port)
        assertEquals("3.0.0", loaded.app.version)
    }
}