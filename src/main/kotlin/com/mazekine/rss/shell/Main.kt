package com.mazekine.rss.shell

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import org.slf4j.LoggerFactory

fun main(args: Array<String>) {
    val config = ConfigLoader.load(args)
    System.setProperty("rss_shell.logdir", config.logging.logDir)
    System.setProperty("rss_shell.loglevel", config.logging.level)
    val logger = LoggerFactory.getLogger("Main")
    logger.info("${config.app.name} v${config.app.version} starting on ${config.server.host}:${config.server.port} (db=${config.db.url})")

    val database = Database(config)
    database.ensureSchema()

    embeddedServer(CIO, port = config.server.port, host = config.server.host) {
        rssShellModule(config, database)
    }.start(wait = true)
}