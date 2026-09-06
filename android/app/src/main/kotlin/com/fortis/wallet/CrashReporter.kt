package com.fortis.wallet

import android.os.Build
import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal, self-hosted crash reporting. On an uncaught exception it POSTs a small
 * JSON report to the fortis backend and then lets the normal crash proceed.
 *
 * Best-effort: the report is sent synchronously on the dying thread with a short
 * timeout and no retry — if the device is offline the report is lost. It never
 * contains wallet data (no addresses, keys, transactions, or the recovery phrase).
 */
object CrashReporter {
    private const val ENDPOINT = "$HOSTED_EDGE/crash"

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // The handler runs on the crashing thread, often the main thread, so
            // the send can't happen inline (NetworkOnMainThreadException). Hand it
            // to a worker and wait briefly before letting the process die.
            val worker = Thread {
                runCatching { send(thread, error) }
                    .onFailure { android.util.Log.w("CrashReporter", "report not sent: $it") }
            }
            worker.start()
            runCatching { worker.join(4_000) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun send(thread: Thread, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val body = JSONObject().apply {
            put("app_version", BuildConfig.VERSION_NAME)
            put("version_code", BuildConfig.VERSION_CODE)
            put("build_type", if (BuildConfig.DEBUG) "debug" else "release")
            put("android_sdk", Build.VERSION.SDK_INT)
            put("model", Build.MODEL)
            put("manufacturer", Build.MANUFACTURER)
            put("thread", thread.name)
            put("exception", error.javaClass.name)
            put("message", error.message ?: "")
            put("stack", trace.take(12_000))
        }.toString()

        (URL(ENDPOINT).openConnection() as HttpURLConnection).run {
            requestMethod = "POST"
            connectTimeout = 2_000
            readTimeout = 3_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            outputStream.use { it.write(body.toByteArray()) }
            responseCode // force the request
            disconnect()
        }
    }
}
