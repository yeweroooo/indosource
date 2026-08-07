# indosource

Plugin sumber manga Indonesia untuk aplikasi baca manga **Usagi** (dan aplikasi berbasis Kotatsu).

Satu file `indosource.jar` berisi **10 sumber**:

| Sumber | Domain utama | Teknik |
|---|---|---|
| MG Komik | web1.mgkomik.cc | HTML |
| Shinigami | 11.shinigami.asia | JSON API |
| Kiryuu | v7.kiryuu.to | HTML + admin-ajax |
| APKOMIK | 01.apkomik.com | HTML (themesia mangastream) |
| Komikcast | be.komikcast.cc | JSON API |
| IKIRU | 07.ikiru.wtf | HTML + JSON-LD |
| Cosmic Toons | 02.cosmicscans.to | JSON API |
| Komiknesia | v1.komiknesiaku.com | JSON API |
| Westmanga | v1.westmanga.my | JSON API (signed) |
| Ainz Scans | v3.ainzscans01.com | JSON API |

Format plugin: `org.koitharu.kotatsu.parsers` (classic Kotatsu), di-dex dengan d8 (min API 24), berjalan di sandbox plugin aplikasi.

---

## Cara pasang

### Cara A — Import langsung dari GitHub (paling mudah)

1. Buka Usagi → **Settings** → **Sources** → **Manage plugins**
2. Pilih **Add from GitHub** (ikon + / tambah dari GitHub)
3. Masukkan:

   ```
   yeweroooo/indosource
   ```

   atau URL lengkap: `https://github.com/yeweroooo/indosource`
4. Aplikasi otomatis memeriksa rilis terbaru → pilih **indosource.jar** → **Install**
5. Kembali ke daftar **Sources**, aktifkan sumber yang kamu mau

Update berikutnya juga lewat repo ini: gunakan tombol update / auto-update di Manage plugins — aplikasi membandingkan tag rilis dan mengganti jar otomatis.

### Cara B — Manual dari file

1. Download `indosource.jar` dari halaman **Release** repo ini
2. Taruh di penyimpanan HP (misal `/sdcard/Download/`)
3. Usagi → **Settings** → **Sources** → **Manage plugins** → **Import from storage** → pilih `indosource.jar`

---

## Cara pakai

- Buka tab **Sources**, aktifkan salah satu atau semua sumber
- Cari judul / browse daftar manga, filter genre, status, tipe, urutkan (terbaru, populer, A-Z) — semua didukung
- Buka detail manga → daftar chapter sudah **urutan normal: chapter awal (bab 1) sampai chapter terakhir**, jadi:
  - baca mode **webtoon / scroll ke bawah** = maju ke chapter berikutnya (tidak mundur)
  - daftar chapter di layar detail juga tampil dari chapter awal
- Buka chapter → pilih mode baca (webtoon / paged / vertical) → baca

Catatan: beberapa situs butuh koneksi internet normal dari Indonesia (beberapa domain pernah diblokir Kominfo di jaringan tertentu; pakai domain mirror yang tertera di aplikasi kalau perlu).

---

## Aman? Ada virus?

**Tidak ada.** Ini plugin parser/scraper murni, dan berikut alasan konkretnya:

1. **Tidak ada akses ke data pribadi.** Plugin hanya membaca halaman web / API publik situs manga. Tidak ada permission Android tambahan selain internet, tidak ada telemetri, tidak ada tracker, tidak ada akses file/telepon/kontak.
2. **Kode sumber terbuka.** Seluruh kode parser ada di folder [`src/`](src/) — siapa pun bisa mengauditnya.
3. **Rilis transparan.** File `indosource.jar` di setiap Release dibuat dari kode di `src/` (Kotlin → fat jar → dex dengan d8). Tidak ada binary tersembunyi.
4. **Jaringan hanya ke situs sumber.** Semua request hanya ke domain yang tercantum di tabel di atas beserta CDN gambarnya (sv1.imgkc1.my.id, cdn.uqni.net, cdnap.site, data.cdnesia.my.id, storage.westmanga.blog, api.ainzscans01.com, yuucdn.com, dll). Tidak ada domain lain.
5. **Berjalan di sandbox plugin.** Jar dimuat lewat PluginClassLoader aplikasi sebagai plugin, bukan aplikasi mandiri; aplikasi yang mengatur batasannya.

Tetap jaga keamananmu: **hanya install dari repo ini** (release resmi), jangan install jar yang dibagikan orang lain di sembarang tempat.

---

## Build dari kode sumber

Prasyarat: JDK 11+, Android SDK build-tools (d8), framework `kotatsu-core` (kotatsu-parsers 1.0.5).

```
# 1) compile + fat jar (Kotlin, jsoup, okhttp, okio, coroutines, org.json)
gradle buildJar

# 2) dex untuk Android (min API 24)
d8 --release --lib $ANDROID_HOME/platforms/android-35/android.jar --min-api 24 plugin.jar --output indosource.jar
```

Hasilnya `indosource.jar` (~2.0 MB) — file yang sama dengan yang ada di Release.

---

## Disclaimer

- Repo ini tidak resmi dan tidak berafiliasi dengan situs manga mana pun. Konten adalah hak milik penerbit masing-masing.
- Situs dapat berubah struktur, memblokir, atau mati; perbaikan dirilis melalui update di repo ini.
- Untuk penggunaan pribadi. Hapus jika diminta pemilik hak cipta.

## Lisensi

[MIT](LICENSE)
