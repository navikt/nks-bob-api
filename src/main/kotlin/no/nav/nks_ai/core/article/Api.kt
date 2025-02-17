package no.nav.nks_ai.core.article

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

fun Route.articleRoutes(articleService: ArticleService) {
    route("/articles") {
        get {
            call.respond(HttpStatusCode.OK, articleService.getArticle("kA07U000000L2s7SAC"))
        }
    }
}