# NOTICE — Atribusi Komponen Pihak Ketiga

APK **CodeX Studio** tidak membundel biner pihak ketiga di dalam paketnya.
Seluruh komponen diunduh saat instalasi pertama dari sumber resminya.
Komponen tersebut tunduk pada lisensi masing-masing:

## code-server
- Sumber: https://github.com/coder/code-server (rilis standalone resmi)
- Lisensi: MIT
- Catatan: rilis standalone membundel Node.js (proyek OpenJS, lisensi MIT-ish).

## Debian GNU/Linux rootfs
- Sumber: https://images.linuxcontainers.org (server citra proyek LXC)
- Lisensi: beragam (paket per paket) — lihat https://www.debian.org/legal/licenses/
- Catatan: rootfs "bookworm" (Debian 12) dipakai sebagai userland glibc.

## proot
- Sumber: paket `proot` dari repo Termux — https://packages.termux.dev
  (fork: https://github.com/termux/proot)
- Lisensi: GPL-2.0-or-later

## libtalloc
- Sumber: paket `libtalloc` dari repo Termux — https://packages.termux.dev
  (upstream: https://talloc.samba.org)
- Lisensi: LGPL-3.0-or-later (lihat upstream untuk teks lengkap)

## libandroid-shmem
- Sumber: paket `libandroid-shmem` dari repo Termux — https://packages.termux.dev
  (upstream: https://github.com/termux/libandroid-shmem)
- Lisensi: lihat repositori upstream (proyek Termux).

## Pustaka Android (Google & Jetpack)
- androidx (core-ktx, appcompat, activity, lifecycle): Apache-2.0
- Material Components for Android: Apache-2.0
- Kotlin & kotlinx-coroutines: Apache-2.0
- org.tukaani:xz: pustaka dekompresi XZ murni-Java — lisensi publik domain
  atau XZ-License (lihat upstream https://tukaani.org/xz/java.html)

Aplikasi ini adalah pembungkus (wrapper) tidak resmi dan tidak berafiliasi
dengan Microsoft (pemilik merek VS Code) maupun Coder.com.
