package task2.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.http.encodeURLQueryComponent
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer

/**
 * Entire network layer — Ktor + okio in common code, no expect/actual
 * (previously HttpURLConnection in two copies for android/jvm).
 * One HttpClient for the whole app lifetime (connection reuse),
 * files via okio FileSystem.
 */
private val ktorClient = HttpClient(CIO) {
    install(HttpTimeout) {
        connectTimeoutMillis = 30_000
        socketTimeoutMillis = 60_000
        requestTimeoutMillis = 120_000
    }
}

private const val USER_AGENT = "ai-advent"

/** Plain GET as String. Non-2xx throws (as before). */
suspend fun httpGet(url: String): String {
    val response = ktorClient.prepareGet(url) {
        header(HttpHeaders.UserAgent, USER_AGENT)
    }.execute()
    if (!response.status.isSuccess()) throw IllegalStateException("HTTP ${response.status.value}")
    return response.bodyAsText()
}

/** Percent-encoding for query params (e.g. Wikipedia article titles). */
fun urlEncode(s: String): String = s.encodeURLQueryComponent()

/**
 * File download with progress and retries.
 * Semantics mirror the old HttpURLConnection variant:
 * - up to 3 attempts on network drops (IOException), HTTP errors are not retried;
 * - clean start: a truncated .part from a previous attempt is never resumed;
 * - progress callback: per percent change when length is known,
 *   per megabyte when unknown (chunked/proxy), always at the end;
 * - truncated tail (done < total) — IOException, i.e. retried;
 * - atomic file appearance: write to .part, rename at the end.
 */
suspend fun downloadFile(
    url: String,
    destPath: String,
    onProgress: (doneBytes: Long, totalBytes: Long?) -> Unit,
) {
    val fs = FileSystem.SYSTEM
    val dest = destPath.toPath()
    val tmp = "$destPath.part".toPath()
    dest.parent?.let { fs.createDirectories(it) }
    var lastError: Exception? = null
    repeat(3) {
        // Clean start: never resume a truncated .part from a previous attempt.
        runCatching { fs.delete(tmp) }
        try {
            downloadOnce(url, tmp, dest, onProgress)
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            // HTTP errors (response code) — not retried.
            throw e
        } catch (e: Exception) {
            // Mobile network drop/timeout — retried.
            lastError = e
            delay(2000)
        }
    }
    throw lastError ?: IllegalStateException("Не удалось скачать после 3 попыток")
}

private suspend fun downloadOnce(
    url: String,
    tmp: okio.Path,
    dest: okio.Path,
    onProgress: (doneBytes: Long, totalBytes: Long?) -> Unit,
) {
    val fs = FileSystem.SYSTEM
    ktorClient.prepareGet(url) {
        header(HttpHeaders.UserAgent, USER_AGENT)
        timeout {
            // Don't kill a gigabyte-sized model on a slow network: overall timeout
            // is effectively off (7 days), only stall silence ticks.
            // This Ktor version has no INFINITE_TIMEOUT_MS — finite values only.
            requestTimeoutMillis = 7L * 24 * 60 * 60 * 1000
            socketTimeoutMillis = 300_000
        }
    }.execute { response ->
        if (!response.status.isSuccess()) throw IllegalStateException("HTTP ${response.status.value}")
        // null = server didn't send length (chunked/proxy): don't fake percents.
        val total: Long? = response.contentLength()?.takeIf { it > 0 }
        val channel = response.bodyAsChannel()
        fs.sink(tmp).buffer().use { sink ->
            val buf = ByteArray(256 * 1024)
            var done = 0L
            var lastPercent = -1
            while (true) {
                val n = channel.readAvailable(buf, 0, buf.size)
                if (n < 0) break
                sink.write(buf, 0, n)
                done += n
                if (total != null) {
                    val p = ((done * 100) / total).toInt().coerceIn(0, 100)
                    if (p != lastPercent) {
                        lastPercent = p
                        onProgress(done, total)
                    }
                } else if (done % (1024 * 1024) == 0L) {
                    onProgress(done, null)
                }
            }
            if (total != null && done < total) {
                // Truncated tail — not an HTTP error, so a plain Exception: retried above.
                throw Exception("Скачивание оборвалось: $done из $total байт")
            }
            onProgress(done, total)
        }
    }
    try {
        fs.atomicMove(tmp, dest)
    } catch (_: Exception) {
        fs.copy(tmp, dest)
        fs.delete(tmp)
    }
}
