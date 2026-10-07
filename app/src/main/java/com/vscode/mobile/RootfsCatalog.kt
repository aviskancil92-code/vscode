package com.vscode.mobile

import java.io.IOException

data class RootfsArtifact(val url: String, val sha256: String)

/** Parsing dan resolusi katalog build rootfs Linux Containers. */
internal object RootfsCatalog {
    private const val MAX_RECENT_BUILDS = 3
    private val dateLink = Regex(
        """href\s*=\s*["'](\d{8}_\d{2}(?:%3[aA]|:)\d{2})/["']""",
        RegexOption.IGNORE_CASE
    )
    private val checksumLine = Regex("""^\s*([0-9a-fA-F]{64})\s+(.+?)\s*$""")

    internal fun parseBuildDates(html: String): List<String> =
        dateLink.findAll(html)
            .map { it.groupValues[1] }
            .distinct()
            .sortedDescending()
            .toList()

    internal fun parseRootfsSha256(text: String): String? =
        text.lineSequence().mapNotNull { line ->
            val match = checksumLine.matchEntire(line) ?: return@mapNotNull null
            val filename = match.groupValues[2].removePrefix("*").trim()
            if (filename == "rootfs.tar.xz" || filename.endsWith("/rootfs.tar.xz")) {
                match.groupValues[1].lowercase()
            } else {
                null
            }
        }.firstOrNull()

    /**
     * [fetchText] dipisahkan pada batas jaringan agar format index dan error dapat diuji
     * deterministik; production memasok Net.getText.
     */
    internal fun resolve(base: String, fetchText: (String) -> String): List<RootfsArtifact> {
        val normalizedBase = if (base.endsWith('/')) base else "$base/"
        val html = try {
            fetchText(normalizedBase)
        } catch (e: IOException) {
            val detail = e.message ?: e.javaClass.simpleName
            throw IOException(
                "Tidak dapat membaca daftar build Debian dari linuxcontainers.org: $detail. " +
                    "Periksa koneksi ke images.linuxcontainers.org atau gunakan Impor manual rootfs dari Pengaturan.",
                e
            )
        }

        val dates = parseBuildDates(html).take(MAX_RECENT_BUILDS)
        if (dates.isEmpty()) {
            throw IOException(
                "Server linuxcontainers.org merespons, tetapi tidak ditemukan tautan tanggal build " +
                    "Debian yang dikenali. Format indeks mungkin berubah; gunakan Impor manual rootfs dari Pengaturan."
            )
        }

        val artifacts = mutableListOf<RootfsArtifact>()
        val failures = mutableListOf<String>()
        for (date in dates) {
            val sumsUrl = "${normalizedBase}${date}/SHA256SUMS"
            val sums = try {
                fetchText(sumsUrl)
            } catch (e: IOException) {
                failures += "$date: ${e.message ?: e.javaClass.simpleName}"
                continue
            }
            val sha256 = parseRootfsSha256(sums)
            if (sha256 == null) {
                failures += "$date: SHA256SUMS tidak memuat checksum rootfs.tar.xz yang valid"
                continue
            }
            artifacts += RootfsArtifact("${normalizedBase}${date}/rootfs.tar.xz", sha256)
        }

        if (artifacts.isEmpty()) {
            val detail = failures.joinToString("; ").ifBlank { "tidak ada checksum yang valid" }
            throw IOException(
                "Daftar build Debian terbaca, tetapi checksum rootfs.tar.xz dari build terbaru tidak dapat diperoleh " +
                    "($detail). Periksa koneksi atau gunakan Impor manual rootfs dari Pengaturan."
            )
        }
        return artifacts
    }
}
