package com.vscode.mobile

import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URI
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

class DownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class PackageInfo(val path: String, val sha256: String, val size: Long)

/**
 * Utilitas jaringan sederhana: GET teks, unduh dengan resume + retry,
 * dan parsing indeks paket apt (format "Packages").
 */
object Net {

    private const val UA = "CodeXStudio/1.1 (Android; installer)"
    private const val MAX_TEXT_BYTES = 32L * 1024L * 1024L
    internal const val MAX_ARTIFACT_BYTES = 4L * 1024L * 1024L * 1024L
    private const val MAX_REDIRECTS = 5
    private val redirectCodes = setOf(301, 302, 303, 307, 308)
    private val allowedHosts = setOf(
        "packages.termux.dev", "packages-cf.termux.dev",
        "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com",
        "images.linuxcontainers.org"
    )

    /** Server gambar LXC mengalihkan file ke mirror regional di bawah domain resminya. */
    private fun isAllowedHost(host: String?): Boolean {
        val normalized = host?.lowercase() ?: return false
        return normalized in allowedHosts || normalized.endsWith(".images.linuxcontainers.org")
    }

    /** GET teks dengan retry 3x. Panggil dari Dispatchers.IO. */
    fun getText(url: String, timeoutMs: Int = 20_000): String {
        var last: IOException? = null
        for (attempt in 1..3) {
            try {
                val conn = open(url, timeoutMs)
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) throw IOException("HTTP $code")
                    if (conn.contentLengthLong > MAX_TEXT_BYTES) {
                        throw IOException("respons metadata melebihi batas $MAX_TEXT_BYTES byte")
                    }
                    return readBoundedText(conn.inputStream, MAX_TEXT_BYTES)
                } finally {
                    conn.disconnect()
                }
            } catch (e: IOException) {
                last = e
            }
            if (attempt < 3) Thread.sleep(500L * attempt)
        }
        throw DownloadException("Gagal mengambil: ${last?.message}", last)
    }

    internal fun validateAllowedUrl(url: String): URI {
        val uri = runCatching { URI(url) }.getOrElse { throw IOException("URL tidak valid", it) }
        if (uri.scheme?.lowercase() != "https" || !isAllowedHost(uri.host) ||
            uri.userInfo != null || (uri.port != -1 && uri.port != 443)
        ) {
            throw IOException("Sumber unduhan tidak diizinkan: ${uri.scheme}://${uri.host}")
        }
        return uri
    }

    /** Resolve dan validasi satu redirect sebelum request berikutnya dibuat. */
    internal fun resolveAllowedRedirect(currentUrl: String, location: String): String {
        val base = validateAllowedUrl(currentUrl)
        val target = try {
            base.resolve(URI(location)).normalize()
        } catch (e: Exception) {
            throw IOException("URL redirect tidak valid", e)
        }
        return validateAllowedUrl(target.toASCIIString()).toASCIIString()
    }

    /** Membaca metadata dengan batas byte keras, termasuk saat server tidak mengirim Content-Length. */
    internal fun readBoundedText(input: java.io.InputStream, maxBytes: Long): String {
        if (maxBytes < 0L) throw IOException("batas metadata tidak valid")
        val out = ByteArrayOutputStream(minOf(maxBytes, 64L * 1024L).toInt())
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        input.use { stream ->
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                if (n.toLong() > maxBytes - total) {
                    throw IOException("respons metadata melebihi batas $maxBytes byte")
                }
                out.write(buffer, 0, n)
                total += n
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    internal fun checkedByteCount(currentBytes: Long, incomingBytes: Int, maxBytes: Long): Long {
        if (currentBytes < 0L || incomingBytes < 0 || maxBytes < 0L ||
            incomingBytes.toLong() > maxBytes - currentBytes
        ) {
            throw IOException("artifact melebihi batas $maxBytes byte")
        }
        return currentBytes + incomingBytes
    }

    private fun open(url: String, timeoutMs: Int, rangeStart: Long? = null): HttpURLConnection {
        var current = validateAllowedUrl(url).toASCIIString()
        for (redirectCount in 0..MAX_REDIRECTS) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", UA)
            if (rangeStart != null && rangeStart > 0L) {
                conn.setRequestProperty("Range", "bytes=$rangeStart-")
            }

            val code = try {
                conn.responseCode
            } catch (e: IOException) {
                conn.disconnect()
                throw e
            }
            if (code !in redirectCodes) return conn

            val location = conn.getHeaderField("Location")
            conn.disconnect()
            if (location.isNullOrBlank()) throw IOException("redirect HTTP $code tanpa Location")
            if (redirectCount == MAX_REDIRECTS) throw IOException("terlalu banyak redirect HTTP")
            current = resolveAllowedRedirect(current, location)
        }
        throw IOException("terlalu banyak redirect HTTP")
    }

    /**
     * Unduh berkas dengan dukungan resume (HTTP Range) dan retry dengan backoff.
     * [onProgress] dipanggil (terbatasi ±4x/detik) dengan
     * (byteTerunduh, total(-1 jika tidak diketahui), kecepatan Bps).
     */
    suspend fun download(
        url: String,
        dest: File,
        expectedSha256: String? = null,
        expectedSizeBytes: Long? = null,
        availableSpaceBytes: (() -> Long?)? = null,
        onProgress: suspend (read: Long, total: Long, speedBps: Long) -> Unit = { _, _, _ -> }
    ) {
        val expectedSize = expectedSizeBytes?.takeIf { it > 0L }
        if (expectedSize != null && expectedSize > MAX_ARTIFACT_BYTES) {
            throw DownloadException("ukuran artifact yang dinyatakan melebihi batas $MAX_ARTIFACT_BYTES byte")
        }
        val maxBytes = expectedSize ?: MAX_ARTIFACT_BYTES
        dest.parentFile?.mkdirs()
        var lastError: IOException? = null
        for (attempt in 1..5) {
            try {
                downloadOnce(url, dest, maxBytes, availableSpaceBytes, onProgress)
                if (dest.exists() && dest.length() > 0L) {
                    if (expectedSize != null && dest.length() != expectedSize) {
                        val actualSize = dest.length()
                        dest.delete()
                        throw IOException("ukuran artifact tidak cocok ($actualSize/$expectedSize byte)")
                    }
                    expectedSha256?.let { verifySha256(dest, it) }
                    return
                }
                throw IOException("berkas unduhan kosong")
            } catch (e: IOException) {
                lastError = e
            }
            if (attempt < 5) delay(1500L * attempt)
        }
        File(dest.path + ".part").delete()
        dest.delete()
        throw DownloadException("Gagal mengunduh setelah 5 percobaan: ${lastError?.message}", lastError)
    }

    fun verifySha256(file: File, expected: String) {
        val normalized = expected.removePrefix("sha256:").lowercase()
        require(normalized.matches(Regex("[0-9a-f]{64}"))) { "Checksum SHA-256 tidak valid" }
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != normalized) {
            file.delete()
            throw DownloadException("Checksum SHA-256 tidak cocok untuk ${file.name}")
        }
    }

    private suspend fun downloadOnce(
        url: String,
        dest: File,
        maxBytes: Long,
        availableSpaceBytes: (() -> Long?)?,
        onProgress: suspend (read: Long, total: Long, speedBps: Long) -> Unit
    ) {
        val tmp = File(dest.path + ".part")
        val existing = if (tmp.exists()) tmp.length() else 0L
        if (existing > maxBytes) {
            tmp.delete()
            throw IOException("unduhan parsial sudah melewati batas $maxBytes byte")
        }

        val conn = open(url, 30_000, existing.takeIf { it > 0L })
        try {
            val code = conn.responseCode

            when {
                code == 416 -> {
                    // Range tidak terpenuhi — mulai ulang dari nol.
                    tmp.delete()
                    throw IOException("HTTP 416 (rentang tidak valid)")
                }
                code !in 200..299 -> throw IOException("HTTP $code untuk $url")
            }

            val appending = existing > 0L && code == 206
            if (!appending && tmp.exists()) tmp.delete()

            val contentLen = conn.contentLengthLong // -1 bila tidak diketahui
            val start = if (appending) existing else 0L
            val total = when {
                appending && contentLen >= 0 -> start + contentLen
                else -> contentLen
            }
            if (total > maxBytes) throw IOException("artifact melebihi batas $maxBytes byte")
            if (total >= 0L) {
                val remainingBytes = if (appending) (total - start).coerceAtLeast(0L) else total
                val availableBytes = availableSpaceBytes?.invoke()
                if (availableBytes != null && remainingBytes > availableBytes) {
                    throw IOException("ruang penyimpanan tidak cukup ($availableBytes tersedia, $remainingBytes dibutuhkan)")
                }
            }

            var read = start
            var lastTick = System.currentTimeMillis()
            var lastBytes = read
            var speed = 0L

            FileOutputStream(tmp, appending).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        val nextRead = checkedByteCount(read, n, maxBytes)
                        out.write(buf, 0, n)
                        read = nextRead
                        val now = System.currentTimeMillis()
                        if (now - lastTick >= 250) {
                            speed = ((read - lastBytes) * 1000L) / (now - lastTick).coerceAtLeast(1)
                            lastTick = now
                            lastBytes = read
                            onProgress(read, total, speed)
                            coroutineContext.ensureActive()
                        }
                    }
                }
                out.fd.sync()
            }

            if (total >= 0 && read < total) throw IOException("koneksi terputus ($read/$total byte)")
            onProgress(read, total, speed)

            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("gagal memindahkan berkas unduhan")
        } finally {
            conn.disconnect()
        }
    }

    /** Parse indeks "Packages" apt: nama paket -> path berkas deb. */
    fun parsePackagesIndex(text: String): Map<String, PackageInfo> {
        val out = mutableMapOf<String, PackageInfo>()
        text.split("\n\n").forEach { rawStanza ->
            val stanza = rawStanza.trim()
            if (stanza.isEmpty()) return@forEach
            var pkg: String? = null
            var file: String? = null
            var sha256: String? = null
            var size = -1L
            stanza.lines().forEach { line ->
                when {
                    line.startsWith("Package: ") -> pkg = line.removePrefix("Package: ").trim()
                    line.startsWith("Filename: ") -> file = line.removePrefix("Filename: ").trim()
                    line.startsWith("SHA256: ") -> sha256 = line.removePrefix("SHA256: ").trim()
                    line.startsWith("Size: ") -> size = line.removePrefix("Size: ").trim().toLongOrNull() ?: -1L
                }
            }
            if (pkg != null && file != null && sha256?.matches(Regex("[0-9a-fA-F]{64}")) == true) {
                out[pkg!!] = PackageInfo(file!!, sha256!!.lowercase(), size)
            }
        }
        return out
    }
}
