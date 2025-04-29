package no.nav.nks_ai.app.plugins

import com.ucasoft.ktor.simpleCache.SimpleCache
import com.ucasoft.ktor.simpleMemoryCache.*
import io.ktor.server.application.*
import kotlin.time.Duration.Companion.minutes

fun Application.configureCache() {
    install(SimpleCache) {
        memoryCache {
            invalidateAt = 15.minutes
        }
    }
}
