# Audit Arsitektur CodeX Studio

Tanggal: 7 Oktober 2026  
Source utama: `codex-studio-hardened-source.zip` diunggah di dalam `codex-studio(project).zip`  
SHA-256 source ZIP: `90de1df790a5564416398835cd9bc5c7e07ea975170518f12f797ba060cd0379`  
Status: **READ/ANALYZE/PLAN selesai sebelum perubahan; P0/P1 terpilih, GUI opt-in, diagnosis katalog dan koreksi redirect mirror tercatat. Follow-up setelah bagian 8 memuat status serta batas verifikasi terbaru.**

## 1. Arsitektur aktual

```text
MainActivity (UI, permissions, install orchestration, WebView, popup/download, keyboard/menu/settings)
├── Installer
│   ├── Net (download/retry/digest + apt Packages parsing)
│   ├── Archive (ar/.deb + tar/xz/gzip extraction)
│   ├── LinuxRuntime (start/config scripts)
│   └── StateStore (AppState JSON, atomic write, cleanup)
├── LinuxService (foreground service, proot process, localhost readiness, restart loop/logs)
├── CodeWebView + KeyPad + MacKeyboard
└── MenuScreen / MenuHost
```

Source merupakan Android host untuk Debian userland melalui proot dan code-server pada WebView; bukan source editor VS Code. `targetSdk=28`, `compileSdk=34`, minSdk 26; Gradle wrapper 8.9, AGP 8.5.2, Kotlin 1.9.24, Java target 17 (`app/build.gradle.kts:10–30`, root `build.gradle.kts`). Source tidak menyertakan test source set maupun instrumented test pada arsip.

Dokumen arsitektur yang tersimpan di `docs/architecture-analysis.md` byte-identik dengan dokumen arsitektur yang disertakan dalam arsip luar. Karena itu, klaim status historis di dalamnya perlu dibaca sebagai catatan baseline sebelumnya, bukan verifikasi ulang source saat ini.

## 2. P0 — blocker keamanan/integritas

### P0.1 — Ekstraksi tar dapat menulis melalui symlink keluar dari root

- Bukti: `app/src/main/java/com/vscode/mobile/Archive.kt:225–233` hanya menormalkan nama entri secara leksikal; `:294–300` membuat symlink memakai target arsip tanpa pemeriksaan containment; `:315–325` kemudian membuka file tujuan dengan `FileOutputStream`.
- Skenario: arsip membuat symlink di bawah root ekstraksi yang menunjuk ke lokasi di luar root, kemudian mengirim entri file dengan path leksikal di bawah symlink tersebut. `safeTarget()` melihat path yang dinormalisasi masih di dalam root, tetapi filesystem mengikuti symlink ketika file dibuat/ditulis. Jalur ekstraksi dipakai untuk rootfs dan code-server (`Installer.kt:203–215`, `:282–294`) dan data `.deb` (`Archive.kt:344–384`).
- Dampak: isi arsip yang tidak tepercaya dapat menulis/mengganti berkas di luar direktori staging dalam batas hak akses proses aplikasi; dapat merusak state atau berkas aplikasi. Ini bertentangan dengan komentar “semua entri divalidasi” (`Archive.kt:24`).
- Keyakinan: tinggi berdasarkan alur source; **belum ada reproduksi terhadap parser Android end-to-end**. Perbaikan wajib mencakup symlink parent, target absolut/relatif, hardlink, direktori parent, serta memastikan file output tidak mengikuti komponen symlink yang lolos dari root. Jangan menghapus dukungan symlink Debian yang sah; semantik path guest perlu dipetakan dengan aman ke root ekstraksi.

## 3. P1 — reliabilitas/runtime/supply chain

### P1.1 — Timeout readiness tidak mengakhiri proses atau mengubah state

- `LinuxService.kt:145–152`, `:183–192`: jika endpoint tidak siap dalam 120 detik tetapi proses masih hidup, nilai `false` tidak ditangani; `proc.waitFor()` menunggu proses keluar tanpa batas. Restart policy (`:155–176`) hanya berlaku setelah proses keluar. UI dapat terus berada pada state Starting meskipun server tidak siap.

### P1.2 — Kegagalan persiapan runtime berada di luar blok penanganan error

- `LinuxService.kt:109–116`: `prepareGuest`, `syncNetworkFiles`, dan `writeStartScript` dieksekusi sebelum blok `try` yang memetakan error ke `ServerState.Error`; exception pada langkah-langkah tersebut dapat mengakhiri coroutine tanpa state terminal/diagnostic yang benar.

### P1.3 — Redirect tidak memvalidasi host tujuan

- `Net.kt:53–63`: host dan scheme awal diperiksa terhadap allowlist, lalu `instanceFollowRedirects=true`. Kode tidak memeriksa ulang URL tujuan/final host. Ini melemahkan boundary host allowlist, terutama untuk metadata rootfs/Termux yang dipakai memilih artifact berikutnya (`Installer.kt:148–155`, `:226–250`). Digest artifact code-server yang dipin mengurangi risiko pada artifact tersebut, tetapi tidak melindungi metadata yang diperoleh dari host redirect.

### P1.4 — Respons teks, download installer, dan impor manual tidak memiliki limit input eksplisit

- `Net.kt:32–44`: body teks dibaca dengan `readText()` tanpa batas byte.
- `Net.kt:116–178`: stream download ditulis sampai EOF; tidak ada hard cap total file.
- `Installer.kt:379–402`: `copyUri()` menyalin sampai EOF tanpa hard cap/storage preflight.
- Batas ekstraksi bukan pengganti batas ukuran saat data sedang diunduh/disalin. Skenario dapat menghabiskan ruang app storage atau heap sebelum ekstraksi.

### P1.5 — Batas total ekspansi tidak mencakup salinan hardlink

- `Archive.kt:302–313`: hardlink direalisasikan dengan salinan isi file target, tetapi tidak menghitung byte tersebut ke `expandedBytes`.
- Limit 3 GiB pada `:318–326` hanya diterapkan ke file regular. Banyak entri hardlink ke file besar dapat menulis lebih besar daripada batas total yang dinyatakan.

### P1.6 — Popup WebView eksternal tidak dibatasi origin dan berbagi third-party cookies

- `MainActivity.kt:599–609` mengalihkan semua URL HTTP/HTTPS non-local ke popup; `:944–948` popup mengizinkan JavaScript, multiple windows, dan third-party cookies; `:950–955` mengizinkan navigasi HTTP/HTTPS tanpa allowlist.
- OAuth/code-server menjelaskan kebutuhan kompatibilitas, tetapi arbitrary origin di popup memperluas area paparan dan berbagi cookie. Dokumentasi sebelumnya sudah mencatat ini sebagai trade-off/backlog; bukan bukti exploit aktual.

### P1.7 — Proses stop/restart tidak sepenuhnya terserialisasi

- `LinuxService.kt:67–75`, `:216–225`, `:304–308`: restart/shutdown memanggil `killTree()` lewat thread terpisah; start loop dapat berjalan bersamaan dengan cleanup lama. Pemeriksaan PID berbasis `/proc` dan rootfs path (`:227–243`) tidak memiliki tes race/false match dalam source. Perlu verifikasi sebelum merombak; hindari perubahan lifecycle luas tanpa reproduksi.

### P1.8 — Import `.deb` mengalokasikan data archive penuh ke memori

- `Archive.kt:369–384`: data archive dibatasi 256 MiB tetapi dibaca seluruhnya ke `ByteArray` sebelum decompression. Batas ada, namun kebutuhan heap puncak, dekompresi, dan beberapa package installer belum diukur pada perangkat. Ini merupakan risiko reliabilitas yang perlu diuji/ditangani, bukan alasan untuk rewrite parser tanpa bukti.

## 4. P2 — maintainability dan cakupan verifikasi

- **P2.1 God object:** `MainActivity.kt` menggabungkan orchestration, permission, storage, lifecycle, popup/WebView, download, keyboard/menu/settings; ini sejalan dengan audit historis. Hindari ekstraksi agresif karena risiko lifecycle.
- **P2.2 Tidak ada test suite pada source:** tidak ditemukan `app/src/test` atau `app/src/androidTest`; CI hanya menjalankan `:app:lintDebug` dan `:app:assembleDebug` (`.github/workflows/build.yml:20–29`). Parser, installer rollback, service, WebView, dan keyboard belum punya regression gate di repo ini.
- **P2.3 Tidak ada abstraction `RuntimeSupervisor`/`IDEServer`:** `LinuxService` dan `LinuxRuntime` sudah menjalankan sebagian tanggung jawab, tetapi interface adapter yang tercantum sebagai target roadmap belum ada. Karena roadmap melarang implementasi masa depan tanpa kebutuhan, ini tetap backlog setelah readiness/ownership bug konkret diperbaiki.
- **P2.4 Corrupt state diam-diam kembali ke default:** `StateStore.kt:32–48`; dokumentasi sebelumnya sudah menyebut marker/diagnostic sebagai backlog.
- **P2.5 Build policy/release coverage:** `app/build.gradle.kts:70–75` menonaktifkan beberapa lint check target-SDK dan release lint; CI tidak menjalankan unit tests atau `assembleRelease`. `targetSdk=28` adalah pilihan arsitektural runtime saat ini, tetapi bukan basis klaim Play Store modern.
- **P2.6 Penamaan/dokumentasi lama:** project root masih bernama `vscode-mobile` (`settings.gradle.kts:11`); theme/application namespace juga membawa nama lama. Perubahan kosmetik ditunda.

## 5. Konfirmasi klaim dokumen terhadap source

- **Dikonfirmasi:** loopback binding `127.0.0.1` di `LinuxRuntime.kt:167–179`; `--link2symlink` dan `--kill-on-exit` di `:50–67`; atomic JSON write/fsync/rename di `StateStore.kt:51–77`; backup/restore runtime di `Installer.kt:327–355`; digest code-server arm64/amd64 di `Pins.kt:36–42`; file/content WebView access off dan mixed content disallowed di `MainActivity.kt:664–676`.
- **Perlu dikoreksi/diuji:** kesiapan server membedakan proses hidup dari endpoint ready, tetapi timeout saat proses tetap hidup tidak ditindaklanjuti; total hardlink copy tidak masuk counter; redirect tidak memverifikasi final host; batas data unduh/salin manual belum ada.
- **Rootfs digest:** `resolveRootfsUrls()` hanya menghasilkan kandidat bila checksum yang valid berhasil diparse (`Installer.kt:239–250`); jadi kandidat tanpa digest tidak dipakai. Namun checksum diambil dari sumber HTTPS yang sama dan belum terverifikasi signature-level.
- **Historis, bukan baseline baru:** laporan menyebut lint/APK berhasil pada baseline sebelumnya; audit ini tidak menganggap klaim itu sebagai hasil build saat ini.

## 6. Batas audit

Audit awal berbasis pembacaan dokumen dan source statis; pada saat itu belum ada reproduksi parser. Setelah patch, regression fixture host-JVM ditambahkan (hasilnya di bagian 8), tetapi HTTP fault-injection dan runtime golden path belum ada. Tidak ada perangkat Android/emulator di lingkungan ini. Arsip awal sehat menurut ZIP CRC; source diekstrak ke `/home/ubuntu/codex-studio` sebelum patch.

## 7. Ringkasan prioritas

| Priority | Temuan | Tindakan |
|---|---|---|
| P0 | Ekstraksi symlink/hardlink dapat meloloskan write containment | Tutup boundary + adversarial regression tests sebelum fitur lain |
| P1 | Timeout readiness dan setup exception service | Buat terminal failure deterministic, cleanup/diagnostic, restart bounded |
| P1 | Redirect allowlist bypass | Validasi setiap hop/final host dan tetap HTTPS |
| P1 | Input jaringan/impor tidak dibatasi | Tetapkan limit/size policy yang kompatibel dengan artifact yang didukung |
| P1 | Hardlink tidak dihitung dalam expanded total | Hitung bytes aktual semua write/copy |
| P1 | Popup eksternal permissive | Backlog hardening dengan jaga alur OAuth; tidak diubah tanpa tes kompatibilitas |
| P2 | Test suite dan CI terbatas | Tambah unit regression untuk parser/state; perlu emulator/device untuk lifecycle/UI |
| P2 | God object/supervisor/adapter belum dipisah | Catat, jangan rewrite tanpa seam dan bukti lifecycle-safe |

## 8. Status setelah patch — 7 Oktober 2026

| Temuan awal | Status sesudah patch | Bukti dan batas verifikasi |
|---|---|---|
| P0.1 Symlink/hardlink keluar dari root ekstraksi | **Diperbaiki** | `Archive.kt` kini memvalidasi canonical parent, memetakan target absolut guest ke rootfs, tidak menulis dengan mengikuti symlink leaf, dan memeriksa hardlink source/destination. Regression adversarial symlink-parent dan hardlink source-outside lulus. |
| P1.1/P1.2 Readiness timeout dan exception saat persiapan | **Diperbaiki sebagian** | Setup guest kini berada di jalur `try/catch`; readiness gate membedakan READY/EXITED/STOP/TIMEOUT, memakai monotonic clock, menghentikan process tree saat timeout, dan membatasi wait penghentian lanjutan hingga 5 detik sebelum state error. Empat state gate diuji deterministik. Stop/restart race P1.7 belum diserialisasi. |
| P1.3 Redirect allowlist | **Diperbaiki pada policy layer** | Redirect diikuti manual, maksimum 5 hop; setiap tujuan harus HTTPS, host allowlist, tanpa userinfo, dan port default/443. Tes menguji host diizinkan, relative redirect, host tak dikenal, downgrade HTTP, userinfo, dan port nonstandar. Belum ada HTTP fault-injection/e2e redirect test. |
| P1.4 Batas resource input | **Diperbaiki** | Metadata teks dibatasi 32 MiB; download dan impor manual dibatasi 4 GiB; Size dari indeks apt dipakai sebagai ukuran eksak bila valid; tersedia preflight `StorageManager#getAllocatableBytes` bila ukuran diketahui. Tes menguji metadata cap dan byte boundary; belum ada tes integrasi Range 200/206/416/checksum mismatch. |
| P1.5 Hardlink tidak masuk expanded total | **Diperbaiki** | File biasa dan salinan isi hardlink memakai satu accounting helper terhadap limit 3 GiB; uji exact-limit dan overflow lulus. |
| P1.8 Alokasi penuh data `.deb` di heap | **Diperbaiki** | `data.tar*` tetap dibatasi 256 MiB tetapi sekarang disalin ber-buffer 64 KiB ke temp file, lalu didekompresi/di-ekstrak dari file; cleanup diuji pada fixture `.deb`. |
| P1.6 Popup eksternal WebView | **Belum diubah** | Ditahan agar alur OAuth/cookie sharing tidak rusak tanpa tes kompatibilitas origin. Tetap backlog hardening. |
| P1.7 Stop/restart race | **Belum diubah** | Tidak ada reproduksi race pada host JVM dan perlu verifikasi lifecycle service pada perangkat; jangan mengklaim race selesai. |

**Verifikasi akhir yang dapat direproduksi:** `:app:clean`, `:app:lintDebug`, Kotlin/Java compile, `:app:assembleDebug`, dan `:app:testDebugUnitTest` lulus secara berurutan. Ada 16 tes JVM (6 Archive, 6 Net, 4 readiness), 0 failure/error. Lint tetap 60 warnings seperti baseline, tanpa error. APK debug `com.vscode.mobile` 1.1.0 (code 8), min SDK 26/target SDK 28, 6,097,709 byte; signature APK v2 terverifikasi. SHA-256 APK: `8a79bf26cef645b8f6ecf728fcf85d8cfa86507bd0dad674f94c11556274611d`.

**Batas klaim:** tidak ada emulator/perangkat ADB dalam lingkungan ini; pemasangan APK, golden path installer, process-tree behavior Android, UI/WebView/OAuth, serta keyboard/gesture belum diverifikasi langsung pada perangkat. Tes redirect adalah pengujian policy helper, bukan server HTTP tiruan/live. CI workflow telah diperbarui untuk menjalankan lint, compile, APK, dan unit-test task, tetapi belum dieksekusi pada GitHub Actions.

## 9. MVP GUI Python/Kivy — 7 Oktober 2026

Pengguna memilih arsitektur **Python Debian + Xvfb + VNC/noVNC lokal**. Implementasi menambah jalur GUI yang opt-in; tidak ada rewrite besar dan perintah default code-server tetap memakai `/root/.vscmob/start.sh`.

- `KivyGuiConfig.kt` memusatkan port, password sesi, URL viewer loopback, serta validasi ukuran layar. Dimensi diambil dari area WebView Android saat mulai, dibatasi 320–4096 px, dan dikirim ke Xvfb sebagai display 24-bit.
- `LinuxRuntime` memakai builder proot yang sama untuk command default dan runner GUI terpisah; asset `kivy-vnc-session.sh` dan demo Python disalin idempoten tanpa menimpa workspace. `/root/app.py` mendapat prioritas bila sudah ada.
- `LinuxService` mengelola `GuiState`, proses dan job GUI terpisah, readiness noVNC, start/stop serta cleanup. Back dari viewer dan status Stopped/Error mengembalikan pengguna ke code-server; stop/restart service Linux turut menutup GUI.
- Paket GUI di-install on-demand setelah konfirmasi pengguna pertama kali. VNC dan websockify terikat ke `127.0.0.1`; Xvfb memakai `-nolisten tcp`; x11vnc memakai password acak per sesi dan file autentikasi temporer. URL password memakai fragment noVNC, bukan HTTP query. Port VNC tidak dibuka ke jaringan.
- README menjelaskan dependensi Debian, cara memulai/menghentikan sesi, resource tambahan, dan batas MVP.

**Bukti:** `clean → lint → Kotlin/Java compile → assembleDebug → testDebugUnitTest` semuanya sukses; 60 lint warnings sama dengan baseline source patched sebelum GUI (perbandingan langsung pada `codex-studio-patched-source.zip`), tanpa warning baru; 21 tes unit lulus (0 failure/error); shell runner lolos `bash -n`, demo lolos compile Python; APK debug memiliki signature v2 valid dan memuat kedua asset Kivy. Metadata, ukuran, hash APK dan rincian gate ada di `docs/verification-baseline.md`.

**Belum terverifikasi:** tidak ada emulator/ADB/perangkat Android. Karena itu pemasangan aplikasi, proses apt dalam rootfs asli, rendering/GL Kivy pada perangkat, interaksi sentuh/keyboard noVNC, dan stabilitas lifecycle pada OEM tertentu tidak dinyatakan lulus. Belum ada instrumented test service stop/start race. Apt tetap memerlukan jaringan saat dependency belum ada dan total unduhan bergantung arsitektur/dependency closure.


## 10. Follow-up installer: entri tar root `.` — 7 Oktober 2026

Screenshot pengguna menunjukkan instalasi berhenti pada 2% dengan `path arsip di luar direktori tujuan: .`. Root cause ditemukan di `Archive.safeTarget`: entri tar direktori `.`/`./` dinormalisasi ke root ekstraksi yang sah, tetapi parser lalu memvalidasi `target.parent`; parent itu memang berada di luar root dan memicu penolakan keliru.

Perbaikan terlokalisasi: setelah pemeriksaan traversal lexical, jika target tepat sama dengan root ekstraksi, parser mengembalikan direktori root tanpa memeriksa parent-nya. Semua target non-root tetap melalui validasi parent canonical/symlink; penulisan file, symlink, dan hardlink tidak mendapat pengecualian ini.

Regression `acceptsRootDirectoryEntriesDotAndDotSlash` **gagal sebelum patch** dengan `IOException` yang sama, lalu **lulus sesudah patch**. Full suite: 22 tes, 0 failure/error; lint 60 warnings (baseline identik); clean/lint/compile/APK/regression PASS. APK update `com.vscode.mobile` 1.1.1/code 9: 6,112,012 byte, SHA-256 `106829b3d82912458fe346806f6b31082dcd4d4632407095d425bd98255e47de`, signature v2 verified.

**Batas klaim:** test parser berjalan pada fixture host-JVM, bukan pemasangan rootfs pada ponsel. APK belum diuji pada perangkat; pengguna perlu mencoba versi terbaru untuk memastikan alur instalasi pada rootfs/perangkat miliknya.


## Follow-up katalog rootfs Debian tidak terbaca — 7 Oktober 2026

Screenshot versi 1.1.1 menampilkan pesan umum karena `Installer.resolveRootfsUrls()` menangkap semua exception dari `Net.getText()` lalu mengembalikan daftar kosong; penyebab DNS/TLS/timeout/HTTP atau respons metadata berbeda tidak ditampilkan. Selain itu, resolver mengambil index sebelum mengirim progress fase Debian, sehingga UI tetap menampilkan “Mengekstrak proot…” saat request rootfs berlangsung. Dari sandbox, index resmi saat diperiksa merespons HTTP 200, tiga link tanggal terbaca oleh Java `HttpURLConnection` dengan User-Agent aplikasi, dan SHA256SUMS terbaru cocok. Ini membuktikan endpoint dari sandbox pada waktu tersebut, bukan dari handset.

Perubahan 1.1.2 menambahkan `RootfsCatalog` yang mengembalikan detail error index/checksum/format, mencoba checksum hingga tiga build terbaru, serta mengirim progress fase Debian sebelum request. Lima tes fixture baru mencakup bentuk link timestamp encoded/literal, checksum, HTTP failure, index tanpa link, dan kegagalan checksum. Tidak ada fallback ke host rootfs tak terverifikasi.

Gate `clean → lint → compile → APK → regression` lulus; 27 tes, 0 failure/error/skipped; 60 temuan lint identik dengan baseline patched; APK `1.1.2`/code 10 berukuran 6,112,864 byte, signature v2 valid, SHA-256 `dd41136c621e99038e44346a525954ce7d5d8ad56f96516ecfa15c77397b83c8`. Rootfs arm64 resmi yang sama juga diunduh sebagai impor manual: 94,952,764 byte, SHA-256 cocok `be17e8c6fe2d9173a3fd82ed5d5cd814248a27d8e5cad54ab946a546c74cb590`, XZ valid dan berisi `/usr/bin/bash`.

**Batas:** tidak tersedia Android device/emulator untuk menguji koneksi handset. Jika penyebabnya jaringan handset yang tidak dapat menjangkau `images.linuxcontainers.org`, resolver baru akan mengungkap sebabnya tetapi tidak dapat memperbaiki kebijakan jaringan itu sendiri; rootfs manual yang diverifikasi menjadi jalur bypass.


## Follow-up redirect mirror Linux Containers — 7 Oktober 2026

Pesan lengkap pengguna membuktikan index Debian berhasil dibaca; kegagalan terjadi saat mengambil `SHA256SUMS` untuk ketiga build terbaru. Server mengalihkan request ke `sgp1mirror01.do.images.linuxcontainers.org`, tetapi `Net.allowedHosts` hanya menerima host persis `images.linuxcontainers.org`. Uji upstream dari sandbox mengikuti redirect yang sama dan memperoleh HTTP 200 serta SHA rootfs yang sesuai manifest.

Patch kecil mengizinkan nama host utama dan subdomain yang berakhiran tepat `.images.linuxcontainers.org`, tetap mewajibkan HTTPS, tanpa userinfo, serta port default/443. Tes red `allowsRedirectToOfficialLinuxContainersMirrorSubdomain` gagal sebelum patch pada allowlist; sesudah patch seluruh 8 `NetTest` lulus. Tes tambahan menolak `evilimages.linuxcontainers.org` dan mirror dengan port 444; tes redirect host asing/downgrade yang lama tetap lulus.

Full gate versi 1.1.3/code 11: clean, lint, compile, APK, regression PASS; 29 tes, 0 failure/error/skipped; set 60 lint identik dengan baseline. APK 6,112,936 byte, signature v2 valid, SHA-256 `45130f952be1359dd54464450d19e5bbd9ae56365f3d8bc99d59da86717606d8`. Ini menutup root cause allowlist yang dilaporkan. **Batas:** APK belum dapat dipasang/dijalankan pada handset dari lingkungan ini; keberhasilan instalasi keseluruhan tetap perlu dikonfirmasi pengguna.


## Follow-up GUI satu aksi + stop saat tutup — versi 1.1.4

Atas permintaan pengguna, dua entri menu **Jalankan GUI**/**Hentikan GUI** digabung menjadi satu tombol toggle: saat sesi tidak aktif, satu tap menyiapkan/menjalankan Python/Kivy + Xvfb + x11vnc + noVNC; saat aktif, tombol berubah menjadi **Tutup GUI Kivy** dan meminta LinuxService menghentikan sesi. Tekan Back Android dari viewer (termasuk saat sesi masih Starting) sekarang menghentikan GUI dan mengembalikan WebView ke code-server; service code-server tidak dihentikan.

Runner Bash sudah memasang cleanup trap untuk menghentikan Xvfb, x11vnc dan websockify saat proses sesi berakhir. Implementasi memakai penghentian sesi GUI yang sudah ada; tidak ada dependency baru atau rewrite arsitektur. README dan label UI disinkronkan.

Verifikasi versi 1.1.4/code 12: clean, lint, compile, APK, regression PASS; 29 tes lulus, 0 failure/error/skipped; set lint 60 tidak berubah dari baseline; APK signature v2 valid. Uji tap/Back pada ponsel fisik tetap belum tersedia.


## Retirement GUI — baseline 1.1.5 (7 Oktober 2026)

Bagian GUI di atas adalah riwayat implementasi yang kemudian dipensiunkan sesuai keputusan pengguna. Build 1.1.5 menghapus seluruh jalur runtime/asset Kivy dan VNC/noVNC/Xvfb/websockify, termasuk state/intent service, runner, viewer, terminal bridge dan shim Python. Source/test aktif dipindai dan tidak lagi memuat marker tersebut. code-server, Debian/proot, mirror allowlist dan hardening sebelumnya dipertahankan. Tidak ada `apt purge` terhadap rootfs lama, sehingga paket yang sebelumnya dipasang pengguna dapat tetap berada di Debian tetapi tidak lagi diluncurkan oleh aplikasi. Detail dan gate: `docs/kivy-gui-retirement-plan.md` serta verification record.
