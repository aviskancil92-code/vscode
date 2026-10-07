package com.vscode.mobile

import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
        val prootInfo = packages["proot"]
            ?: throw IOException("paket proot tidak ditemukan pada indeks repo Termux")
        val tallocInfo = packages["libtalloc"]
            ?: throw IOException("paket libtalloc tidak ditemukan pada indeks repo Termux")
        val shmemInfo = packages["libandroid-shmem"]
            ?: throw IOException("paket libandroid-shmem tidak ditemukan pada indeks repo Termux")

        val debsDir = File(staging, "debs")
        val prootDeb = File(debsDir, "proot.deb")
        val tallocDeb = File(debsDir, "libtalloc.deb")
        val shmemDeb = File(debsDir, "libandroid-shmem.deb")

        Net.download(
            Pins.TERMUX_REPO + prootInfo.path, prootDeb, prootInfo.sha256,
            expectedSizeBytes = prootInfo.size.takeIf { it > 0L },
            availableSpaceBytes = { allocatableBytes(prootDeb) }
        ) { r, t, s ->
            onProgress(Progress("Mengunduh proot…", "(${termuxArch})", r, t, s))
        }
        Net.download(
            Pins.TERMUX_REPO + tallocInfo.path, tallocDeb, tallocInfo.sha256,
            expectedSizeBytes = tallocInfo.size.takeIf { it > 0L },
            availableSpaceBytes = { allocatableBytes(tallocDeb) }
        ) { r, t, s ->
            onProgress(Progress("Mengunduh libtalloc…", "", r, t, s))
        }
        Net.download(
            Pins.TERMUX_REPO + shmemInfo.path, shmemDeb, shmemInfo.sha256,
            expectedSizeBytes = shmemInfo.size.takeIf { it > 0L },
            availableSpaceBytes = { allocatableBytes(shmemDeb) }
        ) { r, t, s ->
            onProgress(Progress("Mengunduh libandroid-shmem…", "", r, t, s))
        }

        onProgress(Progress("Mengekstrak proot…"))
        val debOut = File(staging, "deb-out")
        debOut.mkdirs()
        Archive.extractDebToDir(prootDeb, debOut)
        Archive.extractDebToDir(tallocDeb, debOut)
        Archive.extractDebToDir(shmemDeb, debOut)

        val prootFile = findFile(debOut) { f ->
            !Files.isSymbolicLink(f.toPath()) && f.isFile && f.name == "proot"
        } ?: throw IOException("biner proot tidak ditemukan di dalam paket .deb")

        val tallocFile = findFile(debOut) { f ->
            !Files.isSymbolicLink(f.toPath()) && f.isFile &&
                Regex("^libtalloc\\.so\\.2").containsMatchIn(f.name)
        } ?: throw IOException("libtalloc.so.2 tidak ditemukan di dalam paket .deb")

        val shmemFile = findFile(debOut) { f ->
            !Files.isSymbolicLink(f.toPath()) && f.isFile && f.name == "libandroid-shmem.so"
        } ?: throw IOException("libandroid-shmem tidak ditemukan di dalam paket .deb")

        copyTo(prootFile, File(staging, "bin/proot"), 0x1ED)      // 0755
        copyTo(tallocFile, File(staging, "lib/libtalloc.so.2"), 0x1A4) // 0644
        copyTo(shmemFile, File(staging, "lib/libandroid-shmem.so"), 0x1A4) // 0644

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

    private fun fetchPackagesIndex(termuxArch: String): Map<String, PackageInfo> {
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
            onProgress(Progress("Memeriksa daftar build Debian…", rootfsArch))
            val base = Pins.LXC_BASE + Pins.DEBIAN_RELEASE + "/" + rootfsArch + "/default/"
            val urls = RootfsCatalog.resolve(base) { url -> Net.getText(url, 25_000) }
            rootfsFile = File(File(staging, "downloads"), "rootfs.tar.xz")
            var lastError: IOException? = null
            var ok = false
            for (url in urls) {
                try {
                    Net.download(
                        url.url, rootfsFile, url.sha256,
                        availableSpaceBytes = { allocatableBytes(rootfsFile) }
                    ) { r, t, s ->
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
            val checksum = Pins.codeServerSha256(version, csArch)
                ?: throw IOException("checksum code-server $version/$csArch belum tersedia; gunakan impor manual")
            tarball = File(File(staging, "downloads"), "code-server.tar.gz")
            Net.download(
                url,
                tarball,
                expectedSha256 = checksum,
                availableSpaceBytes = { allocatableBytes(tarball) }
            ) { r, t, s ->
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

        // Generate a unique per-device password and enable authentication by default
        // This ensures every installation is secured without user intervention
        val generatedPassword = StateStore.newPassword()
        LinuxRuntime.writeServerConfig(rootfs, authEnabled = true, password = generatedPassword)

        val state = AppState(
            installed = true,
            codeServerVersion = LinuxRuntime.codeServerVersionFor(),
            rootfsSource = "debian/${Pins.DEBIAN_RELEASE} (linuxcontainers.org)",
            authEnabled = true,
            password = generatedPassword,
            keepScreenOn = false,
            createdAt = System.currentTimeMillis()
        )
        StateStore.writeTo(staging, state)

        val final = StateStore.linuxDir(ctx)
        val backup = File(ctx.filesDir, "linux-previous")
        StateStore.deleteRecursive(backup)
        var movedOld = false
        try {
            if (final.exists()) {
                try {
                    Files.move(final.toPath(), backup.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(final.toPath(), backup.toPath())
                }
                movedOld = true
            }
            try {
                Files.move(staging.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(staging.toPath(), final.toPath())
            }
            StateStore.deleteRecursive(backup)
        } catch (e: Exception) {
            if (movedOld && !final.exists() && backup.exists()) {
                try {
                    Files.move(backup.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(backup.toPath(), final.toPath())
                }
            }
            throw IOException("gagal memfinalisasi instalasi (runtime lama dipulihkan)", e)
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
        if (total > Net.MAX_ARTIFACT_BYTES) {
            input.close()
            throw IOException("berkas impor melebihi batas ${Net.MAX_ARTIFACT_BYTES} byte")
        }
        val availableBytes = allocatableBytes(dest)
        if (total >= 0L && availableBytes != null && total > availableBytes) {
            input.close()
            throw IOException("ruang penyimpanan tidak cukup ($availableBytes tersedia, $total dibutuhkan)")
        }

        input.use { i ->
            FileOutputStream(dest).use { out ->
                val buf = ByteArray(64 * 1024)
                var read = 0L
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    val nextRead = Net.checkedByteCount(read, n, Net.MAX_ARTIFACT_BYTES)
                    out.write(buf, 0, n)
                    read = nextRead
                    onProgress(read, total)
                }
                out.fd.sync()
            }
        }
    }

    private fun allocatableBytes(file: File): Long? = runCatching {
        val storage = ctx.getSystemService(StorageManager::class.java) ?: return null
        storage.getAllocatableBytes(storage.getUuidForPath(file.absoluteFile))
    }.getOrNull()
}
