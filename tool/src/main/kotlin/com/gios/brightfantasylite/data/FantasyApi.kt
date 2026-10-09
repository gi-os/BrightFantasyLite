package com.gios.brightfantasylite.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException

/**
 * The one place that talks to ESPN. Same hosts, headers and cookies as BrightFantasy's
 * OkHttp client, on ktor's OkHttp engine because that is what the SDK ships.
 *
 * ESPN's fantasy API has no tokens and no OAuth. A private league is read by presenting
 * the two session cookies a browser would have sent: `SWID` (braces included) and
 * `espn_s2`. A public league needs neither.
 */
class FantasyApi(private val creds: () -> Credentials) {

    data class Credentials(val swid: String, val espnS2: String) {
        val present: Boolean get() = swid.isNotBlank() && espnS2.isNotBlank()
    }

    /** What came back, including the failure, because the failure is the useful part. */
    sealed interface Result {
        data class Ok(val body: String) : Result
        /** ESPN answered, but not with what was asked for. [message] is ESPN's own text. */
        data class Denied(val code: Int, val message: String) : Result
        data class Offline(val cause: String) : Result
    }

    private val client: HttpClient by lazy {
        HttpClient(OkHttp) {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                // Some of these payloads are a hundred kilobytes of JSON over LTE.
                requestTimeoutMillis = 30_000
                socketTimeoutMillis = 25_000
            }
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.common() {
        val c = creds()
        // A bare client user agent gets 403ed often enough to matter.
        header("User-Agent", "Mozilla/5.0 (Linux; Android 14) BrightFantasyLite")
        header("Accept", "application/json, text/plain, */*")
        // ESPN's write path rejects a POST without its web client's platform header;
        // harmless on reads, so it goes on everything.
        header("x-fantasy-platform", "kona-PROD-BrightFantasy")
        header("x-fantasy-source", "kona")
        if (c.present) header("Cookie", "SWID=${c.swid}; espn_s2=${c.espnS2}")
    }

    suspend fun get(url: String, fantasyFilter: String? = null): Result = run {
        client.get(url) {
            common()
            // ESPN takes the player query as a JSON header, not a parameter or a body.
            fantasyFilter?.let { header("x-fantasy-filter", it) }
        }
    }

    suspend fun post(url: String, json: String): Result = run {
        client.post(url) {
            common()
            setBody(TextContent(json, ContentType.Application.Json))
        }
    }

    private suspend fun run(call: suspend () -> HttpResponse): Result = try {
        val response = call()
        val body = response.bodyAsText()
        val code = response.status.value
        if (code in 200..299) Result.Ok(body) else Result.Denied(code, espnMessage(body, code))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.Offline(e.message ?: "network error")
    }

    companion object {
        /**
         * ESPN's error body is `{"messages":["..."],"details":[...]}`. The first message
         * beats a status code. A 401 means the cookies expired (they last a few months);
         * a 404 on a league that used to work means the season rolled over.
         */
        fun espnMessage(body: String, code: Int): String {
            val quoted = Regex("\"messages\"\\s*:\\s*\\[\\s*\"([^\"]+)\"")
                .find(body)?.groupValues?.getOrNull(1)
            return when {
                quoted != null -> quoted
                code == 401 -> "ESPN rejected the login. The cookies have probably expired."
                code == 403 -> "This league is private and the login was not accepted."
                code == 404 -> "No league by that id in this season."
                else -> "ESPN answered $code."
            }
        }
    }
}
