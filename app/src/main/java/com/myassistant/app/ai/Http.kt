package com.myassistant.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun postJson(url: String, headers: Map<String, String>, body: JSONObject): JSONObject =
        request(Request.Builder().url(url).post(body.toString().toRequestBody(jsonType)), headers)

    suspend fun getJson(url: String, headers: Map<String, String>): JSONObject =
        request(Request.Builder().url(url).get(), headers)

    private suspend fun request(builder: Request.Builder, headers: Map<String, String>): JSONObject =
        withContext(Dispatchers.IO) {
            headers.forEach { (k, v) -> builder.header(k, v) }
            val response = try {
                client.newCall(builder.build()).execute()
            } catch (e: IOException) {
                throw ProviderException("تعذّر الاتصال (${e.message ?: "network"})")
            } catch (e: IllegalArgumentException) {
                throw ProviderException("رابط غير صالح")
            }
            response.use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw ProviderException("HTTP ${r.code}: ${errorMessage(text)}")
                try {
                    JSONObject(text)
                } catch (e: JSONException) {
                    throw ProviderException("رد غير مفهوم من الخادم")
                }
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
