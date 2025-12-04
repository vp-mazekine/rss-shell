package com.mazekine.rss.shell

import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant

class Database(private val config: AppConfig) {
    private val logger = LoggerFactory.getLogger(Database::class.java)
    private val isSqlite = config.db.url.lowercase().startsWith("jdbc:sqlite:")

    init {
        loadDriver()
    }

    private fun loadDriver() {
        when {
            config.db.url.startsWith("jdbc:postgresql:") -> Class.forName("org.postgresql.Driver")
            config.db.url.startsWith("jdbc:sqlite:") -> Class.forName("org.sqlite.JDBC")
            else -> error("Unsupported DB URL: ${config.db.url}")
        }
    }

    fun getConnection(): Connection {
        return if (isSqlite || config.db.user == null) {
            DriverManager.getConnection(config.db.url)
        } else {
            DriverManager.getConnection(config.db.url, config.db.user, config.db.password)
        }
    }

    fun ensureSchema() {
        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(feedTableSql())
                stmt.execute(articleTableSql())
            }
        }
        logger.info("Ensured schema for tables ${config.tables.feeds} and ${config.tables.articles}")
    }

    private fun feedTableSql(): String = if (isSqlite) {
        """
        CREATE TABLE IF NOT EXISTS ${config.tables.feeds} (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            external_id TEXT NOT NULL UNIQUE,
            title TEXT NOT NULL,
            description TEXT,
            link TEXT,
            outlet_name TEXT,
            is_active INTEGER NOT NULL DEFAULT 1,
            created_at TEXT NOT NULL DEFAULT (datetime('now')),
            updated_at TEXT NOT NULL DEFAULT (datetime('now'))
        );
        """.trimIndent()
    } else {
        """
        CREATE TABLE IF NOT EXISTS ${config.tables.feeds} (
            id BIGINT PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
            external_id UUID NOT NULL UNIQUE,
            title TEXT NOT NULL,
            description TEXT,
            link TEXT,
            outlet_name TEXT,
            is_active BOOLEAN NOT NULL DEFAULT TRUE,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
        );
        """.trimIndent()
    }

    private fun articleTableSql(): String = if (isSqlite) {
        """
        CREATE TABLE IF NOT EXISTS ${config.tables.articles} (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            feed_id INTEGER NOT NULL,
            url TEXT NOT NULL,
            title TEXT,
            outlet_name TEXT,
            note TEXT,
            pub_date TEXT,
            expose INTEGER NOT NULL DEFAULT 1,
            created_at TEXT NOT NULL DEFAULT (datetime('now')),
            FOREIGN KEY(feed_id) REFERENCES ${config.tables.feeds}(id) ON DELETE CASCADE
        );
        """.trimIndent()
    } else {
        """
        CREATE TABLE IF NOT EXISTS ${config.tables.articles} (
            id BIGINT PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
            feed_id BIGINT NOT NULL REFERENCES ${config.tables.feeds}(id) ON DELETE CASCADE,
            url TEXT NOT NULL,
            title TEXT,
            outlet_name TEXT,
            note TEXT,
            pub_date TIMESTAMPTZ,
            expose BOOLEAN NOT NULL DEFAULT TRUE,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now()
        );
        """.trimIndent()
    }

    fun findActiveFeed(externalId: String): Feed? {
        val sql = "SELECT * FROM ${config.tables.feeds} WHERE external_id = ? AND is_active = ${if (isSqlite) 1 else true}"
        getConnection().use { conn ->
            conn.prepareStatement(sql).use { ps ->
                if (isSqlite) {
                    // external_id is TEXT in SQLite schema
                    ps.setString(1, externalId)
                } else {
                    // external_id is UUID in Postgres schema
                    ps.setObject(1, java.util.UUID.fromString(externalId))
                }
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.toFeed() else null
                }
            }
        }
    }

    fun findArticlesForFeed(feedId: Long): List<Article> {
        val sql = """
            SELECT * FROM ${config.tables.articles}
            WHERE feed_id = ? AND expose = ${if (isSqlite) 1 else true}
            ORDER BY COALESCE(pub_date, created_at) DESC
        """.trimIndent()
        getConnection().use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setLong(1, feedId)
                ps.executeQuery().use { rs ->
                    val items = mutableListOf<Article>()
                    while (rs.next()) {
                        items.add(rs.toArticle())
                    }
                    return items
                }
            }
        }
    }

    fun findArticleUrl(articleId: Long): String? {
        val sql = "SELECT url FROM ${config.tables.articles} WHERE id = ?"
        getConnection().use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setLong(1, articleId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.getString("url") else null
                }
            }
        }
    }

    private fun ResultSet.toFeed(): Feed = Feed(
        id = getLong("id"),
        externalId = getString("external_id"),
        title = getString("title"),
        description = getString("description"),
        link = getString("link"),
        outletName = getString("outlet_name"),
        isActive = if (isSqlite) getInt("is_active") == 1 else getBoolean("is_active"),
        createdAt = getTimestamp("created_at")?.toInstant() ?: Instant.now(),
        updatedAt = getTimestamp("updated_at")?.toInstant() ?: Instant.now()
    )

    private fun ResultSet.toArticle(): Article = Article(
        id = getLong("id"),
        feedId = getLong("feed_id"),
        url = getString("url"),
        title = getString("title"),
        outletName = getString("outlet_name"),
        note = getString("note"),
        pubDate = getTimestamp("pub_date")?.toInstant(),
        expose = if (isSqlite) getInt("expose") == 1 else getBoolean("expose"),
        createdAt = getTimestamp("created_at")?.toInstant() ?: Instant.now()
    )
}

data class Feed(
    val id: Long,
    val externalId: String,
    val title: String,
    val description: String?,
    val link: String?,
    val outletName: String?,
    val isActive: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class Article(
    val id: Long,
    val feedId: Long,
    val url: String,
    val title: String?,
    val outletName: String?,
    val note: String?,
    val pubDate: Instant?,
    val expose: Boolean,
    val createdAt: Instant
)