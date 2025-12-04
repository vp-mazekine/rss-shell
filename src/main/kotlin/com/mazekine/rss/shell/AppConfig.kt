package com.mazekine.rss.shell

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import java.io.File

private const val ROOT_KEY = "rss-shell"

object ConfigLoader {
    fun load(args: Array<String>): AppConfig {
        val providedPath = parseConfigPath(args)
        val baseConfig = ConfigFactory.load()
        val config = if (providedPath != null) {
            val fileConfig = ConfigFactory.parseFile(File(providedPath))
            fileConfig.withFallback(baseConfig).resolve()
        } else {
            baseConfig.resolve()
        }
        val root = config.getConfig(ROOT_KEY)
        return root.toAppConfig()
    }

    private fun parseConfigPath(args: Array<String>): String? {
        var idx = 0
        while (idx < args.size) {
            val arg = args[idx]
            when {
                arg == "-c" && idx + 1 < args.size -> return args[idx + 1]
                arg == "--config" && idx + 1 < args.size -> return args[idx + 1]
                arg.startsWith("--config=") -> return arg.substringAfter("--config=")
            }
            idx++
        }
        return null
    }
}

data class AppConfig(
    val app: AppInfo,
    val server: ServerConfig,
    val db: DbConfig,
    val tables: TableConfig,
    val feedDefaults: FeedDefaults,
    val logging: LoggingConfig
)

data class AppInfo(val name: String, val version: String)
data class ServerConfig(val host: String, val port: Int)
data class DbConfig(val url: String, val user: String?, val password: String?)
data class TableConfig(val feeds: String, val articles: String)
data class FeedDefaults(val ttlSeconds: Int, val language: String)
data class LoggingConfig(val level: String, val logDir: String)

fun Config.toAppConfig(): AppConfig {
    val appCfg = getConfig("app")
    val serverCfg = getConfig("server")
    val dbCfg = getConfig("db")
    val tablesCfg = getConfig("tables")
    val defaultsCfg = getConfig("feedDefaults")
    val loggingCfg = getConfig("logging")

    return AppConfig(
        app = AppInfo(
            name = appCfg.getString("name"),
            version = appCfg.getString("version")
        ),
        server = ServerConfig(
            host = serverCfg.getString("host"),
            port = serverCfg.getInt("port")
        ),
        db = DbConfig(
            url = dbCfg.getString("url"),
            user = dbCfg.takeIf { it.hasPath("user") }?.getString("user")?.takeIf { it.isNotBlank() },
            password = dbCfg.takeIf { it.hasPath("password") }?.getString("password")?.takeIf { it.isNotBlank() }
        ),
        tables = TableConfig(
            feeds = tablesCfg.getString("feeds"),
            articles = tablesCfg.getString("articles")
        ),
        feedDefaults = FeedDefaults(
            ttlSeconds = defaultsCfg.getInt("ttlSeconds"),
            language = defaultsCfg.getString("language")
        ),
        logging = LoggingConfig(
            level = loggingCfg.getString("level"),
            logDir = loggingCfg.getString("logDir")
        )
    )
}

fun visibleHost(host: String): String = if (host == "0.0.0.0") "localhost" else host

fun AppConfig.baseUrl(): String = "http://${visibleHost(server.host)}:${server.port}"