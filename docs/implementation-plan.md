# Rencana Implementasi CodeX Studio

Tanggal rencana: 7 Oktober 2026  
Status: **P0/P1 terpilih, MVP GUI opt-in, dan koreksi katalog/redirect rootfs telah diimplementasikan bertahap; status handset tetap NOT VERIFIED.**
Source baseline: `codex-studio-hardened-source.zip`, SHA-256 `90de1df790a5564416398835cd9bc5c7e07ea975170518f12f797ba060cd0379`.

## Prinsip

- Pertahankan source hardened sebagai baseline; hindari rewrite besar dan perubahan behavior yang tidak diminta.
- Dahulukan containment arsip karena dapat memengaruhi data di luar root ekstraksi.
- Satu unit kecil per perubahan, regression test lebih dahulu bila seam jelas, lalu checkpoint **lint → compile → APK → regression**.
- Tidak melonggarkan pemeriksaan keamanan demi build hijau.
- Pisahkan fakta statis, hasil build, tes di simulator/perangkat, dan klaim penggunaannya.
- Jangan mengklaim install perangkat/golden path jika tidak tersedia Android device/emulator.

## Urutan

### Fase 0 — Read/audit/baseline (sudah dilakukan)

1. Baca kedua dokumen arahan dan seluruh struktur source ZIP utama.
2. Catat arsitektur, kontrak fitur, P0/P1/P2, dan bukti file:baris pada `docs/architecture-audit.md`.
3. Jalankan baseline yang dapat dijalankan; catat hasil di `docs/verification-baseline.md`.
4. Saat audit awal, `gradlew --version` berhasil tetapi task Android terblokir karena SDK belum tersedia; setelah persetujuan pengguna, SDK/JDK disiapkan dan baseline final dijalankan sebelum patch. Tidak ada kode aplikasi yang diubah sebelum audit/rencana tersimpan.

### Fase 1 — P0: containment ekstraksi arsip

1. Tetapkan semantik aman symlink rootfs Debian: absolute guest target harus dipetakan ke root ekstraksi, bukan filesystem host; relative target harus tetap contained.
2. Cegah traversal melalui komponen parent symlink dan hardlink; cek containment setelah resolusi, bukan lexical normalize saja.
3. Uji test-first dengan tar fixture: `../` traversal, absolute target keluar, relative target keluar, symlink-parent write, hardlink/path parent, symlink Debian valid (mis. `/bin`/`/var/lock` bila relevan), entry normal, arsip terpotong.
4. Pertahankan ekstraksi streaming, batas entry/count/expanded bytes, mode permission, progress, dan symlink valid yang diperlukan Debian.
5. Gate: tes red sebelum patch, lint, compile, APK debug, tes regresi parser. Jika SDK tidak tersedia, stop/checkpoint BLOCKED; jangan menganggap static compile cukup.

### Fase 2 — P1: readiness dan state terminal LinuxService

1. Tangani timeout readiness eksplisit: jangan `waitFor()` tanpa batas ketika server belum ready; hentikan process tree secara terkoordinasi, state/log terminal atau retry terbatas, lalu beri diagnostic.
2. Masukkan preparation/network/script setup ke jalur error-state yang sama.
3. Pastikan stop/restart tidak membuat cleanup/start yang saling tumpang tindih; buat tes seam yang dapat diuji host/JVM bila memungkinkan.
4. Pertahankan loopback-only, restart budget, status UI, drain log, keep process ownership pada service.
5. Gate setelah setiap perubahan runtime.

### Fase 3 — P1: network boundary dan resource bounds

1. Validasi setiap redirect (HTTPS saja; destination host harus dalam allowlist yang memang diperlukan), atau tangani redirect manual agar host tujuan dapat diperiksa sebelum request berikutnya.
2. Beri batas eksplisit pada body metadata; gunakan ukuran `Packages` sebagai expected size bila valid dan cocokkan hasil download.
3. Tambahkan batas unduhan/impor manual berdasarkan tipe artifact yang didukung dan kebutuhan aktual; jangan memilih angka yang memutus rootfs valid tanpa evidence.
4. Uji redirect ke host terlarang, redirect downgrade HTTP, body metadata berlebih, download terpotong, checksum mismatch, resume 200/206/416.
5. Gate lint → compile → APK → regresi.

### Fase 4 — P1: accounting archive dan recovery

1. Hitung seluruh byte yang benar-benar ditulis/duplikasi hardlink terhadap batas total ekspansi.
2. Tambahkan tes hardlink chain/duplikasi dan batas tepat di atas limit.
3. Uji finalisasi/restore runtime pada exception; jangan hapus `linux-previous` sebelum staging berhasil dan recovery terverifikasi.
4. Gate lint → compile → APK → regresi.

### Fase 5 — P2: dokumentasi, CI, backlog architecture

1. Perbarui `docs/architecture-analysis.md` dengan kondisi dan hasil terverifikasi setelah patch.
2. Tambah atau sinkronkan regression tests ke CI; pertahankan artifact debug; pertimbangkan release build hanya bila signing policy mendukung.
3. Catat popup/WebView origin allowlist dan OAuth compatibility sebagai backlog atau implementasikan terpisah dengan tes OAuth/local navigation.
4. Catat `RuntimeSupervisor`, `IDEServer`/`CodeServerAdapter`, target API modernization, storage/Play policy, dan extraction `MainActivity` sebagai langkah lanjutan kecuali audit lanjutan menunjukkan blocking bug. Jangan membuat adapter masa depan tanpa requirement fungsional.
5. Sinkronkan `docs/verification-baseline.md` dan `docs/architecture-audit.md`; jangan menandai golden path/device tests sebagai verified tanpa perangkat nyata.

## Acceptance criteria per patch

- Tidak ada regresi kontrak: auto-install setelah flow izin, 3-jari menu, 4-jari keyboard, volume modifier, import manual, loopback server, restart/log service, WebView dan OAuth.
- P0 arsip tidak dapat menulis di luar root ekstraksi pada fixture adversarial; symlink guest valid tetap berfungsi.
- Timeout server menghasilkan state/cleanup bounded; tidak ada wait tanpa batas setelah readiness timeout.
- Redirect keluar allowlist/downgrade ditolak.
- Resource input dan expanded size dibatasi serta diuji.
- Setiap checkpoint mencatat command + status + output/artifact path; failure dicatat, bukan ditutup-tutupi.

## Risiko/keterbatasan yang diketahui

- Blocker awal Android SDK/JDK telah diselesaikan setelah persetujuan pengguna; toolchain API 34/JDK 21 tersedia di lingkungan ini. Peringatan parser SDK XML v4 masih muncul, tetapi seluruh gate final lulus.
- Tidak ada emulator/adb yang terdeteksi; golden path Android, WebView, keyboard, foreground-service dan install-device tests tetap tidak dapat diverifikasi di sandbox sampai tersedia perangkat/emulator.
- Semantik symlink absolut guest kini dipetakan ke root ekstraksi dan diuji dengan fixture host-JVM; perilaku pada rootfs Debian asli tetap perlu golden-path test perangkat.

## Progress log — 7 Oktober 2026

- **Fase 0 READ/ANALYZE/PLAN:** selesai sebelum perubahan source; Android SDK/JDK kemudian dipasang setelah izin pengguna. Source ZIP utama tetap menjadi baseline.
- **Fase 1 P0 archive containment:** implementasi selesai; regresi red/green untuk symlink parent write escape, hardlink source escape, dan symlink guest absolut/relatif; accounting hardlink terhadap budget 3 GiB juga ditambahkan. Semua tes terkait lulus.
- **Fase 2 readiness/setup failure:** setup runtime dipindahkan ke jalur exception handling; timeout readiness kini menghentikan process tree dan tidak menunggu tanpa batas; state gate diuji melalui clock/sleep injeksi. Serialisasi penuh stop/restart belum dilakukan karena perlu reproduksi lifecycle/perangkat.
- **Fase 3 network/resource:** redirect manual dibatasi 5 hop dan setiap tujuan divalidasi HTTPS+allowlist+port; body teks 32 MiB; artifact download/import 4 GiB; indeks apt Size menjadi expected size; preflight memakai `StorageManager#getAllocatableBytes`. Transfer HTTP integrasi (resume 200/206/416, checksum mismatch, unknown-length/free-space failure) masih perlu tes lanjutan.
- **Fase 4 archive/recovery:** byte salinan hardlink ikut dihitung; `.deb` data archive diproses melalui temp file streaming; fixture ekstraksi lulus. Installer finalization rollback belum memiliki failure-injection test baru.
- **Fase 5 documentation/CI:** audit, plan, architecture analysis, dan verification record diperbarui; `.github/workflows/build.yml` kini meminta lint, compile, APK, dan `testDebugUnitTest`. Workflow YAML ditinjau lokal tetapi belum dieksekusi pada GitHub Actions. Popup origin/OAuth, `MainActivity` decomposition, state corruption diagnostics, target API modernization, dan release build/signing tetap backlog.

**Hasil gate akhir:** clean PASS; lint PASS (60 warnings); Kotlin+Java compile PASS; debug APK PASS dan signature v2 PASS; 16 unit regression tests PASS, 0 failure/error. APK SHA-256 dan detail gate dicatat di `docs/verification-baseline.md`. Install device/golden path tidak diverifikasi karena tidak ada emulator/ADB.


## Fase 6 — GUI Python/Kivy MVP (7 Oktober 2026)

- **READ/ANALYZE/PLAN:** dokumen arsitektur, roadmap, source dan lampiran kebutuhan dibaca; setelah analisis, pengguna memilih Python Debian + Xvfb + VNC lokal. Rencana GUI ditulis sebelum perubahan source.
- **IMPLEMENT:** ditambahkan konfigurasi viewer/port/password/screen size, guest runner dan demo Kivy, proot command terpisah, lifecycle `GuiState`/start-stop, konfirmasi dependency, navigasi noVNC dan dokumentasi. Server code-server default tidak diubah dan GUI tidak auto-start.
- **TEST/VERIFY:** `clean`, `lint`, Kotlin/Java compile, APK, regression, shell syntax, Python compile, metadata/signature dan isi asset APK lulus. 21 tes lulus; lint 60 warning, sama dengan baseline pra-GUI. Bukti final ada di verification record.
- **RECORD:** README, plan GUI, audit arsitektur dan verification record diperbarui. Build APK bukan bukti runtime perangkat: no emulator/ADB tersedia, sehingga apt install guest, rendering GL/Kivy, sentuh/keyboard noVNC, dan service lifecycle on-device masih menunggu golden-path test.


## Follow-up: kegagalan instalasi pada entri arsip root `.` — 7 Oktober 2026

Screenshot memperlihatkan `path arsip di luar direktori tujuan: .` saat mengekstrak proot/rootfs. `safeTarget` memeriksa parent dari target yang sudah dinormalisasi menjadi root; parent itu satu tingkat di luar direktori ekstraksi. Test-first fixture berisi entri direktori `.` dan `./` gagal sebelum patch. Perbaikan memberi pengecualian hanya bila target sama persis dengan root setelah pemeriksaan lexical traversal; semua path lain tetap memakai containment symlink/canonical yang ada.

Tes merah menjadi hijau, kemudian clean → lint → compile → APK → seluruh regression lulus. Suite akhir: 22 tes, 0 failure/error; 60 warning lint setara baseline. APK update 1.1.1/code 9 ditandatangani v2; detail checksum ada pada verification record bagian 6. Belum ada emulator/ADB, jadi hasil pemasangan pada perangkat belum diverifikasi.


## Follow-up 2 — katalog Debian arm64 gagal dibaca (7 Oktober 2026)

Screenshot 1.1.1 menunjukkan resolver katalog rootfs mengembalikan daftar kosong, bukan kegagalan parser tar yang sudah diperbaiki pada iterasi sebelumnya. Audit kode menemukan exception request disembunyikan dan UI belum beralih ke fase Debian. Patch kecil 1.1.2 memindahkan parser/resolver ke `RootfsCatalog`, mempertahankan pesan sebab request, membedakan kegagalan format/checksum, mengirim progress phase 1 sebelum fetch dan menambah lima fixture regression.

Verifikasi: clean, lint, compile, APK dan 27 unit tests lulus; lint set sama dengan baseline; APK 1.1.2/code 10 dan signature v2 valid. Probe dari sandbox membaca index/checksum upstream dan rootfs arm64; checksum serta integritas XZ lulus. **Handset tidak tersedia:** koneksi Android ke host upstream dan install golden path masih NOT VERIFIED. Rootfs resmi arm64 terverifikasi disediakan sebagai jalur Impor manual bila koneksi index di handset gagal.


## Follow-up 3 — checksum dialihkan ke mirror resmi (7 Oktober 2026)

Pesan lengkap 1.1.2 mengidentifikasi sebab: directory listing terbaca, tetapi `SHA256SUMS` dialihkan ke `sgp1mirror01.do.images.linuxcontainers.org`, subdomain regional resmi yang tidak tercantum dalam allowlist exact-host. Patch menerima subdomain tepat di bawah `images.linuxcontainers.org`, tanpa membuka HTTP, userinfo atau port nonstandar. Tes mirror merah sebelum patch dan hijau sesudah patch; tes host lookalike tetap ditolak.

Checkpoint full 1.1.3: clean/lint/compile/APK/regression lulus; 29 tes, 0 failure; lint tetap sama dengan baseline; signature APK v2 valid. Belum ada perangkat untuk menjalankan update/golden path fisik. Perlu instal APK 1.1.3 dan konfirmasi bahwa rootfs terunduh/terekstrak pada handset.


## Follow-up 4 — satu aksi Kivy/VNC dan tutup untuk menghentikan sesi (7 Oktober 2026)

Dua opsi start/stop pada menu digabung menjadi toggle tunggal. Tap saat idle memulai dependency check (persetujuan unduhan hanya pada penggunaan pertama), Xvfb, x11vnc, noVNC dan Python/Kivy dalam satu alur. Tap lagi menutup sesi. Android Back dari viewer juga meminta stop, termasuk ketika readiness belum selesai; code-server utama tetap berjalan.

Full checkpoint 1.1.4/code 12: clean/lint/compile/APK/regression lulus; 29 tes, 0 failure; lint set identik baseline dan signature v2 terverifikasi. UI lifecycle fisik masih perlu diuji pengguna.


## Fase 7 — Retirement GUI dan baseline kompatibilitas inti (7 Oktober 2026)

Sesuai keputusan pengguna, GUI Kivy/VNC sementara dikeluarkan sepenuhnya dari source APK: menu/viewer/consent, `GuiState` dan intent, GUI process runner, konfigurasi dan terminal bridge, Python shims, interpreter auto-setting, serta asset dan tes GUI dihapus. `LinuxRuntime` kembali ke entrypoint code-server tunggal; fitur Debian/proot/code-server dan patch keamanan/mirror tetap dipertahankan. README kini menjelaskan bahwa Python biasa tidak dicegat; Python mungkin perlu dipasang manual pada rootfs minimal. Paket yang telah ada di rootfs tidak di-purge otomatis demi menjaga environment pengguna.

Pemindaian source/test aktif: **PASS**, tidak ada residu marker GUI/VNC, Xvfb, noVNC, websockify, bridge atau shim. Gate berurutan `clean → lintDebug → compileDebugKotlin → assembleDebug → testDebugUnitTest`: **PASS**; lint 60 Warning, unit suite 24 tes tanpa failure/error/skipped. APK 1.1.5/code 13, signature v2 valid, 6,098,573 byte; SHA-256 `4205173317fc3443d16b20c664a0ac22eeefe11351135ad18070e1620f2ee426`. Metadata dan detail ada di `docs/verification-baseline.md`. Source core `Archive`, `Net`, `RootfsCatalog`, `Installer` serta test Archive/Net identik byte-for-byte dengan 1.1.4. Uji pada handset tetap belum diverifikasi karena tidak ada emulator/ADB.
