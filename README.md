# CodeX Studio

**VS Code di Android — Debian + code-server + WebView, tanpa root.**

APK ini menjalankan sistem Debian GNU/Linux lengkap bersama
[code-server](https://github.com/coder/code-server) (VS Code di peramban) di
latar belakang perangkat, lalu menampilkannya langsung lewat WebView — jadi
saat aplikasi dibuka, yang tampil adalah **VS Code utuh** lengkap dengan
terminal Debian, ekstensi, dan Git.

```
┌───────────────────── APK (±5 MB) ─────────────────────┐
│                                                        │
│  MainActivity ── WebView ──► http://127.0.0.1:8080     │
│        │ start()                                       │
│        ▼                                               │
│  LinuxService (foreground service, notifikasi status)  │
│        │ exec                                          │
│        ▼                                               │
│  proot -r files/linux/debian  (Debian 12 "bookworm")   │
│        │ /root/.vscmob/start.sh                        │
│        ▼                                               │
│  /opt/code-server/bin/code-server  (bundel Node.js)    │
│        │ listen                                        │
│        ▼                                               │
│  127.0.0.1:8080 ── WebView merender UI VS Code ✅      │
└────────────────────────────────────────────────────────┘
```

## Fitur

- **Tanpa root** — Debian berjalan lewat [proot](https://github.com/termux/proot)
  (translasi syscall berbasis ptrace), persis mekanisme Termux/proot-distro.
- **Foreground service** dengan notifikasi status, auto-restart bila server mati,
  dan bertahan saat aplikasi ditutup.
- **Instalasi satu ketuk** — unduh otomatis: proot (repo Termux), rootfs Debian
  (linuxcontainers.org), dan code-server (rilis standalone GitHub, sudah
  membundel Node.js).
- **Resume unduhan** — koneksi terputus? unduhan melanjutkan dari posisi
  terakhir (HTTP Range) + retry otomatis.
- **Impor manual** — punya berkas rootfs/tarball sendiri? Impor lewat
  Pengaturan tanpa menghabiskan kuota.
- Terminal Debian asli di dalam VS Code (`apt update && apt install git`
  langsung bekerja), ekstensi VS Code via Marketplace, folder kerja di `/root`,
  dan akses penyimpanan bersama lewat `/sdcard` (bila izin diberikan).
- Autentikasi **diaktifkan secara otomatis** untuk setiap perangkat: installer
  menghasilkan kata sandi acak yang kuat (12 karakter, tanpa simbol mirip) dan
  mengaktifkan `auth: password` di code-server. Kata sandi ini ditampilkan di
  Pengaturan → Lihat kata sandi, jadi tidak perlu diingat secara manual.
  Server hanya di `127.0.0.1`, jadi kata sandi melindungi akses lokal saja;
  matikan dari Pengaturan jika Anda yakin hanya Anda yang menggunakan perangkat.

## Kenapa `targetSdk 28`?

Android 10+ (API 29+) **melarang `exec()` berkas dari penyimpanan aplikasi**,
padahal proot dan seluruh biner glibc Debian harus dieksekusi dari `filesDir`.
Menetapkan `targetSdk 28` memakai jalur kompatibilitas yang sama seperti
**Termux** — legal dan stabil untuk APK sideload pribadi. Jangan naikkan
targetSdk ke 29+ kecuali Anda mengganti arsitektur eksekusi (mis. memindahkan
semua biner ke `nativeLibraryDir`).

## Kebutuhan build

| Alat | Versi |
|---|---|
| Android Studio | Ladybug atau lebih baru (atau CLI Gradle) |
| JDK | 17 (sudah dibundel di Android Studio) |
| Android SDK | platform `android-34`, build-tools `34.0.0` |
| Gradle | 8.9 (otomatis via wrapper) |

## Build

### Cara A — Android Studio (paling mudah)

1. Ekstrak ZIP proyek ini, buka folder `vscode-mobile` di Android Studio.
2. Tunggu Gradle Sync selesai (unduhan dependensi pertama ±10 menit).
3. Hubungkan HP (aktifkan *USB debugging*) atau pilih emulator arm64/x86_64.
4. Tekan **Run ▶** — APK terpasang ke perangkat.

### Cara B — Command line

```bash
cd vscode-mobile
export ANDROID_HOME=$HOME/Android/Sdk        # sesuaikan
./gradlew assembleDebug
# hasil: app/build/outputs/apk/debug/app-debug.apk
```

### APK rilis bertanda tangan

```bash
keytool -genkey -v -keystore vscode-mobile.keystore \
  -alias vscode-mobile -keyalg RSA -keysize 2048 -validity 10000
```

Lalu isi `signingConfigs` di `app/build.gradle.kts` dan jalankan
`./gradlew assembleRelease`.

## Kebutuhan runtime di perangkat

- Android **8.0+** (API 26); arsitektur **arm64**, armv7, atau x86_64.
- ±1,2 GB ruang bebas (unduhan ±300 MB sekali saja).
- **Android System WebView** versi baru (via Google Play).
- Untuk keandalan service di latar belakang: matikan *battery optimization*
  untuk aplikasi ini (Settings → Battery → Unrestricted).

## Pemecahan masalah

| Gejala | Solusi |
|---|---|
| Layar VS Code putih/blank | Perbarui Android System WebView dari Play Store. |
| Server mati saat layar terkunci | Matikan optimasi baterai untuk aplikasi ini. |
| Unduhan rootfs gagal | Coba lagi (resume otomatis), atau impor manual: unduh `rootfs.tar.xz` Debian di perangkat lain lalu Pengaturan → Impor rootfs. |
| `proot` tak mau jalan setelah update sistem | Lihat menu → Lihat log; laporan bug disambut. |
| Ingin `git` di dalam VS Code | Buka terminal di VS Code lalu `apt update && apt install git`. |
| Ingin code-server versi lain | Ubah `Pins.CODE_SERVER_VERSION` di `Pins.kt`, hapus data Linux (Pengaturan), pasang ulang. |

## Struktur kode

```
app/src/main/java/com/vscode/mobile/
├── App.kt             — Application: kanal notifikasi
├── MainActivity.kt    — UI (setup/instalasi/loading/WebView), dialog, unduhan
├── LinuxService.kt    — foreground service: siklus proot, restart, notifikasi
├── Installer.kt       — instalasi bertahap + impor manual + finalisasi atomik
├── LinuxRuntime.kt    — perintah proot, env, config guest, resolv.conf
├── KivyGuiConfig.kt   — port, URL noVNC, password, dan ukuran Xvfb
├── Archive.kt         — pembaca tar (GNU/pax), ar (.deb), XZ/GZIP
├── Net.kt             — unduhan resume+retry, parser indeks apt
├── StateStore.kt      — persistensi status (JSON)
└── Pins.kt            — versi & URL terverifikasi
```

## Keamanan

- code-server **hanya** mendengarkan `127.0.0.1` — tidak dapat diakses dari
  jaringan/internet.
- Default tanpa kata sandi (`auth: none`) agar langsung masuk VS Code; aktifkan
  kata sandi dari Pengaturan bila perlu (mis. perangkat dipakai orang lain).
- Trafik teks polos hanya diizinkan untuk `127.0.0.1`/`localhost`
  (lihat `network_security_config.xml`); semua unduhan ekstensi pakai HTTPS.

## Lisensi & atribusi

Lihat [NOTICE.md](NOTICE.md). Proyek ini menyertakan tautan unduhan ke
komponen pihak ketiga saat instalasi (bukan dibundel di dalam APK):
code-server (MIT), Debian, proot (GPL), libtalloc, libandroid-shmem.

## Kompatibilitas Python

Build 1.1.5 mempertahankan terminal Debian biasa tanpa mencegat perintah `python`/`python3` dan tanpa pemilih interpreter otomatis. Python tidak dijamin terpasang pada rootfs minimal; bila dibutuhkan, pasang sesuai kebutuhan dari terminal Debian (misalnya `apt update && apt install python3`). Dukungan GUI Kivy belum menjadi bagian dari baseline ini; fokus saat ini adalah kompatibilitas runtime inti. Paket Debian yang mungkin sudah terpasang oleh versi lama tidak dihapus otomatis agar data dan pilihan paket pengguna tetap utuh.

## Kenapa `targetSdk 28`?
