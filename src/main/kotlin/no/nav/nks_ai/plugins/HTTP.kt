package no.nav.nks_ai.plugins

import com.ucasoft.ktor.simpleCache.SimpleCache
import com.ucasoft.ktor.simpleMemoryCache.*
import io.ktor.server.application.*
import kotlin.time.Duration.Companion.minutes

fun Application.configureCache() {
    install(SimpleCache) {
        memoryCache {
            invalidateAt = 30.minutes
        }
    }
//    routing {
//        cacheOutput(2.seconds) {
//            get("/short") {
//                call.respond(Random.nextInt().toString())
//            }
//        }
//        cacheOutput {
//            get("/default") {
//                call.respond(Random.nextInt().toString())
//            }
//        }
//    }
}
