package dev.ilgax.minekt.api

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.fabricmc.loader.api.FabricLoader
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

// Web requests, e.g. a Discord webhook:
//
//   httpPost(webhook, mapOf("content" to "Found diamonds at ${player.position}"))
//   val weather = httpGet("https://api.example.com/weather?city=Oslo").json<Weather>()
//
// They block the script until the answer arrives (use `launch { }` to keep going meanwhile), and
// `\kill` stops them. Any status code is returned, check `ok`; only network failures throw.

/** The answer to a web request. */
class HttpResult(val status: Int, val body: String, val headers: Map<String, List<String>>) {
    /** Status 200–299. */
    val ok: Boolean get() = status in 200..299

    /** The first value of a response header, any case. */
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    /** The body parsed as JSON into [T]: a data class, `Map<String, Any?>`, `List<…>`, … */
    inline fun <reified T> json(): T = httpGson.fromJson(body, object : TypeToken<T>() {}.type)

    /** Throw unless the status is 200–299. */
    fun orThrow(): HttpResult = apply { if (!ok) throw IOException("HTTP $status: ${body.take(200)}") }

    override fun toString() = "HttpResult($status, ${body.length} chars)"
}

/** GET [url]. */
fun Minekt.httpGet(url: String, headers: Map<String, String> = emptyMap(), timeout: Duration = 30.seconds): HttpResult =
    http("GET", url, null, headers, timeout)

/** POST [body] to [url]: a String is sent as it is, anything else as JSON. */
fun Minekt.httpPost(url: String, body: Any? = null, headers: Map<String, String> = emptyMap(), timeout: Duration = 30.seconds): HttpResult =
    http("POST", url, body, headers, timeout)

/** Any request: [method] is GET, POST, PUT, PATCH, DELETE, … [body] as for [httpPost]. */
fun Minekt.http(method: String, url: String, body: Any? = null, headers: Map<String, String> = emptyMap(), timeout: Duration = 30.seconds): HttpResult {
    checkCanWait("http")
    val uri = runCatching { URI(url) }.getOrNull()?.takeIf { it.scheme == "http" || it.scheme == "https" }
        ?: throw IllegalArgumentException("Not a web address: $url")
    val (content, type) = when (body) {
        null -> null to null
        is String -> body to "text/plain; charset=utf-8"
        is ByteArray -> null to "application/octet-stream"
        else -> httpGson.toJson(body) to "application/json"
    }
    val publisher = when {
        body is ByteArray -> HttpRequest.BodyPublishers.ofByteArray(body)
        content != null -> HttpRequest.BodyPublishers.ofString(content)
        else -> HttpRequest.BodyPublishers.noBody()
    }
    val request = HttpRequest.newBuilder(uri).method(method.uppercase(), publisher).timeout(timeout.toJavaDuration())
    request.header("User-Agent", Http.userAgent)
    if (type != null && headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) request.header("Content-Type", type)
    headers.forEach { (name, value) -> request.header(name, value) }
    val response = try {
        Http.client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    } catch (e: HttpConnectTimeoutException) {
        throw IOException("Couldn't connect to ${uri.host} in time", e)
    } catch (e: HttpTimeoutException) {
        throw IOException("${uri.host} didn't answer within $timeout", e)
    } catch (e: ConnectException) {
        throw IOException("Couldn't connect to ${uri.host}" + (e.message?.let { " ($it)" } ?: ""), e)
    } catch (e: IOException) {
        if (generateSequence<Throwable>(e) { it.cause }.any { it is UnknownHostException }) throw IOException("Unknown host ${uri.host} (no internet, or a typo?)", e)
        throw e
    }
    job.checkAlive()
    return HttpResult(response.statusCode(), response.body() ?: "", response.headers().map())
}

@PublishedApi
internal val httpGson: Gson = GsonBuilder().disableHtmlEscaping().create()

internal object Http {
    val client: HttpClient by lazy {
        // The JVM's proxy settings (-Dhttps.proxyHost …), if any.
        HttpClient.newBuilder().proxy(java.net.ProxySelector.getDefault()).followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(java.time.Duration.ofSeconds(15)).build()
    }

    val userAgent: String by lazy {
        val version = runCatching { FabricLoader.getInstance().getModContainer("minekt").map { it.metadata.version.friendlyString }.orElse(null) }.getOrNull()
        "Minekt/${version ?: "dev"}"
    }
}
