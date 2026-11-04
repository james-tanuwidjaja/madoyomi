@file:Suppress("ktlint:standard:function-naming")

package eu.kanade.tachiyomi.network

import okhttp3.CacheControl
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit.MINUTES

private val DEFAULT_CACHE_CONTROL = CacheControl.Builder().maxAge(10, MINUTES).build()
private val DEFAULT_HEADERS = Headers.Builder().build()
private val DEFAULT_BODY: RequestBody = FormBody.Builder().build()

fun GET(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Request = GET(url.toHttpUrl(), headers, cache)

/**
 * @since extensions-lib 1.4
 */
fun GET(
    url: HttpUrl,
    headers: Headers = DEFAULT_HEADERS,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Request =
    Request
        .Builder()
        .url(url)
        .headers(headers)
        .cacheControl(cache)
        .build()

fun POST(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody = DEFAULT_BODY,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Request =
    Request
        .Builder()
        .url(url)
        .post(body)
        .headers(headers)
        .cacheControl(cache)
        .build()

fun PUT(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody = DEFAULT_BODY,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Request =
    Request
        .Builder()
        .url(url)
        .put(body)
        .headers(headers)
        .cacheControl(cache)
        .build()

fun DELETE(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody = DEFAULT_BODY,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Request =
    Request
        .Builder()
        .url(url)
        .delete(body)
        .headers(headers)
        .cacheControl(cache)
        .build()

// Madoyomi: the suspend request helpers of Aniyomi's extensions-lib 16 (aniyomiorg/aniyomi
// core/common/.../network/Requests.kt). Suwayomi's copy lacks them, and lib-16 anime extensions call them
// (compat scan, Phase 2 doc section 10: anikoto, kotokai, aniwave)

/**
 * Sends a GET request and returns the response, throwing [HttpException] unless it is successful.
 *
 * @since extensions-lib 16 (anime)
 */
suspend fun OkHttpClient.get(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Response = newCall(GET(url, headers, cache)).awaitSuccess()

/**
 * Sends a GET request and returns the response, throwing [HttpException] unless it is successful.
 *
 * @since extensions-lib 16 (anime)
 */
suspend fun OkHttpClient.get(
    url: HttpUrl,
    headers: Headers = DEFAULT_HEADERS,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Response = newCall(GET(url, headers, cache)).awaitSuccess()

/**
 * Sends a POST request and returns the response, throwing [HttpException] unless it is successful.
 *
 * @since extensions-lib 16 (anime)
 */
suspend fun OkHttpClient.post(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody = DEFAULT_BODY,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Response = newCall(POST(url, headers, body, cache)).awaitSuccess()
