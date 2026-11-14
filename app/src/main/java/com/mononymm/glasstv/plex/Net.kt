package com.mononymm.glasstv.plex

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Tiny HTTP helper. Tokens travel in headers only, never in URLs we build. */
object Net {
    class HttpError(val code: Int, msg: String) : IOException(msg)

    fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        timeoutMs: Int = 15000,
    ): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = true
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (method == "POST" || method == "PUT") {
                c.doOutput = true
                c.setFixedLengthStreamingMode(0)
                c.outputStream.close()
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (code !in 200..299) throw HttpError(code, "HTTP $code")
            return body
        } finally {
            c.disconnect()
        }
    }

    fun bytes(url: String, headers: Map<String, String>, timeoutMs: Int = 15000): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            val code = c.responseCode
            if (code !in 200..299) throw HttpError(code, "HTTP $code")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }
}
