# Rencana MVP GUI Python/Kivy — Xvfb + noVNC lokal

> **RETIRED pada 2026-10-07:** dokumen ini adalah catatan historis untuk MVP yang kemudian dibatalkan. Fitur, runner, dan asset GUI tidak disertakan pada build 1.1.5. Lihat [rencana retirement](kivy-gui-retirement-plan.md); bagian di bawah tidak menggambarkan perilaku APK saat ini.

**Status:** arah arsitektur dipilih pengguna (opsi 2); rencana dicatat sebelum perubahan source.  
**Baseline:** source CodeX Studio hardened yang sudah diaudit dan dipatch; pertahankan arsitektur Android host + Debian/proot + code-server.  
**Target:** tombol contoh Kivy dapat ditampilkan dan disentuh di dalam WebView CodeX Studio, tanpa desktop environment.

## Keputusan arsitektur

- Jalankan Kivy sebagai proses Python Debian biasa di sesi X11 virtual `Xvfb :99`; gunakan Mesa software GL bila dibutuhkan.
- Akses display lewat `x11vnc` dan frontend noVNC yang disajikan `websockify` pada `127.0.0.1` saja. VNC server sendiri juga dibatasi loopback dan memakai password acak per sesi. Password dikirim ke noVNC melalui URL fragment (bukan request path) dan tidak dicatat ke log.
- Integrasikan viewer pada WebView yang sama. Navigasi Back kembali ke code-server; tindakan menu terpisah menutup sesi GUI. GUI tidak dimulai otomatis bersama code-server.
- Saat pengguna pertama kali memilih GUI, tampilkan penjelasan/persetujuan unduhan dependency Debian tambahan. Instal sesuai kebutuhan melalui apt: `python3-kivy`, `xvfb`, `x11vnc`, `novnc`, `websockify`, dan paket Mesa software yang diperlukan. Tidak memasang XFCE/GNOME.
- Gunakan `/root/app.py` bila sudah ada; bila belum, jalankan demo Kivy `Hello CodeX Studio` yang dikelola CodeX Studio. Jangan menimpa berkas workspace milik pengguna.
- Kelola proses GUI tersendiri dari proses server di `LinuxService`; start/stop GUI tidak boleh memanggil pembersihan luas `killTree()` untuk sesi normal. Stop atau restart Linux penuh tetap membersihkan guest process sesuai kebijakan service yang sudah ada.

## Batas keamanan dan resource

- Bind noVNC/WebSocket ke IPv4 loopback (`127.0.0.1`), bukan `0.0.0.0`; bind x11vnc ke localhost juga. Password acak berbeda untuk tiap sesi.
- Hanya satu sesi GUI pada satu waktu; GUI opt-in agar Xvfb/Kivy tidak mengonsumsi RAM/CPU saat IDE saja yang dipakai.
- Port MVP: VNC 5900, noVNC 6080, display `:99`; konflik port harus menghasilkan error yang dapat dibaca dan dicatat, bukan menampilkan viewer kosong.
- Dependensi diunduh on-demand, bukan ditambahkan ke ukuran APK atau instalasi Linux dasar. Besaran total download/ruang bergantung dependency closure apt pada arsitektur perangkat; akan dicatat sebagai keterbatasan, bukan diperkirakan tanpa pengukuran.

## Implementasi bertahap

1. Tambah konfigurasi GUI murni/testable (port, display, pembentukan URL noVNC tanpa password pada query request) dan file guest demo/startup yang idempoten.
2. Generalisasi builder proot secara backward-compatible agar perintah server lama tetap identik; tambahkan runner guest GUI terpisah dengan output masuk ke log.
3. Tambah `GuiState` + action start/stop, readiness timeout untuk noVNC, dan cleanup sesi; pastikan stop/restart server penuh juga menghentikan GUI.
4. Tambah tindakan menu mulai/berhenti GUI dan alur konfirmasi dependency pertama kali; saat siap, muat viewer di WebView utama.
5. Tambah regression tests untuk URL/security/configuration dan flow readiness yang dapat diuji; cek sintaks shell dan Python demo.
6. Jalankan checkpoint satu per satu: clean → lint → compile → APK → unit regression; validasi manifest/metadata/signature APK dan diff terhadap source baseline.
7. Rekam keputusan, diff, hasil verifikasi, artefak APK/ZIP, serta sisa risiko pada dokumen arsitektur dan verification record.

## Acceptance criteria

- Default code-server start, readiness, restart/log, WebView/OAuth, gestur menu/keyboard, serta import/install lama tetap berfungsi.
- GUI hanya mulai setelah aksi pengguna; service menerbitkan state Starting/Running/Error/Stopped yang dapat diamati.
- Viewer hanya dapat diakses melalui loopback, memakai autentikasi sesi, dan terhubung ke websockify yang berjalan.
- Kivy demo dapat dijalankan tanpa desktop environment; jika `/root/app.py` ada, file tersebut yang dieksekusi.
- Proses GUI dapat dihentikan dan dimulai lagi tanpa membunuh code-server atau meninggalkan proses guest yatim.
- Regression tests dan semua gate build dijalankan serta hasilnya dicatat. Pengujian nyata input/rendering pada perangkat Android tetap tidak boleh diklaim jika emulator/perangkat tidak tersedia.

## Risiko/hal yang perlu diverifikasi

- OpenGL/GLX software rendering dan kinerja Kivy melalui `Xvfb` bergantung pada driver/Mesa di Debian rootfs dan ABI perangkat. Compile APK tidak membuktikan render Android sesungguhnya.
- Input sentuh/keyboard pada noVNC mobile harus diuji di perangkat nyata; noVNC mobile browser support adalah indikasi kompatibilitas, bukan bukti device golden path.
- APT dapat memerlukan unduhan besar serta waktu lama; UI harus menjelaskan hal ini dan readiness timeout harus cukup untuk instalasi awal.
- Jika OpenGL software tidak tersedia pada perangkat target, jalur Xvfb+VNC perlu evaluasi ulang; alternatif direct Android Surface/SDL2 tetap di luar MVP terpilih.

## Status implementasi dan verifikasi — 7 Oktober 2026

**Status: implementasi MVP opt-in selesai pada source; build/regression sandbox lulus; golden path perangkat belum diverifikasi.** Resolusi Xvfb kini mengikuti area WebView Android saat tombol GUI dipilih (fallback display metrics bila ukuran view belum tersedia), dengan setiap dimensi diklem 320–4096 piksel.

| Acceptance | Status |
|---|---|
| Server code-server tetap jalur default dan GUI tidak auto-start | Diimplementasikan; command server lama tetap memakai script yang sama |
| GUI start/stop terpisah dengan state/readiness/cleanup | Diimplementasikan pada `LinuxService`; belum diuji instrumented pada Android |
| Viewer loopback dengan password sesi dan port terisolasi | Policy/config regression lulus; service belum diuji di perangkat |
| Kivy demo atau `/root/app.py` | Runner/demo ada di APK; eksekusi Kivy nyata belum diverifikasi |
| Ukuran display sesuai WebView Android | Unit test config lulus; pengukuran pada perangkat nyata belum diverifikasi |
| Lint, compile, APK, regression | PASS; detail checksum dan hitungan ada di `docs/verification-baseline.md` |

Sisa risiko tetap sesuai bagian Risiko: paket apt membutuhkan internet dan ruang tambahan, Mesa/Xvfb dapat berbeda menurut ABI/perangkat, dan sentuh/keyboard noVNC perlu golden-path test. Tidak ada klaim bahwa APK telah dipasang atau Kivy berhasil dirender pada perangkat.


## Follow-up installer rootfs — 7 Oktober 2026

Regression tambahan mencakup directory entry tar `.` dan `./` yang lazim pada rootfs. False positive containment diperbaiki hanya untuk target persis root ekstraksi; perlindungan path non-root dipertahankan. Test gagal sebelum patch dan lulus sesudahnya. APK update terbaru 1.1.1/code 9; angka dan checksum ada di `docs/verification-baseline.md` bagian 6.
