# Analisis Arsitektur CodeX Studio — Source Update

Tanggal audit: 7 Oktober 2026  
Source: `codex-studio-source.zip`  
Status audit: **struktur dipahami; lint, Kotlin, resource, dan debug APK berhasil diverifikasi setelah 2 blocker API-level pada theme navigation bar diperbaiki secara minimal**.

## 1. Ringkasan eksekutif

Source update ini adalah evolusi nyata dari project Android wrapper sebelumnya. Ia tetap bukan source Workbench VS Code, tetapi Android host yang menjalankan code-server di userland Debian melalui proot dan menampilkannya di WebView.

Perubahan arsitektur penting dibanding project lama:

- UI startup/install sekarang berbasis langkah (`StepView`, `item_step.xml`) dengan progress phase 0–3.
- Menu tiga jari dipisahkan menjadi komponen `MenuScreen` fullscreen bergaya grouped/iOS.
- Keyboard virtual dipisah menjadi `MacKeyboard` dan mekanisme input/modifier dipusatkan di `KeyPad`.
- WebView khusus `CodeWebView` menangani IME dan injeksi modifier Ctrl/Shift/Alt/Command.
- Runtime proot mendapatkan `--link2symlink` untuk mengatasi kegagalan `dpkg` membuat hard link pada storage Android.
- Runtime menambahkan `/dev/shm`, fake `/proc`, konfigurasi apt/dpkg, DNS/hosts/hostname, serta environment shell yang lebih lengkap.
- Branding, logo launcher density resources, light/dark color resources, dan onboarding CodeX Studio sudah tersedia.

## 2. Model arsitektur aktual

```text
Android Application (CodeX Studio)
├── MainActivity
│   ├── startup permission flow
│   ├── install/loading/web UI state machine
│   ├── 3-finger MenuScreen
│   ├── 4-finger MacKeyboard
│   ├── settings/log/about dialogs
│   └── WebView lifecycle + OAuth popup
│
├── LinuxService (foreground service)
│   ├── starts/stops/restarts guest process
│   ├── monitors localhost:8080
│   ├── drains stdout/stderr to log buffer/file
│   └── posts status notification
│
├── Installer
│   ├── Termux proot libraries
│   ├── Debian rootfs
│   ├── code-server tarball
│   └── atomic staging → files/linux finalization
│
├── LinuxRuntime
│   ├── proot command + binds
│   ├── environment
│   ├── guest preparation
│   ├── DNS/hosts/hostname
│   ├── fake /proc files
│   └── code-server start/config scripts
│
└── Shared support
    ├── Archive: tar/deb/XZ/GZIP/symlink/hardlink
    ├── Net: HTTPS download + resume/retry
    ├── StateStore: JSON state
    └── Pins: external version/URL catalog
```

## 3. Modul dan tanggung jawab

| Modul | Tanggung jawab | Coupling utama |
|---|---|---|
| `MainActivity.kt` | Orkestrasi UI, permission, install, WebView, gesture, menu, settings | Tinggi; bergantung pada hampir semua komponen |
| `MenuScreen.kt` | Menu fullscreen, grouped sections, scale slider, switches | `MenuHost` interface ke Activity |
| `MacKeyboard.kt` | Layout keyboard QWERTY, F-row, modifiers, repeat, haptic | `KeyPad` |
| `KeyPad.kt` | State machine modifier dan Volume keys, raw KeyEvent/IME chars | `WebView` target dan `MacKeyboard` |
| `CodeWebView.kt` | InputConnection wrapper untuk IME + modifier translation | `KeyPad` |
| `Installer.kt` | Download, extract, phase progress, staging/finalize | `Net`, `Archive`, `LinuxRuntime`, `StateStore` |
| `LinuxRuntime.kt` | Runtime contract guest Android ↔ Debian | `Pins`, filesystem, proot |
| `LinuxService.kt` | Long-running server process dan restart policy | `LinuxRuntime`, `StateStore`, notification |
| `Archive.kt` | Parser/extractor tar, pax, symlink, hardlink, deb | `Installer` |
| `Net.kt` | HTTPS GET, redirects, retry, Range resume | `Pins`, `Installer` |
| `StateStore.kt` | State instalasi/config JSON | Activity, Installer, Runtime |

## 4. Lifecycle dan state machine

### Activity

`MainActivity` memiliki mode:

- `Setup`
- `SetupFailed`
- `Installing`
- `Loading`
- `Web`

Saat `onCreate`:

1. Inflate binding.
2. Buat `KeyPad` dan baca `volkeys` dari SharedPreferences.
3. Bind branding/version dan langkah install.
4. Observe `LinuxService.state`.
5. Jika runtime valid, start service dan masuk `Loading`.
6. Jika belum valid, reset UI dan masuk `Installing`.
7. Meminta storage permission langsung.
8. Setelah permission selesai, `continueAfterStorage()` memulai install otomatis.

### Installer

Pipeline staging bersifat atomik secara konseptual:

1. Buat `files/linux-staging` baru.
2. Unduh/ekstrak proot dan library.
3. Unduh/ekstrak rootfs Debian.
4. Unduh/ekstrak code-server.
5. Tulis runtime script/config/state ke staging.
6. Hapus `files/linux` lama.
7. Rename staging menjadi `files/linux`.

Kekuatan: instalasi gagal tidak meninggalkan state `.state.json` valid di final directory.  
Risiko: finalisasi menghapus runtime lama sebelum rename staging berhasil; kegagalan rename dapat meninggalkan aplikasi tanpa runtime lama.

### Service

`LinuxService` menjalankan loop:

1. `ensureRuntimeDirs`.
2. `syncNetworkFiles`.
3. `writeStartScript`.
4. `ProcessBuilder(LinuxRuntime.prootCommand())`.
5. Drain output ke log.
6. Probe `127.0.0.1:8080`.
7. Restart maksimal 5 kali dalam window restart.

## 5. Temuan positif

### Runtime dan dpkg

- `--link2symlink` secara langsung menargetkan error `dpkg: error creating new backup file ... status-old: Permission denied` karena hard link tidak tersedia/ditolak pada filesystem Android.
- `--kill-on-exit` mencegah proses guest yatim.
- `/dev/shm` dipetakan untuk Python/Chromium/multiprocessing.
- Fake `/proc` mengurangi kegagalan tool Linux akibat akses procfs Android.
- `prepareGuest()` membuat direktori standar dan konfigurasi apt/dpkg.
- `resolv.conf` ditulis ulang dengan penghapusan symlink terlebih dahulu; ini memperbaiki kasus dangling symlink Debian.
- Environment shell menyetel `USER`, `LOGNAME`, `SHELL`, `HOME`, `PATH`, dan `TMPDIR` secara eksplisit.

### UI dan input

- Menu dan keyboard dipisah dari Activity sehingga lebih mudah diiterasi.
- Gesture 3 jari diberi delay 220 ms agar 4 jari tidak salah terdeteksi sebagai menu.
- `KeyPad` mendukung one-shot, locked modifier, Volume Down Ctrl, Volume Up Fn, dan konsumsi state.
- `CodeWebView` menghindari IME composing text agar kombinasi Ctrl+huruf dapat dikirim konsisten.
- UI install menampilkan phase terpisah dan progress monotonic melalui `maxPercent`.
- `values-night` dan token warna terpisah sudah disiapkan.

### Build dan distribusi

- Java/Kotlin target 17.
- Android Gradle Plugin 8.5.2, Kotlin 1.9.24, Gradle wrapper 8.9.
- CI GitHub Actions menggunakan Java 17 dan Android platform 34.
- Launcher bitmap tersedia pada beberapa density dan adaptive icon API 26+.

## 6. Temuan risiko dan prioritas

### P0 — build blocker (sudah diperbaiki pada baseline aktif)

1. `app/src/main/res/values/themes.xml` dan `values-night/themes.xml` memakai `android:windowLightNavigationBar`, yang membutuhkan API 27, sementara `minSdk = 26`.
2. Validasi pertama menemukan **2 error** dan sekitar **59 warning** pada lint.
3. Baseline aktif memperbaiki error ini dengan `tools:targetApi="27"` pada kedua atribut; `lintDebug` dan `assembleDebug` kemudian berhasil.

### P1 — integritas supply chain

1. Download eksternal sekarang memverifikasi SHA-256 untuk package Termux, rootfs LXC yang memiliki `SHA256SUMS`, dan code-server release 4.140.0 arm64/amd64.
2. Rootfs URL masih dicari dengan scraping directory listing LXC dan memilih tanggal terbaru; checksum mengurangi risiko tampering tetapi belum menjamin reproducibility penuh.
3. Apt Packages index belum diverifikasi signature-level; checksum package berasal dari index HTTPS yang diambil langsung.
4. Code-server armv7 release lama tanpa digest API ditolak untuk remote install; pengguna masih dapat memakai impor manual.

### P1 — parser/archive robustness

1. Metadata tar dibatasi 4 MiB; entri individual dibatasi 512 MiB; total hasil ekstraksi 3 GiB; jumlah entri 250.000.
2. `.deb` `data.tar*` dibatasi 256 MiB; isi tetap dibaca ke memory pada jalur `.deb`, sehingga streaming penuh masih menjadi backlog.
3. Hardlink yang target source-nya belum ada sekarang gagal tertutup, bukan dilewati.
4. Compression-ratio limit dan corpus test untuk symlink/pax/hardlink masih perlu ditambahkan.

### P1 — Android/Play policy compatibility

1. `targetSdk = 28` dipertahankan untuk eksekusi userland dari app storage. Ini mungkin diperlukan untuk arsitektur saat ini, tetapi tidak kompatibel dengan target API Play Store modern.
2. `MANAGE_EXTERNAL_STORAGE` dan legacy storage adalah restricted/high-risk area untuk distribusi Play Store.
3. Foreground service `dataSync` perlu ditinjau ulang terhadap deklarasi FGS Android modern.
4. Sebelum Play Store, runtime perlu direstrukturisasi: native binaries di lokasi yang diizinkan, Storage Access Framework untuk file user, dan target API modern.

### P1 — state dan recovery

1. `StateStore.writeTo()` sekarang memakai temp file, `fd.sync()`, dan atomic move dengan fallback.
2. Finalisasi sekarang memindahkan runtime lama ke `linux-previous` dan memulihkannya bila rename staging gagal.
3. `StateStore.read()` masih mengembalikan default state pada JSON rusak; corruption marker/diagnostic masih backlog.

### P1 — WebView/network boundary

1. JavaScript/DOM storage/database/multiple windows tetap aktif karena dibutuhkan code-server/OAuth; third-party cookies masih aktif sebagai compatibility trade-off.
2. Mixed content ditolak, Safe Browsing aktif, dan file/content access dimatikan pada main WebView maupun popup.
3. `handleDownload()` sekarang hanya menerima HTTPS atau HTTP localhost, menolak credential URL, membatasi 512 MiB, sanitasi nama, dan menulis `.part` lalu rename.
4. Popup eksternal masih dibuka internal untuk OAuth; allowlist origin dan isolasi cookie lebih ketat masih backlog.

### P2 — maintainability

1. `MainActivity` masih menjadi god object: install, WebView, popup, settings, permission, storage, keyboard, gesture, dan menu host.
2. `MenuHost` membantu, tetapi `MenuScreen` masih bergantung pada banyak warna/string dan state yang disuplai Activity.
3. `StateStore` memakai boolean preferences terpisah (`volkeys`, `scale`, `allfiles_asked`) di luar `AppState`; konfigurasi tersebar.
4. `theme` masih bernama `Theme.VSCodeMobile` meskipun branding CodeX Studio.
5. README menyebut folder/command `vscode-mobile` dan beberapa label lama; perlu sinkronisasi dokumentasi.
6. CI menghapus file lama yang tidak ada pada source (`bg_logo.xml`, `ic_launcher_fg.xml`, `menu_main.xml`); ini indikasi CI belum direkonsiliasi penuh dengan struktur baru.

## 7. Kontrak fungsional yang sebaiknya dipertahankan

- Startup install otomatis setelah permission flow.
- Tiga jari membuka menu fullscreen.
- Empat jari membuka keyboard virtual.
- Volume Down/Up modifier behavior ala Termux.
- `dpkg` compatibility via `--link2symlink`.
- code-server bind hanya ke `127.0.0.1`.
- Staging install agar partial install tidak dianggap valid.
- Manual rootfs/code-server import.
- Foreground service restart policy dan log viewer.

## 8. Roadmap peningkatan

### Fase A — baseline replacement

- Perbaiki 2 lint blocker theme API 27.
- Rebuild debug APK dan pastikan archive valid.
- Copy source baru ke project path aktif.
- Tambahkan architecture report ini ke source baru.
- Reconcile CI path/artifact name dan README branding.

### Fase B — reliability

- Atomic state write dan rollback-safe finalization.
- Streaming archive extraction dan resource limits.
- Hardlink/symlink test corpus.
- Installer retry matrix per ABI/network failure.
- Instrumented tests untuk gesture, keyboard, permission, service restart.

### Fase C — supply chain/security

- SHA-256 pinning per artifact.
- Signature/metadata verification jika upstream mendukung.
- Redirect/final-host allowlist.
- Download size and filename validation.
- WebView popup/download hardening.

### Fase D — Android modernization

- Analisis ulang target API 36 dan eksekusi binary.
- Migrasi storage ke SAF/app-specific + explicit user workspace access.
- Evaluasi FGS type dan notification permission.
- Separate runtime execution strategy from UI host.

## 9. Kesimpulan

Source update adalah **baseline yang lebih baik dan layak menggantikan project lama**, terutama pada UI mobile, keyboard, startup installer, dan kompatibilitas dpkg. Namun statusnya belum “verified clean” karena lint gagal pada dua atribut theme API 27 dan masih ada warning/risiko arsitektural yang perlu ditangani sebelum rilis production.

Setelah perbaikan lint minimal, source ini aman untuk dipromosikan sebagai project aktif untuk iterasi berikutnya. Perbaikan supply-chain, archive limits, rollback, target API, dan WebView security harus menjadi backlog prioritas sebelum Play Store.


## 10. Status patch bertahap — 7 Oktober 2026

Patch dilakukan dengan checkpoint lint + compile + APK archive setelah setiap fase:

| Prioritas | Hasil | Verifikasi |
|---|---|---|
| Supply-chain | **Diimplementasikan sebagian besar**: HTTPS/host allowlist, SHA-256 Termux package index, rootfs LXC `SHA256SUMS`, code-server release digest; remote artifact tanpa checksum ditolak | Lulus checkpoint |
| Archive safety | **Diimplementasikan**: batas metadata, ukuran entri, total ekspansi, jumlah entri, batas `.deb`, hardlink missing fail-closed | Lulus checkpoint |
| Rollback installer | **Diimplementasikan**: runtime lama dipindah ke backup sementara, restore saat finalisasi gagal | Lulus checkpoint |
| Atomic state | **Diimplementasikan**: temp file, `fd.sync()`, atomic move dengan fallback | Lulus checkpoint |
| WebView security | **Diimplementasikan**: no mixed content, Safe Browsing, user gesture media, download HTTPS/local-only, size limit, filename sanitization, `.part` + fsync + rename | Lulus checkpoint |
| Target API modern | **Belum diaktifkan**: ditahan karena runtime execution/storage belum kompatibel dan SDK 36 belum tersedia; guardrail/rencana migrasi ditulis di `docs/target-api-migration.md` | Tidak diklaim selesai |
| Maintainability | **Sebagian**: helper WebView dipusatkan dan pipeline dokumentasi diperjelas; pemecahan penuh MainActivity ditahan agar tidak menumpuk risiko lifecycle | Build tetap lulus |

APK berhasil dibangun pada setiap checkpoint yang selesai. Warning tersisa hanya deprecated API/non-blocking; tidak ada lint error.

## 11. Pembaruan hasil audit/patch — 7 Oktober 2026

Bagian 1–10 di atas adalah dokumen baseline yang diberikan bersama source; pernyataan status dan warning di dalamnya bersifat historis. Audit terhadap source hardened yang aktif serta hasil perubahan sesi ini dicatat lebih rinci di `docs/architecture-audit.md` dan `docs/verification-baseline.md`.

Patch saat ini mempertahankan arsitektur utama, tanpa rewrite: parser archive menutup write escape melalui symlink/hardlink, menjaga semantik symlink guest Debian, menghitung salinan hardlink terhadap budget ekstraksi, dan mengalirkan data `.deb` melalui temp file terbatas. `LinuxService` kini menangani exception setup dan readiness timeout secara bounded. `Net` memvalidasi tiap redirect dan membatasi resource; installer meneruskan ukuran apt serta preflight kapasitas storage.

Final verification: clean, lint, compile, debug APK, signature v2, dan 16 unit regression tests lulus; lint tetap 60 warnings. Workflow CI kini meminta lint, compile, debug APK, dan unit tests, tetapi belum dijalankan pada GitHub Actions. Tidak ada emulator/ADB, sehingga instalasi APK, runtime Debian/code-server, WebView/OAuth, input/gesture, dan golden path perangkat **belum diverifikasi**. P1 WebView popup-origin policy dan race stop/restart masih terbuka.


## 12. Pembaruan lanjutan: GUI Python/Kivy opt-in — 7 Oktober 2026

Bagian analisis dan status patch sebelumnya adalah catatan historis untuk tahap P0/P1. Setelah pengguna memilih opsi Python di Debian dengan Xvfb + VNC lokal, arsitektur utama Android host → Debian/proot → code-server dipertahankan. GUI ditambahkan sebagai proses sibling opt-in, bukan desktop environment dan bukan pengganti code-server.

Alur baru: menu tiga-jari → persetujuan dependency apt pertama kali → proses GUI di `LinuxService` → Xvfb 24-bit pada resolusi area WebView Android → Kivy melalui Mesa software rendering → x11vnc loopback → websockify/noVNC loopback pada port terpisah → viewer WebView. `LinuxRuntime` mempertahankan path script code-server dan menyediakan command GUI terpisah. Runner memilih `/root/app.py` atau demo yang dikelola aplikasi, serta membersihkan proses dan file autentikasi saat berhenti.

Implementasi diuji melalui config regression, lint, Kotlin/Java compile, APK signature/asset inspection dan unit suite; ringkasan angka final ada di `docs/verification-baseline.md`. Kesimpulan tahap ini: source dan debug APK berhasil dibangun, tetapi aplikasi belum dipasang/dijalankan pada emulator atau perangkat. Rendering Kivy, paket apt di rootfs, input sentuh/keyboard, serta lifecycle Android tetap memerlukan verifikasi perangkat dan tidak diklaim sebagai fitur yang sudah terbukti runtime.


## 13. Follow-up installer rootfs: entri `.` — 7 Oktober 2026

Laporan pengguna saat setup menunjukkan kegagalan path `.` pada ekstraksi arsip. Parser kini menerima metadata direktori root tar setelah lexical check, tanpa memeriksa parent yang memang berada di luar root extraction. Entry `.` dan `./` diuji bersama ekstraksi file anak; tes gagal sebelum perubahan, lulus setelahnya, dan seluruh 22 unit regression lulus. Semua gate build kembali lulus; lint tetap sama dengan baseline. APK update dibuat sebagai 1.1.1/code 9. Device install belum diverifikasi.


## Follow-up 2 — resolusi katalog rootfs dan error jaringan (7 Oktober 2026)

Kegagalan pada screenshot versi 1.1.1 terjadi sebelum ekstraksi rootfs: resolver katalog memanggil `Net.getText()` lalu menelan exception dan menyatakan daftar kosong. Kode juga belum mengirim event progress fase Debian sebelum akses index. Hal ini berbeda dari regression entri arsip `.` yang diperbaiki pada versi 1.1.1.

Versi 1.1.2 memindahkan parsing/resolusi ke helper `RootfsCatalog` agar listing timestamp dan manifest SHA dapat diuji dengan fixture; error transport, format index, dan checksum kini dibedakan, sedangkan UI masuk ke fase Debian sebelum fetch. User-Agent aplikasi dari sandbox menerima HTTP 200 dan index live cocok dengan parser; rootfs arm64 94,952,764 byte cocok dengan SHA upstream dan XZ valid. Bukti sandbox tidak mewakili jaringan handset. Karena belum ada perangkat Android, diagnosis akhir pada ponsel tetap menunggu percobaan APK 1.1.2; bila domain upstream tidak dapat dijangkau, file rootfs terverifikasi bisa diimpor manual.


## Follow-up 3 — mirror regional Linux Containers (7 Oktober 2026)

Error 1.1.2 membuktikan index Debian sudah lolos; request checksum kemudian mendapat redirect ke `sgp1mirror01.do.images.linuxcontainers.org`. `Net.open()` memvalidasi setiap redirect secara manual, tetapi allowlist sebelumnya hanya menerima host index persis dan menolak mirror subdomain. Perubahan mempertahankan boundary itu—HTTPS saja, userinfo dilarang, port hanya default/443—seraya menerima hostname dengan suffix DNS tepat `.images.linuxcontainers.org`. Tes meliputi mirror observed, lookalike hostname, port alternatif, downgrade dan host eksternal. Full regression 1.1.3 lulus 29 tes dan build APK berhasil. Device install masih belum diverifikasi.


## Follow-up 4 — sesi GUI satu aksi dan close-to-stop (7 Oktober 2026)

Lapisan menu kini menampilkan satu kontrol status-sadar: mulai ketika GUI Idle/Stopped/Error, tutup ketika Starting/Running. MainActivity memetakan Android Back saat GUI aktif ke `LinuxService.stopKivyGui()` dan mengembalikan WebView ke halaman code-server. Service server tidak dihentikan; hanya proses proot GUI yang berakhir sehingga cleanup runner membersihkan Xvfb/VNC/websockify. Full build/test lulus, tetapi interaksi perangkat nyata belum dapat diuji dari sandbox.


## Status saat ini — GUI Kivy/VNC retired (7 Oktober 2026)

Bagian GUI pada catatan historis di atas tidak menggambarkan build saat ini. Pada versi 1.1.5, seluruh runtime, UI, konfigurasi, bridge dan asset GUI Kivy/VNC/noVNC/Xvfb/websockify dihapus untuk kembali ke baseline kompatibilitas inti. Code-server, service Debian/proot, terminal biasa, patch keamanan dan dukungan redirect mirror dipertahankan. Rootfs pengguna tidak dimodifikasi untuk menghapus paket lama. Source scan aktif bersih; hasil build final dicatat setelah gate selesai di `docs/verification-baseline.md`.
