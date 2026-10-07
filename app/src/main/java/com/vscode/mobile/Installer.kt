package com.vscode.mobile

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files

/**
 * Installer pertama kali:
 *  1. Unduh & ekstrak proot + libtalloc + libandroid-shmem (dari repo apt Termux).
 *  2. Unduh & ekstrak rootfs Debian (linuxcontainers.org).
 *  3. Unduh & ekstrak code-server standalone (sudah membundel Node.js).
 *  4. Tulis konfigurasi lalu pindahkan staging -> files/linux secara atomik.
 *
 * Mendukung impor manual (Uri) untuk rootfs & code-server bagi pengguna yang
 * ingin memakai berkas unduhan sendiri.
 */
class Installer(private val ctx: Context) {

    data class Progress(
        val step: String,
        val detail: String = "",
        val read: Long = 0,
        val total: Long = -1,
        val speedBps: Long = 0,
        /** 0 proot, 1 Debian, 2 code-server, 3 konfigurasi akhir (untuk UI langkah-langkah). */
        val phase: Int = 0
    )

    suspend fun install(
        onProgress: suspend (Progress) -> Unit,
        manualRootfs: Uri? = null,
        manualCodeServer: Uri? = null
    ): AppState = withContext(Dispatchers.IO) {
        val staging = File(ctx.filesDir, "linux-staging")
        StateStore.deleteRecursive(staging)
        staging.mkdirs()
        listOf("bin", "lib", "tmp", "logs", "downloads", "debs").forEach {
            File(staging, it).mkdirs()
        }

        val arches = LinuxRuntime.arches()
            ?: throw IOException("Arsitektur CPU tidak didukung (butuh arm64, armv7, atau x86_64).")
        val (termuxArch, rootfsArch, csArch) = arches

        try {
            // ---- 1) proot + library pendukung -------------------------------
            installProot(staging, termuxArch) { p -> onProgress(p.copy(phase = 0)) }

            // ---- 2) rootfs Debian -------------------------------------------
            installRootfs(staging, rootfsArch, manualRootfs) { p -> onProgress(p.copy(phase = 1)) }

            // ---- 3) code-server ---------------------------------------------
            installCodeServer(staging, csArch, manualCodeServer) { p -> onProgress(p.copy(phase = 2)) }

            // ---- 4) konfigurasi + finalisasi --------------------------------
            onProgress(Progress("Menulis konfigurasi…", phase = 3))
            finalize(staging)
            StateStore.read(ctx)
        } catch (e: CancellationException) {
            StateStore.deleteRecursive(staging)
            throw e
        } catch (e: Exception) {
            StateStore.deleteRecursive(staging)
            throw e
        }
    }

    // ------------------------------------------------------------------ proot

    private suspend fun installProot(
        staging: File,
        termuxArch: String,
        onProgress: suspend (Progress) -> Unit
    ) {
        onProgress(Progress("Mengambil indeks paket Termux…"))
        val packages = fetchPackagesIndex(termuxArch)
        val prootPath = packages["proot"]
            ?: throw IOException("paket proot tidak ditemukan pada indeks repo Termux")
        val tallocPath = packages["libtalloc"]
            ?: throw IOException("paket libtalloc tidak ditemukan pada indeks repo Termux")
        val shmemPath = packages["libandroid-shmem"]
            ?: throw IOException("paket libandroid-shmem tidak ditemukan pada indeks repo Termux")

        val debsDir = File(staging, "debs")
        val prootDeb = File(debsDir, "proot.deb")
        val tallocDeb = File(debsDir, "libtalloc.deb")
        val shmemDeb = File(debsDir, "libandroid-shmem.deb")

        Net.download(Pins.TERMUX_REPO + prootPath, prootDeb) { r, t, s ->
            onProgress(Progress("Mengunduh proot…", "(${termuxArch})", r, t, s))
        }
        Net.download(Pins.TERMUX_REPO + tallocPath, tallocDeb) { r, t, s ->
            onProgress(Progress("Mengunduh libtalloc…", "", r, t, s))
        }
        Net.download(Pins.TERMUX_REPO + shmemPath, shmemDeb) { r, t, s ->
            onProgress(Progress("Mengunduh libandroid-shmem…", "", r, t, s))
        }

        onProgress(Progress("Mengekstrak proot…"))
        val debOut = File(staging, "deb-out")
        debOut.mkdirs()
        Archive.extractDebToDir(prootDeb, debOut)
        Archive.extractDebToDir(tallocDeb, debOut)
        Archive.extractDebToDir(shmemDeb, debOut)

        val proot = findFile(debOut) { f ->
            !Files.isSymbolicLink(f.toPath()) && f.isFile && f.name == "proot"
        } ?: throw IOException("biner proot tidak ditemukan di dalam paket .deb")

        val talloc = findFile(debOut) { f ->
            !Files.isSymbolicLink(f.toPath()) && f.isFile &&
                Regex("^libtalloc\\.so\\.2").containsMatchIn(f.name)
        } ?: throw IOException("libtalloc.so.2 tidak ditemukan di dalam paket .deb")

        val shmem = findFile(debOut) { f ->
            !Files.isSymbolicLink(f.toPath()) && f.isFile && f.name == "libandroid-shmem.so"
        } ?: throw IOException("libandroid-shmem tidak ditemukan di dalam paket .deb")

        copyTo(proot, File(staging, "bin/proot"), 0x1ED)      // 0755
        copyTo(talloc, File(staging, "lib/libtalloc.so.2"), 0x1A4) // 0644
        copyTo(shmem, File(staging, "lib/libandroid-shmem.so"), 0x1A4) // 0644

        // Loader proot (bila build Termux memisahkannya dari biner). Tanpa ini execve
        // ke dalam rootfs gagal. Opsional: build yang meng-embed loader tak punya berkasnya.
        findFile(debOut) { f ->
            !Files.isSymbolicLink(f.toPath()) && f.isFile && f.name == "loader" &&
                f.parentFile?.name == "proot"
        }?.let { copyTo(it, File(staging, "lib/proot-loader"), 0x1ED) }
        findFile(debOut) { f ->
            !Files.isSymbolicLink(f.toPath()) && f.isFile && f.name == "loader32" &&
                f.parentFile?.name == "proot"
        }?.let { copyTo(it, File(staging, "lib/proot-loader32"), 0x1ED) }

        StateStore.deleteRecursive(debOut)
        StateStore.deleteRecursive(debsDir)
    }

    private fun fetchPackagesIndex(termuxArch: String): Map<String, String> {
        val path = "dists/stable/main/binary-$termuxArch/Packages"
        val text = try {
            Net.getText(Pins.TERMUX_REPO + path)
        } catch (e: Exception) {
            Net.getText(Pins.TERMUX_REPO_MIRROR + path)
        }
        return Net.parsePackagesIndex(text)
    }

    // ----------------------------------------------------------------- rootfs

    private suspend fun installRootfs(
        staging: File,
        rootfsArch: String,
        manual: Uri?,
        onProgress: suspend (Progress) -> Unit
    ) {
        val rootfsFile: File
        if (manual != null) {
            rootfsFile = File(File(staging, "downloads"), "rootfs-manual.tar")
            copyUri(manual, rootfsFile) { r, t ->
                onProgress(Progress("Menyalin rootfs…", "", r, t, 0))
            }
        } else {
            val urls = resolveRootfsUrls(rootfsArch)
            if (urls.isEmpty()) {
                throw IOException(
                    "Daftar build Debian ($rootfsArch) tidak dapat dibaca dari linuxcontainers.org. " +
                        "Coba lagi nanti atau gunakan Impor manual rootfs dari Pengaturan."
                )
            }
            rootfsFile = File(File(staging, "downloads"), "rootfs.tar.xz")
            var lastError: IOException? = null
            var ok = false
            for (url in urls) {
                try {
                    Net.download(url, rootfsFile) { r, t, s ->
                        onProgress(Progress("Mengunduh rootfs Debian $rootfsArch…", "Debian ${Pins.DEBIAN_RELEASE}", r, t, s))
                    }
                    ok = true
                    break
                } catch (e: IOException) {
                    lastError = e
                    rootfsFile.delete()
                }
            }
            if (!ok) {
                throw IOException(
                    "Gagal mengunduh rootfs: ${lastError?.message}. " +
                        "Coba lagi atau gunakan Impor manual rootfs dari Pengaturan."
                )
            }
        }

        onProgress(Progress("Mengekstrak rootfs Debian…", "ini bisa memakan beberapa menit"))
        val rootfsDir = File(staging, "debian")
        rootfsDir.mkdirs()
        val (counting, stream) = Archive.openTarFile(rootfsFile)
        val total = rootfsFile.length()
        try {
            Archive.extractTar(stream, rootfsDir) {
                onProgress(Progress("Mengekstrak rootfs Debian…", "", counting.count, total, 0))
            }
        } finally {
            runCatching { stream.close() }
            runCatching { counting.close() }
        }

        val bashOk = File(rootfsDir, "bin/bash").exists() || File(rootfsDir, "usr/bin/bash").exists()
        if (!bashOk) throw IOException("rootfs tidak valid: bash tidak ditemukan di dalam arsip")
        rootfsFile.delete()
    }

    /**
     * Cari URL rootfs terbaru: scrape daftar tanggal build di
     * images.linuxcontainers.org (diurutkan menurun, dicoba satu per satu).
     */
    private fun resolveRootfsUrls(rootfsArch: String): List<String> {
        val base = Pins.LXC_BASE + Pins.DEBIAN_RELEASE + "/" + rootfsArch + "/default/"
        val html = try {
            Net.getText(base, 25_000)
        } catch (_: Exception) {
            return emptyList()
        }
        val dates = Regex("href=\"(\\d{8}_\\d{2}(?:%3A|:)\\d{2})/\"")
            .findAll(html)
            .map { it.groupValues[1] }
            .distinct()
            .sortedDescending()
            .toList()
        return dates.take(3).map { base + it + "/rootfs.tar.xz" }
    }

    // ------------------------------------------------------------ code-server

    private suspend fun installCodeServer(
        staging: File,
        csArch: String,
        manual: Uri?,
        onProgress: suspend (Progress) -> Unit
    ) {
        val version = LinuxRuntime.codeServerVersionFor()
        val tarball: File
        if (manual != null) {
            tarball = File(File(staging, "downloads"), "cs-manual.tar.gz")
            copyUri(manual, tarball) { r, t ->
                onProgress(Progress("Menyalin code-server…", "", r, t, 0))
            }
        } else {
            val url = Pins.codeServerUrl(version, csArch)
            tarball = File(File(staging, "downloads"), "code-server.tar.gz")
            Net.download(url, tarball) { r, t, s ->
                onProgress(Progress("Mengunduh code-server $version…", "($csArch)", r, t, s))
            }
        }

        onProgress(Progress("Mengekstrak code-server…", "±700 MB setelah diekstrak"))
        val csExtract = File(staging, "cs-extract")
        csExtract.mkdirs()
        val (counting, stream) = Archive.openTarFile(tarball)
        val total = tarball.length()
        try {
            Archive.extractTar(stream, csExtract) {
                onProgress(Progress("Mengekstrak code-server…", "", counting.count, total, 0))
            }
        } finally {
            runCatching { stream.close() }
            runCatching { counting.close() }
        }

        val inner = csExtract.listFiles()?.firstOrNull { it.isDirectory }
            ?: throw IOException("struktur tarball code-server tidak dikenal (direktori utama tidak ada)")

        val target = File(File(staging, "debian"), "opt/code-server")
        if (target.exists()) StateStore.deleteRecursive(target)
        if (!inner.renameTo(target)) throw IOException("gagal memindahkan code-server ke rootfs")
        if (!File(target, "bin/code-server").exists() || !File(target, "lib/node").exists()) {
            throw IOException("tarball code-server tidak lengkap (bin/code-server atau lib/node hilang)")
        }

        StateStore.deleteRecursive(csExtract)
        tarball.delete()
    }

    // --------------------------------------------------------------- finalisasi

    private fun finalize(staging: File) {
        val rootfs = File(staging, "debian")
        LinuxRuntime.writeStartScript(rootfs)
        LinuxRuntime.writeServerConfig(rootfs, authEnabled = false, password = "")
        val state = AppState(
            installed = true,
            codeServerVersion = LinuxRuntime.codeServerVersionFor(),
            rootfsSource = "debian/${Pins.DEBIAN_RELEASE} (linuxcontainers.org)",
            authEnabled = false,
            password = "",
            keepScreenOn = false,
            createdAt = System.currentTimeMillis()
        )
        StateStore.writeTo(staging, state)

        val final = StateStore.linuxDir(ctx)
        if (final.exists()) StateStore.deleteRecursive(final)
        if (!staging.renameTo(final)) {
            throw IOException("gagal memfinalisasi instalasi (rename staging)")
        }
    }

    // ---------------------------------------------------------------- util

    private fun copyTo(src: File, dst: File, mode: Int) {
        dst.parentFile?.mkdirs()
        FileInputStream(src).use { i ->
            FileOutputStream(dst).use { o -> i.copyTo(o) }
        }
        try { android.system.Os.chmod(dst.path, mode) } catch (_: Exception) { }
    }

    private fun findFile(root: File, pred: (File) -> Boolean): File? {
        if (root.isDirectory && !Files.isSymbolicLink(root.toPath())) {
            val children = root.listFiles() ?: return null
            for (c in children) {
                findFile(c, pred)?.let { return it }
            }
            return null
        }
        return if (pred(root)) root else null
    }

    private suspend fun copyUri(uri: Uri, dest: File, onProgress: suspend (read: Long, total: Long) -> Unit) {
        val input = ctx.contentResolver.openInputStream(uri)
            ?: throw IOException("tidak dapat membaca berkas yang dipilih")
        var total = -1L
        try {
            ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                total = afd.length
            }
        } catch (_: Exception) { }

        input.use { i ->
            FileOutputStream(dest).use { out ->
                val buf = ByteArray(64 * 1024)
                var read = 0L
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    read += n
                    onProgress(read, total)
                }
                out.fd.sync()
            }
        }
    }
}
