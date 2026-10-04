package com.vscode.mobile

/**
 * Pin versi & URL yang diverifikasi saat pengembangan.
 *
 * Semua URL diunduh saat instalasi pertama (APK tetap kecil, ±5 MB).
 * Repo Termux dipakai sebagai sumber biner proot (dibuat untuk Android/bionic),
 * linuxcontainers.org sebagai sumber rootfs Debian, dan GitHub coder/code-server
 * untuk rilis standalone code-server (sudah membundel Node.js).
 */
object Pins {

    /** Repo apt Termux utama + mirror cadangan (untuk biner proot). */
    const val TERMUX_REPO = "https://packages.termux.dev/apt/termux-main/"
    const val TERMUX_REPO_MIRROR = "https://packages-cf.termux.dev/apt/termux-main/"

    const val PROOT_VERSION = "5.1.107.96"
    const val LIBTALLOC_VERSION = "2.5.0"
    const val LIBSHMEM_VERSION = "0.7"

    /**
     * Versi code-server untuk arm64/amd64.
     * VERSI TERBARHARU: 4.140.0 — pola URL:
     * https://github.com/coder/code-server/releases/download/v{V}/code-server-{V}-linux-{ARCH}.tar.gz
     */
    const val CODE_SERVER_VERSION = "4.140.0"

    /**
     * Rilis terakhir yang masih menyediakan tarball armv7l (perangkat 32-bit ARM).
     * Rilis baru (termasuk 4.140.0) hanya menyediakan arm64 dan amd64.
     */
    const val CODE_SERVER_VERSION_ARMV7 = "4.23.1"

    const val CODE_SERVER_BASE = "https://github.com/coder/code-server/releases/download/"

    /** Rilis Debian untuk rootfs (bookworm = Debian 12, stabil). */
    const val DEBIAN_RELEASE = "bookworm"

    /** Server citra resmi LXC — menyediakan rootfs Debian per-arsitektur. */
    const val LXC_BASE = "https://images.linuxcontainers.org/images/debian/"

    fun codeServerUrl(version: String, arch: String): String =
        "${CODE_SERVER_BASE}v$version/code-server-$version-linux-$arch.tar.gz"
}
