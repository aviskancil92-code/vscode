# Target API Modern — Status dan Rencana Aman

## Status saat ini

- `compileSdk = 34` karena SDK yang tersedia pada workspace ini adalah Android 34.
- `targetSdk = 28` dipertahankan dengan sengaja.
- Runtime mengeksekusi `proot`, loader, dan userland Debian dari `filesDir`.
- Menaikkan `targetSdk` tanpa memindahkan strategi eksekusi dapat membuat `exec()` binary dari app storage gagal pada Android modern.

## Mengapa belum dinaikkan otomatis

Target SDK bukan sekadar angka build. Pada project ini, perubahan tersebut menyentuh kontrak eksekusi binary, storage, foreground service, dan akses `/sdcard`. Memaksa `targetSdk 35/36` sekarang akan menghasilkan APK yang mungkin ter-build tetapi runtime Debian/proot dapat gagal saat startup—lebih buruk daripada build yang secara eksplisit mempertahankan baseline stabil.

## Tahap migrasi yang diperlukan

1. Pisahkan binary native Android (`proot`, loader, library) dari guest Debian.
2. Evaluasi lokasi eksekusi yang diizinkan pada target API modern dan uji ABI arm64/armv7/x86_64.
3. Migrasikan akses file user dari legacy storage/`MANAGE_EXTERNAL_STORAGE` ke Storage Access Framework dan persistable URI permissions.
4. Audit ulang `FOREGROUND_SERVICE_DATA_SYNC`, notification permission, dan service start restrictions.
5. Tambahkan build profile modern dengan `compileSdk 36` dan device/instrumentation test, bukan hanya compile test.
6. Hanya setelah runtime pass pada perangkat nyata, ubah default `targetSdk`.

## Guardrail

Jangan menjalankan build modern sebagai rilis hanya karena Gradle berhasil. APK modern harus lulus minimal:

- proot start dan tetap hidup;
- code-server listen di `127.0.0.1:8080`;
- terminal Debian dapat menjalankan `dpkg --configure -a`;
- download/import workspace melalui SAF;
- service recovery setelah screen lock/reboot;
- arm64, armv7, dan x86_64 bila ABI masih didukung.
