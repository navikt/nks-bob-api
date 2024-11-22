package no.nav.nks_ai.app

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.Serializable
import java.net.Inet4Address

suspend fun isLeader(httpClient: HttpClient): Boolean {
    val response = httpClient
        .get(Config.nais.electorUrl)
        .body<ElectorGetResponse>()

    val leader = response.name
    val hostname = Inet4Address.getLocalHost().hostName

    return hostname.equals(leader.toString())
}

@Serializable
private data class ElectorGetResponse(
    val name: String
)