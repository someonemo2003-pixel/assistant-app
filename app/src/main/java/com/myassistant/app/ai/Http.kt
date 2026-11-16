package com.myassistant.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

object Http {

    /** Tries IPv4 addresses first: some mobile networks have IPv6 routes that stall. */
    private val ipv4First = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = Dns.SYSTEM.lookup(hostname)
            return all.filterIsInstance<Inet4Address>() + all.filterNot { it is Inet4Address }
        }
    }

    /** IPv4 only, used for the retry. */
    private val ipv4Only = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            Dns.SYSTEM.lookup(hostname).filterIsInstance<Inet4Address>().ifEmpty { Dns.SYSTEM.lookup(hostname) }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .dns(ipv4First)
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Plain HTTP/1.1 over IPv4: the most compatible setup, for networks that break HTTP/2. */
    private val fallbackClient: OkHttpClient = client.newBuilder()
        .dns(ipv4Only)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun postJson(url: String, headers: Map<String, String>, body: JSONObject): JSONObject {
        val payload = body.toString()
        return request(headers) { Request.Builder().url(url).post(payload.toRequestBody(jsonType)) }
    }

    suspend fun getJson(url: String, headers: Map<String, String>): JSONObject =
        request(headers) { Request.Builder().url(url).get() }

    private suspend fun request(headers: Map<String, String>, newRequest: () -> Request.Builder): JSONObject =
        withContext(Dispatchers.IO) {
            val build = {
                val b = try {
                    newRequest()
                } catch (e: IllegalArgumentException) {
                    throw ProviderException("رابط غير صالح")
                }
                headers.forEach { (k, v) -> b.header(k, v) }
                b.build()
            }
            val (code, text) = try {
                execute(client, build())
            } catch (first: IOException) {
                // One more try with the most compatible connection settings.
                try {
                    execute(fallbackClient, build())
                } catch (second: IOException) {
                    throw ProviderException("تعذّر الاتصال: ${describe(second)}")
                }
            }
            if (code !in 200..299) throw ProviderException("HTTP $code: ${errorMessage(text)}")
            try {
                JSONObject(text)
            } catch (e: JSONException) {
                throw ProviderException("رد غير مفهوم من الخادم")
            }
        }

    private fun execute(c: OkHttpClient, request: Request): Pair<Int, String> =
        c.newCall(request).execute().use { r -> r.code to r.body?.string().orEmpty() }

    private fun describe(e: IOException): String {
        val type = e.javaClass.simpleName
        val msg = e.message.orEmpty()
        return when {
            type.contains("UnknownHost") -> "مفيش إنترنت أو الـ DNS مش شغال"
            type.contains("Timeout", ignoreCase = true) || msg.contains("timeout", ignoreCase = true) ->
                "السيرفر مردّش في الوقت ($type)"
            else -> "$type ${msg.take(120)}"
        }
    }

    private fun errorMessage(text: String): String {
        val msg = try {
            when (val e = JSONObject(text).opt("error")) {
                is JSONObject -> e.optString("message", text)
                is String -> e
                else -> text
            }
        } catch (_: Exception) {
            text
        }
        return msg.take(300)
    }
}
