# Baseline Verifikasi CodeX Studio

Tanggal: 7 Oktober 2026  
Source SHA-256: `90de1df790a5564416398835cd9bc5c7e07ea975170518f12f797ba060cd0379`  
Status: **Checkpoint terbaru 1.1.3 ada di bagian 8; build/tests lulus, sementara uji install dan jaringan di handset belum tersedia.**

## Persiapan toolchain

- Android Command-Line Tools Linux versi `15859902` diunduh dari domain resmi Google `dl.google.com`; SHA-256 terverifikasi: `4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583`.
- Izin eksplisit pengguna diperoleh sebelum menerima Android SDK License Agreement.
- `platforms;android-34`, `build-tools;34.0.0`, dan `platform-tools` dipasang di `/home/ubuntu/Android/Sdk`.
- JDK 21 headless dipasang karena image awal hanya memiliki JRE dan build AGP membutuhkan `jlink`.
- Warning SDK tooling muncul: command-line tools memberi pesan bahwa versi SDK XML 4 lebih baru daripada format yang diketahui parser versi ini. Meski demikian task build berjalan.

## Baseline commands dan bukti

| Checkpoint | Perintah / bukti | Status | Catatan |
|---|---|---|---|
| Arsip/source | ZIP CRC check + ekstraksi aman | PASS | Source ZIP SHA-256 di atas; source diekstrak ke `/home/ubuntu/codex-studio` |
| Wrapper | `bash ./gradlew --version` | PASS | Gradle 8.9 |
| Clean | `:app:clean` | PASS | Dijalankan terpisah dengan `--no-daemon --console=plain` |
| Lint | `:app:lintDebug` | PASS dengan warning | Report `app/build/reports/lint-results-debug.html`, tajuk: **60 warnings**, tidak menunjukkan lint error. Grup utama: 31 unused resources, 5 GradleDependency, 4 ObsoleteSdkInt, 3 SdCardPath, 1 SetJavaScriptEnabled, 2 accessibility, 5 SetTextI18n, 2 HardcodedText, dan sisanya ikon/overdraw/view constructor. |
| Unit-test task | `:app:testDebugUnitTest` | **NO-SOURCE** | Task berhasil, tetapi report menyebut `compileDebugUnitTestKotlin NO-SOURCE` dan `testDebugUnitTest NO-SOURCE`; source tidak mempunyai tes. Ini bukan bukti regresi lulus. |
| Kotlin compile | `:app:compileDebugKotlin` | PASS | Dua warning: deprecated `ConnectivityManager.allNetworks` (`LinuxRuntime.kt:222`) dan parameter `ctx` tidak dipakai (`:257`). |
| Java compile | `:app:compileDebugJavaWithJavac` | PASS | Tidak ada error compile. |
| APK debug | `:app:assembleDebug` | PASS | `app/build/outputs/apk/debug/app-debug.apk`, 6,087,729 bytes |
| APK signature | `apksigner verify --verbose .../app-debug.apk` | PASS | Satu signer; APK Signature Scheme v2 verified. |
| Identitas APK | `aapt dump badging .../app-debug.apk` | PASS | `com.vscode.mobile`, version 1.1.0 (code 8), compile SDK 34, min SDK 26, target SDK 28. |
| Install perangkat / golden path | Tidak tersedia | NOT VERIFIED | Tidak ada emulator atau perangkat `adb` terpasang di sandbox. |

**Riwayat awal:** baseline pertama terblokir karena Android SDK tidak tersedia; pengulangan kedua mencapai Kotlin compile tetapi gagal karena JRE tidak memiliki `jlink`. Setelah SDK dan JDK lengkap tersedia, checkpoint clean/lint/test task/compile/APK di atas berhasil. Hanya hasil pengulangan terakhir yang menjadi baseline final.

**Batas klaim:** build debug baseline berhasil. Source belum memiliki regression tests; P0 parser issue belum dites end-to-end dan runtime/device behavior belum diverifikasi. “Test task sukses” berarti tidak ada sumber test untuk dijalankan, bukan “bug/regresi lulus”.

## Rujukan eksternal toolchain

- Dokumentasi resmi paket SDK: [Android SDK tools](https://developer.android.com/tools).
- Halaman resmi paket/unduhan Android Studio dan command-line tools: [Android Studio](https://developer.android.com/studio).
- Paket Linux yang digunakan: `https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip`; SHA-256 diverifikasi terhadap checksum yang ditampilkan di halaman resmi Android Studio: `4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583`.
- Lisensi Android SDK diterima hanya setelah persetujuan eksplisit pengguna pada sesi ini.

## 4. Checkpoint setelah patch P0/P1 terpilih

Tanggal eksekusi: 7 Oktober 2026. Perintah dijalankan berurutan dengan `--no-daemon --console=plain`: `:app:clean` → `:app:lintDebug` → `:app:compileDebugKotlin :app:compileDebugJavaWithJavac` → `:app:assembleDebug` → `:app:testDebugUnitTest`.

| Gate | Hasil final | Bukti |
|---|---|---|
| Clean | PASS | `:app:clean`, BUILD SUCCESSFUL |
| Lint | PASS, 60 warnings / 0 lint errors | Sama dengan jumlah warning baseline sebelum patch; tidak ada warning lint baru pada file patch setelah kapasitas memakai `StorageManager#getAllocatableBytes` |
| Compile | PASS | Kotlin dan Java compile berhasil; dua warning Kotlin lama tetap ada di `LinuxRuntime.kt` (deprecated `allNetworks`, parameter `ctx` tidak dipakai) |
| APK debug | PASS | `app/build/outputs/apk/debug/app-debug.apk`; 6,097,709 byte; SHA-256 `8a79bf26cef645b8f6ecf728fcf85d8cfa86507bd0dad674f94c11556274611d` |
| Signature dan metadata | PASS | `apksigner`: v2 verified; `aapt`: `com.vscode.mobile`, versionName 1.1.0/code 8, compile SDK 34, min SDK 26, target SDK 28 |
| Regression | PASS | 16 tes: Archive 6, Net 6, ServerReadiness 4; 0 failures, 0 errors |
| Device/golden path | NOT VERIFIED | Tidak ada emulator/perangkat ADB yang tersedia |

Regression yang dieksekusi mencakup write escape lewat parent symlink, hardlink source di luar root, semantik symlink guest absolut/relatif, budget expanded-byte tepat/lebih, ekstraksi `.deb` streaming, allowlist redirect/downgrade/port, batas byte metadata/artifact, serta hasil readiness READY/EXITED/STOP/TIMEOUT. Redirect belum diuji menggunakan server HTTP integrasi; transfer Range 200/206/416, checksum mismatch, installer rollback, dan runtime pada perangkat masih belum tercakup.

Warning toolchain Android tentang SDK XML v4 yang lebih baru dari parser command-line tools tetap muncul, tetapi semua gate di atas selesai sukses. Angka NO-SOURCE pada tabel baseline di atas merujuk kondisi sebelum patch; setelah source tes ditambahkan, regression task benar-benar mengeksekusi 16 tes.

## 5. Checkpoint MVP GUI Python/Kivy — 7 Oktober 2026

Arsitektur terpilih: Python Debian dengan Xvfb dan viewer VNC/noVNC loopback; GUI berjalan hanya atas permintaan pengguna. Setelah perbaikan kecil yang ditemukan saat review, seluruh gate dijalankan ulang berurutan:

`clean` → `:app:lintDebug` → `:app:compileDebugKotlin :app:compileDebugJavaWithJavac` → `:app:assembleDebug` → `:app:testDebugUnitTest`.

| Gate | Hasil | Bukti |
|---|---|---|
| Clean | PASS | `:app:clean`, BUILD SUCCESSFUL |
| Lint | PASS, 60 warnings / 0 errors | Sama dengan baseline source patched pra-GUI (60); perbandingan Lint XML menunjukkan tidak ada warning baru dari GUI |
| Kotlin/Java compile | PASS | Dua warning Kotlin yang sudah ada di `LinuxRuntime.kt` tetap: deprecated `allNetworks` dan parameter `ctx` tidak dipakai |
| APK debug | PASS | `app/build/outputs/apk/debug/app-debug.apk`, 6,111,996 byte; SHA-256 `06ff2d2c9df57f3da234891cd3be14174fbb1be7ab4921762aba26d3af6ae672` |
| Metadata/signature | PASS | `com.vscode.mobile`, versionName `1.1.0`, versionCode `8`, compile SDK 34, min SDK 26, target SDK 28; `apksigner` v2 verified |
| Asset Kivy | PASS | APK memuat `assets/kivy/kivy-vnc-session.sh` dan `assets/kivy/kivy-demo.py` |
| Regression | PASS | 21 tes: Archive 6, Net 6, ServerReadiness 4, KivyGuiConfig 5; 0 failure/error/skipped |
| Asset syntax | PASS | `bash -n` runner dan `python3 -m py_compile` demo |
| Device/golden path | NOT VERIFIED | Tidak ada emulator/perangkat ADB; apt dalam rootfs, render/input WebView dan Kivy harus diuji pada perangkat |

Uji murni untuk fitur baru mencakup pembentukan URL viewer loopback tanpa query password, alfabet password, penolakan host/port lain, isolasi port dari code-server, serta pemetaan dan validasi/clamp ukuran Xvfb. Validasi batas Bash menguji ukuran valid dan out-of-range. Ini tidak menggantikan pengujian integrasi Android/perangkat. Peringatan SDK tentang parser XML v4 tetap muncul selama Gradle; semua gate tetap sukses.


## 6. Perbaikan instalasi rootfs dengan entri tar `.` — 7 Oktober 2026

- **Reproduksi merah:** `ArchiveTest.acceptsRootDirectoryEntriesDotAndDotSlash` gagal sebelum patch dengan `java.io.IOException` saat entri directory tar `.` diproses; kondisi sesuai screenshot pengguna.
- **Patch:** `Archive.safeTarget` menerima target yang tepat sama dengan root setelah pemeriksaan lexical containment, dan hanya melewati pemeriksaan parent untuk kasus root tersebut. Target lain tetap memakai containment canonical/symlink/hardlink yang sama.
- **Hijau:** regression yang sama lulus sesudah patch; seluruh `ArchiveTest` menjadi 7 tes. Full suite: 22 tes (Archive 7, Net 6, ServerReadiness 4, KivyGuiConfig 5), 0 failure/error/skipped.
- **Gate final:** clean, lint, Kotlin/Java compile, assembleDebug dan regression PASS. Lint 60 warnings, identik dengan baseline pra-GUI (0 warning baru). Peringatan SDK XML v4 dan dua warning Kotlin lama tetap ada.
- **APK update:** `com.vscode.mobile` versionName `1.1.1`, versionCode `9`; 6,112,012 byte; SHA-256 `106829b3d82912458fe346806f6b31082dcd4d4632407095d425bd98255e47de`; signature v2 verified.
- **Batas:** test membuktikan parser pada fixture host-JVM, bukan pemasangan penuh di ponsel. Tidak ada emulator/perangkat ADB untuk golden-path retest; pengguna perlu memasang APK update dan mencoba ulang.


## 7. Diagnosis katalog rootfs Debian — APK 1.1.2

| Gate | Hasil | Bukti |
|---|---|---|
| Clean | PASS | `:app:clean` |
| Lint | PASS | 60 issue/warning; set ID/file/pesan identik dengan baseline patched |
| Kotlin/Java compile | PASS | `:app:compileDebugKotlin :app:compileDebugJavaWithJavac` |
| APK | PASS | `:app:assembleDebug`; `com.vscode.mobile`, versionName `1.1.2`, versionCode `10`, 6,112,864 byte; SHA-256 `dd41136c621e99038e44346a525954ce7d5d8ad56f96516ecfa15c77397b83c8` |
| Signature | PASS | `apksigner verify`; APK Signature Scheme v2 true |
| Regression | PASS | 27 tes: Archive 7, Net 6, ServerReadiness 4, KivyGuiConfig 5, RootfsCatalog 5; 0 failure/error/skipped |
| Device/network golden path | NOT VERIFIED | Tidak ada emulator/ADB; kegagalan jaringan yang terjadi di handset belum dapat direproduksi langsung |

**Probe upstream:** dari sandbox, `https://images.linuxcontainers.org/images/debian/bookworm/arm64/default/` merespons HTTP 200 dan memberi tiga link build; Java `HttpURLConnection` menggunakan User-Agent aplikasi membaca listing dan mencocokkan tautan tanggal. Manifest build `20261007_05%3A24/SHA256SUMS` menyatakan rootfs SHA-256 `be17e8c6fe2d9173a3fd82ed5d5cd814248a27d8e5cad54ab946a546c74cb590`. Unduhan rootfs 94,952,764 byte lulus perbandingan checksum dan `xz -t`; daftar tar memuat `./usr/bin/bash`. Probe tersebut **tidak** membuktikan konektivitas pada Android pengguna.

APK 1.1.2 kini menampilkan error jaringan/HTTP saat index gagal, membedakan respons tanpa link tanggal dari masalah checksum, dan memperbarui indikator ke fase Debian sebelum network lookup. Belum ada klaim bahwa pemasangan otomatis pada handset sudah berhasil. Sebagai bypass, berkas rootfs terverifikasi tersedia terpisah pada deliverable; server image Linux Containers menyatakan build lama hanya disimpan untuk jendela tiga hari.


## 8. Koreksi redirect checksum ke mirror resmi — APK 1.1.3

Pesan runtime yang dikirim pengguna menunjukkan target redirect sebenarnya: `https://sgp1mirror01.do.images.linuxcontainers.org/.../SHA256SUMS`. `Net.validateAllowedUrl()` sebelumnya menolak host tersebut karena allowlist hanya memuat host utama. Ini adalah sebab langsung kegagalan instalasi Debian, bukan kegagalan membaca daftar build. Pemeriksaan HTTP dari sandbox mengikuti redirect upstream yang sama, berakhir di hostname tersebut dengan HTTP 200 dan membaca checksum `be17e8c6fe2d9173a3fd82ed5d5cd814248a27d8e5cad54ab946a546c74cb590`.

Tes `allowsRedirectToOfficialLinuxContainersMirrorSubdomain` **merah sebelum patch** (`IOException`, sumber tidak diizinkan), lalu seluruh `NetTest` **hijau** setelah host policy menerima suffix subdomain resmi. `rejectsLookalikeLinuxContainersHostAndNonstandardMirrorPort` memastikan domain mirip/port 444 tetap ditolak. Scheme tetap HTTPS dan userinfo tetap dilarang.

| Gate | Hasil |
|---|---|
| Clean | PASS |
| Lint | PASS; 60 issue, set identik baseline |
| Kotlin/Java compile | PASS |
| APK | PASS; `com.vscode.mobile`, versionName `1.1.3`, code `11`, 6,112,936 byte |
| Signature | PASS; APK Signature Scheme v2 |
| Regression | PASS; 29 tes (Archive 7, Net 8, ServerReadiness 4, KivyGuiConfig 5, RootfsCatalog 5), 0 failure/error/skipped |
| APK SHA-256 | `45130f952be1359dd54464450d19e5bbd9ae56365f3d8bc99d59da86717606d` |
| Install handset | NOT VERIFIED; tidak ada emulator/ADB pada sandbox |

Kode sekarang mengizinkan mirror HTTPS di bawah `images.linuxcontainers.org`, termasuk hostname yang terlihat pada error pengguna. Hasil host-side memverifikasi URL policy dan server redirect, tetapi pemasangan otomatis pada perangkat tetap harus dicoba dengan APK 1.1.3.


## 9. UX toggle GUI dan close-to-stop — APK 1.1.4

Satu aksi menu kini menjalankan seluruh sesi Python/Kivy/VNC secara otomatis; ketika aktif, aksi yang sama menjadi **Tutup GUI Kivy**. Android Back dari viewer atau ketika GUI masih Starting menghentikan sesi GUI dan kembali ke code-server; code-server tetap berjalan. Script guest memiliki cleanup trap bagi Xvfb/x11vnc/websockify.

| Gate | Hasil |
|---|---|
| Clean | PASS |
| Lint | PASS; 60 issue, set ID/file/pesan identik dengan baseline |
| Kotlin/Java compile | PASS |
| APK | PASS; versionName `1.1.4`, versionCode `12` |
| Regression | PASS; 29 tes, 0 failure/error/skipped |
| Uji manual pada handset | NOT VERIFIED; tidak tersedia device/emulator dalam sandbox |

APK dibuat dari full sequence `clean → lintDebug → compileDebugKotlin/Java → assembleDebug → testDebugUnitTest`. Penutupan proses saat runtime nyata dan perilaku touch noVNC tetap perlu dicoba pada handset.


## 10. Retirement GUI dan baseline kompatibilitas inti — APK 1.1.5

Sesuai keputusan pengguna, source APK 1.1.5 menghapus UI/viewer/consent, `GuiState` dan service actions, GUI process runner/probe, Kivy config, terminal bridge, Python shim/interpreter override, serta asset dan test khusus GUI. `LinuxRuntime` hanya menyediakan entrypoint code-server. README dan catatan historis disinkronkan. Paket Debian yang mungkin telah terpasang pada rootfs lama tidak di-purge otomatis. Source scan serta pemeriksaan asset/DEX APK tidak menemukan marker Kivy/VNC/noVNC/Xvfb/websockify/bridge. File `Archive.kt`, `Net.kt`, `RootfsCatalog.kt`, `Installer.kt`, serta test Archive/Net identik byte-for-byte dengan source 1.1.4.

| Gate | Hasil |
|---|---|
| Clean | PASS |
| `lintDebug` | PASS; 60 issue, semuanya Warning |
| `compileDebugKotlin` | PASS |
| `assembleDebug` | PASS |
| `testDebugUnitTest` | PASS; 24 tes, 0 failure/error/skipped (Archive 7, Net 8, RootfsCatalog 5, ServerReadiness 4) |
| APK | PASS; `com.vscode.mobile`, versionName `1.1.5`, versionCode `13`, 6,098,573 byte |
| Signature | PASS; APK Signature Scheme v2 |
| SHA-256 | `4205173317fc3443d16b20c664a0ac22eeefe11351135ad18070e1620f2ee426` |
| APK GUI asset/DEX scan | PASS; tidak ditemukan marker/runtime GUI |
| Uji pada perangkat | NOT VERIFIED; tidak tersedia emulator/ADB pada sandbox |

SDK yang digunakan ada di `/home/ubuntu/Android/Sdk`; gate dijalankan berurutan dengan `ANDROID_HOME` dan `ANDROID_SDK_ROOT` menunjuk ke lokasi tersebut. Hasil build host tidak membuktikan instalasi atau perilaku runtime pada handset.
