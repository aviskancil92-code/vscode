# Rencana Retirement GUI Kivy — 2026-10-07

## Keputusan
Sesuai pilihan pengguna, seluruh fitur GUI Kivy sementara dihapus dari aplikasi. Fokus berikutnya adalah kompatibilitas runtime; APK saat ini tidak boleh memuat alur Kivy/VNC/noVNC.

## Cakupan penghapusan
- Hapus item menu, viewer/navigation WebView, dialog consent, state, intent, dan process runner GUI.
- Hapus asset runner/demo dan konfigurasi/password viewer Kivy.
- Hapus bind bridge, PATH shim, interpreter setting otomatis, dan launcher terminal Kivy yang belum dirilis.
- Hapus test khusus GUI/bridge dan bagian README yang menginstruksikan fitur yang tidak lagi ada.
- Naikkan versi patch/code agar APK hasil build dapat menjadi update.

## Yang dipertahankan
- code-server, service Debian/proot, terminal biasa, alur instalasi yang sudah ada, serta server readiness/security regression tests.
- Catatan audit/verification lama sebagai sejarah; catatan itu diberi status retired supaya tidak disalahartikan sebagai perilaku APK sekarang.

## Gate dan acceptance
1. Source scan memastikan tidak ada referensi executable/runtime Kivy, VNC, noVNC, Xvfb, websockify, atau terminal bridge pada source aktif.
2. `clean` → lint → Kotlin compile → debug APK → unit regression.
3. Verifikasi metadata/signature/ukuran APK dan isi resource; pastikan tidak ada asset GUI.
4. Review diff terhadap ruang lingkup; jangan klaim Kivy kompatibel atau tersedia di build ini.

## Batas
Ini bukan implementasi kompatibilitas Kivy. Python tidak lagi dicegat/dijalankan otomatis; environment Debian dapat dipakai untuk pengujian kompatibilitas selanjutnya tanpa viewer GUI. Uji perangkat tidak tersedia di sandbox.

## Implementasi per 2026-10-07

- Menu/viewer/consent, state dan action GUI, runner service, konfigurasi Kivy, terminal bridge, Python shim dan interpreter auto-setting dihapus.
- `LinuxRuntime` kembali ke satu proot entrypoint `/root/.vscmob/start.sh`; bind bridge dan asset Kivy/VNC tidak ada di source.
- README diperbarui, sedangkan dokumen MVP sebelumnya diberi label retired sebagai catatan historis.
- Source scan `app/src/main` dan `app/src/test` lulus tanpa marker GUI/VNC/Xvfb/noVNC/websockify/bridge/shim.
- Versi aplikasi dinaikkan ke 1.1.5 / code 13. Paket yang mungkin sudah ada di rootfs pengguna tidak dihapus otomatis.

## Verifikasi akhir

Gate dijalankan berurutan dengan SDK `/home/ubuntu/Android/Sdk`: `clean` PASS → `lintDebug` PASS (60 Warning) → `compileDebugKotlin` PASS → `assembleDebug` PASS → `testDebugUnitTest` PASS (24 tes; 0 failure/error/skipped). APK `1.1.5` / code 13 berukuran 6,098,573 byte; signature v2 valid; SHA-256 `4205173317fc3443d16b20c664a0ac22eeefe11351135ad18070e1620f2ee426`. Pemindaian source aktif, asset APK, dan DEX bersih dari marker GUI/VNC. File core keamanan/installer yang relevan dibandingkan dengan source 1.1.4 dan tidak berubah. Uji pada perangkat fisik/emulator tetap belum diverifikasi.
