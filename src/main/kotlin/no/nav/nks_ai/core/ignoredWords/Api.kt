package no.nav.nks_ai.core.ignoredWords

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.ExperimentalKtorApi
import no.nav.nks_ai.app.Page
import no.nav.nks_ai.app.Sort
import no.nav.nks_ai.app.navIdent
import no.nav.nks_ai.app.pagination
import no.nav.nks_ai.app.respondEither
import no.nav.nks_ai.app.teamLogger

private val logger = KotlinLogging.logger { }
private val teamLogger = teamLogger(logger)

@OptIn(ExperimentalKtorApi::class)
fun Route.ignoredWordsAdminRoutes(ignoredWordsService: IgnoredWordsService) {
    route("/admin/ignored-words") {
        get {
            call.respondEither {
                val pagination = call.pagination().bind()

                val navIdent = call.navIdent().bind()
                teamLogger.info { "[ACCESS] user=${navIdent.plaintext.value} action=READ resource=ignored-words" }

                ignoredWordsService.getAllIgnoredWords(pagination)
            }
        }.describe {
            description = "Get all ignored words"
            parameters {
                query("page") {
                    schema = jsonSchema<Int>()
                    description = "Which page to fetch (default = 0)"
                    required = false
                }
                query("size") {
                    schema = jsonSchema<Int>()
                    description = "How many ignored words to fetch (default = 100)"
                    required = false
                }
                query("sort") {
                    schema = jsonSchema<String>()
                    description =
                        "Sort order (default = ${Sort.CreatedAtDesc.value}). Valid values: ${Sort.validValues}"
                    required = false
                }
            }
            responses {
                HttpStatusCode.OK {
                    schema = jsonSchema<Page<IgnoredWordAggregation>>()
                    description = "All ignored words"
                }
            }
        }
        route("/{id}") {
            get {
                call.respondEither {
                    val id = call.ignoredWordId().bind()

                    val navIdent = call.navIdent().bind()
                    teamLogger.info { "[ACCESS] user=${navIdent.plaintext.value} action=READ resource=ignored-words/${id}" }

                    ignoredWordsService.getIgnoredWord(id)
                }
            }.describe {
                description = "Get ignored word by id"
                responses {
                    HttpStatusCode.OK {
                        schema = jsonSchema<IgnoredWord>()
                        description = "The ignored word requested"
                    }
                }
            }
            delete {
                call.respondEither(HttpStatusCode.NoContent) {
                    val id = call.ignoredWordId().bind()

                    val navIdent = call.navIdent().bind()
                    teamLogger.info { "[ACCESS] user=${navIdent.plaintext.value} action=DELETE resource=ignored-words/${id}" }

                    ignoredWordsService.deleteIgnoredWord(id)
                }
            }.describe {
                description = "Delete ignored word by id"
                responses {
                    HttpStatusCode.NoContent {
                        description = "The ignored word was deleted"
                    }
                }
            }
        }
        get("/aggregate") {
            call.respondEither {
                val pagination = call.pagination().bind()

                val navIdent = call.navIdent().bind()
                teamLogger.info { "[ACCESS] user=${navIdent.plaintext.value} action=READ resource=ignored-words/aggregate" }

                ignoredWordsService.getAllIgnoredWordsAggregated(pagination)
            }
        }.describe {
            description = "Get all ignored words aggregated"
            parameters {
                query("page") {
                    schema = jsonSchema<Int>()
                    description = "Which page to fetch (default = 0)"
                    required = false
                }
                query("size") {
                    schema = jsonSchema<Int>()
                    description = "How many ignored word aggregations to fetch (default = 100)"
                    required = false
                }
            }
            responses {
                HttpStatusCode.OK {
                    schema = jsonSchema<List<IgnoredWordAggregation>>()
                    description = "All ignored words grouped by value and validation type"
                }
            }
        }
    }
}

@OptIn(ExperimentalKtorApi::class)
fun Route.ignoredWordsRoutes(ignoredWordsService: IgnoredWordsService) {
    route("/ignored-words") {
        post {
            call.respondEither {
                val navIdent = call.navIdent().bind()
                val newIgnoredWord = call.receive<NewIgnoredWord>()

                ignoredWordsService.addIgnoredWord(navIdent, newIgnoredWord)
            }
        }.describe {
            description = "Add a new ignored word"
            requestBody {
                schema = jsonSchema<NewIgnoredWord>()
                description = "The ignored word to be added"
            }
            responses {
                HttpStatusCode.Created {
                    schema = jsonSchema<IgnoredWord>()
                    description = "The ignored word that got created"
                }
            }
        }
    }
}