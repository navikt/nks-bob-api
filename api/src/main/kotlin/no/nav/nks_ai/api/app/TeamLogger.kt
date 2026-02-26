package no.nav.nks_ai.api.app

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.Marker

private object TeamLogsMarker : Marker {
    override fun getName(): String = "TEAM_LOGS"
}

interface TeamLogger {
    fun info(message: () -> Any?)
    fun debug(message: () -> Any?)
    fun warn(message: () -> Any?)
    fun error(message: () -> Any?)
}

fun teamLogger(kLogger: KLogger) = object : TeamLogger {
    override fun info(message: () -> Any?) =
        kLogger.info(throwable = null, marker = TeamLogsMarker, message = message)

    override fun debug(message: () -> Any?) =
        kLogger.debug(throwable = null, marker = TeamLogsMarker, message = message)

    override fun warn(message: () -> Any?) =
        kLogger.warn(throwable = null, marker = TeamLogsMarker, message = message)

    override fun error(message: () -> Any?) =
        kLogger.error(throwable = null, marker = TeamLogsMarker, message = message)
}
