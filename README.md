# indosource

Plugin sumber manga Indonesia untuk aplikasi baca manga **Usagi** (dan aplikasi berbasis Kotatsu).

Satu file `indosource.jar` berisi **13 sumber**:

| Sumber | Domain utama | Teknik |
|---|---|---|
| MG Komik | web1.mgkomik.cc | HTML |
| Shinigami | 11.shinigami.asia | JSON API |
| Kiryuu | v7.kiryuu.to | HTML + admin-ajax |
| APKOMIK | 01.apkomik.com | HTML (themesia mangastream) |
| Voratoon | api.voratoon.com | JSON API (menggantikan Komikcast) |
| IKIRU | 09.ikiru.wtf | JSON API + HTML |
| Cosmic Toons | 02.cosmicscans.to | JSON API |
| Komiknesia | v1.komiknesiaku.com | JSON API |
| Westmanga | v1.westmanga.my | JSON API (signed) |
| Ainz Scans | v3.ainzscans01.com | JSON API |
| LumosKomik | 03.lumosgg.com | HTML (Astro SSR) |
| MangaDex | api.mangadex.org | JSON API (Any Language / Multi-language) |
| Asura Scans | asurascans.com | JSON API + Astro SSR (English) |

Format plugin: **dual-format** agar kompatibel dengan semua versi app Usagi:
- **tsuki-style** (`tsuki.MangaParserFactoryKt` + `tsuki.model.MangaParserSource`) — app versi tsuki (0.0.32-beta+ / 1.0.0-rc1+)
- **org.koitharu-style** (`org.koitharu.kotatsu.parsers.MangaParserFactoryKt` + `org.koitharu.kotatsu.parsers.model.MangaParserSource`) — app lama (0.0.31 dan sebelumnya), framework `core-parsers` dibundle penuh di dalam plugin

Struktur source:
- `src/` — parser tsuki-style (package `org.koitharu.kotatsu.parsers.site.id`)
- `src-legacy/` — parser org.koitharu-style (package `...site.id.legacy`)
- `plugin/tsuki/` + `plugin/legacy/` — enum `MangaParserSource` + `MangaParserFactoryKt` (13 sumber)

Di-dex dengan d8 (min API 24), berjalan di sandbox plugin aplikasi.

**Asura Scans** (`asurascans.com`, API `api.asurascans.com`) — scanlation manhwa/manhua berbahasa Inggris (flag ENG):
- Browse & **search** (`?search=`) + sorting (update, popularitas, rating, terbaru, alfabet) & `SortOrder.RELEVANCE`
- Filter 35 genre lengkap (Action, Murim, Regression, Reincarnation, System, Necromancer, dll.), status (ongoing, completed, hiatus, dropped), tipe (manhwa, manhua, manga), dan author
- Detail: judul alternatif, sinopsis, cover art HD, rating, dan daftar bab lengkap
- Reader: ekstraksi gambar beresolusi tinggi langsung dari CDN Asura
- Link resolver: mendukung link `asurascans.com/comics/...`, `/manga/...`, dan `/series/...`

**MangaDex** (`api.mangadex.org`, portal `mangadex.org`) — integrasi penuh REST API v5:
- Flag **Any Language** (locale `all` / multi-bahasa): browse, cari, dan baca manga dalam bahasa apa saja (Indonesia, Inggris, Jepang, dsb.) atau filter ke bahasa tertentu
- Browse & **search** (`?title=`) + relevance sorting, terpopuler, terbaru, rating, dan alfabet
- Filter lengkap: 77 tag MangaDex (genre, format, tema, konten), status rilis, format (Manga/Manhwa/Manhua), target pembaca (Shounen/Shoujo/Seinen/Josei), content rating, dan tahun
- Detail: judul alternatif, sinopsis, cover art HD, rating bayesian, dan daftar chapter lengkap dengan pembagian branch bahasa (ID, EN, JA, dll.)
- Reader: integrasi MangaDex@Home API (gambar resolusi penuh + preview data-saver)
- Link resolver: mendukung link `mangadex.org/title/...`, `mangadex.org/manga/...`, dan `mangadex.org/chapter/...`

**LumosKomik** (`03.lumosgg.com`, portal `lumosgg.com`) — situs Astro SSR, semua fitur jalan lewat HTTP murni:
- Browse + **search** (`?q=`) + **filter**: 185+ genre (**multi-genre**, dipisah koma → interseksi server-side), status (ongoing/completed/hiatus), tipe (manga/manhwa/manhua)
- Sort: terbaru, populer, rating, A-Z
- Detail: sinopsis (schema.org JSON-LD), author, rating, genre, status, daftar chapter lengkap
- Reader: halaman dari `lms.imgsvr.my.id`
- Catatan: situs menanam **honeypot trap** (`/comic/honeypot-trap-*` yang mengarah ke `/api/honeypot/trap`, memban IP pengunjung) — parser memfilter slug ini sehingga tidak pernah dikunjungi

**Voratoon** (portal `voratoon.id`, mirror web `v1`/`v2`/`v3`/`v8.voratoon.com`) adalah penerus backend Komikcast dengan API JSON yang sama — API dilayani dari `api.voratoon.com` (sejak 2026-10-14; `www.voratoon.com` mati). Didukung:
- Browse + **search** (`?title=`) dan **filter lengkap**: 48 genre (multi-genre), status (ongoing/completed/hiatus/cancelled), format (manga/manhwa/manhua), tipe **Project/Mirror**, tag **Hot/Recommended/Anime Adaptation**
- Sort: terbaru, update, popularitas, rating, views
- Detail: sinopsis, status, rating, genre, daftar chapter lengkap (urutan normal)
- Reader: halaman dari `cdn.voratoon.com`, cover dari `cvr.voratoon.id` (URL presigned)

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
4. **Jaringan hanya ke situs sumber.** Semua request hanya ke domain yang tercantum di tabel di atas beserta CDN gambarnya (sv1.imgkc1.my.id, cdn.uqni.net, cdnap.site, data.cdnesia.my.id, storage.westmanga.blog, api.ainzscans01.com, yuucdn.com, cdn.voratoon.com, cvr.voratoon.id, lms.imgsvr.my.id, dll). Tidak ada domain lain.
5. **Berjalan di sandbox plugin.** Jar dimuat lewat PluginClassLoader aplikasi sebagai plugin, bukan aplikasi mandiri; aplikasi yang mengatur batasannya.

Tetap jaga keamananmu: **hanya install dari repo ini** (release resmi), jangan install jar yang dibagikan orang lain di sembarang tempat.

---

## Build dari kode sumber

Prasyarat: bash, curl, unzip, zip, JDK 17+ (`java` di PATH). Semua dependency (kotlinc, framework `tsuki` 1.0.5 + `core-parsers` 0.0.3, r8/d8, jsoup, okhttp, dll) diunduh otomatis sekali ke `./deps`.

```
./build.sh
```

Pipeline: `src/*.kt` + `src-legacy/*.kt` + `plugin/*` → dua kali kompilasi kotlinc 2.2.10 (tsuki-style + legacy, jvm-target 17) → fat jar (tsuki + core-parsers + dependency) → dex d8 (min API 24) → `indosource.jar` (zip berisi satu `classes.dex`).

Hasilnya `indosource.jar` (~2.3 MB) — file yang sama dengan yang ada di Release.

---

## Cara membuat plugin Usagi yang valid

Dokumentasi ini hasil reverse-engineering format plugin Usagi (app versi Tsuki, framework `tsuki` 1.0.5). Repo ini adalah contoh yang berjalan penuh.

### 1. Format file

Plugin Usagi bukan APK — dia **jar biasa yang isinya cuma satu file `classes.dex`** di root (tanpa `META-INF`, tanpa manifest, tanpa resources):

```
indosource.jar
└── classes.dex        # hasil d8, min API 24
```

> Jangan pakai `jar cf` langsung ke directory hasil compile — itu menghasilkan `META-INF/MANIFEST.MF` tambahan. Build memakai `zip` murni (lihat `build.sh`). `zip` juga **tidak menghapus entri lama** saat menimpa — selalu `rm` dulu.

### 2. Yang wajib ada di dalam dex (kontrak plugin)

Aplikasi memuat plugin lewat `PluginClassLoader` dan mencari kelas-kelas ini:

| Kelas | Fungsi |
|---|---|
| `tsuki.model.MangaParserSource` | **Enum wajib di-ship oleh plugin** (tidak ada di framework). Setiap konstanta enum = satu sumber yang muncul di daftar Sources. Konstruktor: `(title, locale, contentType, isBroken)` dan harus implement `tsuki.model.MangaSource` |
| `tsuki.MangaParserFactoryKt.newParser(MangaParserSource, MangaLoaderContext): MangaParser` | Factory berisi `when(source)` → instance parser, dibungkus `MangaParserWrapper(parser)`, lalu `check(parser.source == source)` |
| `tsuki.MangaParser` | Interface parser (extends `okhttp3.Interceptor`). App meng-proxy semua method; pakai `PagedMangaParser` sebagai base |
| `tsuki.MangaLoaderContext` | Context yang diberikan app ke parser (httpClient, config, dsb) — jangan di-ship, jangan diimplement |

Untuk kompatibilitas dengan **app versi lama** (sebelum migrasi tsuki, mis. 0.0.31 yang saat ini berstatus "Latest"), plugin juga harus menyediakan varian org.koitharu: `org.koitharu.kotatsu.parsers.model.MangaParserSource` + `org.koitharu.kotatsu.parsers.MangaParserFactoryKt.newParser(...)`, beserta framework `core-parsers` (org.koitharu) dibundle penuh di dalam dex — app lama tidak menyediakannya. App baru (tsuki) memuat varian tsuki; app lama memuat varian org.koitharu (lewat fallback `kotatsuki.*` → `org.koitharu.kotatsu.parsers.*`).

Kelas parser sendiri boleh di package apa pun — repo ini memakai konvensi `org.koitharu.kotatsu.parsers.site.id` (tsuki-style) dan `...site.id.legacy` (org.koitharu-style).

### 3. Dependency (versi yang dipakai repo ini)

Framework & library **TIDAK perlu di-ship** — app sudah menyediakan tsuki/jsoup/okhttp/dll dan loader selalu mengutamakan kelas app. Tapi `build.sh` tetap menggabungkannya ke fat jar supaya plugin tetap jalan jika suatu saat app mengubah versi dependency (inert, tidak dipakai app).

Diperlukan saat **kompilasi** (classpath kotlinc):

| Dependency | Versi | Sumber |
|---|---|---|
| `tsuki` (framework app Usagi) | 1.0.5 | Jitpack `io.github.UsagiApp.Tsuki` |
| `core-parsers` (framework org.koitharu, untuk varian legacy) | 0.0.3 | Jitpack `com.github.UsagiApp.core-parsers` |
| `kotlin-compiler-embeddable` | 2.2.10 | Maven Central |
| `kotlin-stdlib` | 2.2.10 | Maven Central |
| `jsoup` | 1.17.2 | Maven Central |
| `okhttp` | 5.3.2 | Maven Central |
| `okio-jvm` | 3.17.0 | Maven Central |
| `org.json` | 20251224 | Maven Central |
| `kotlinx-coroutines-core-jvm` | 1.10.2 | Maven Central |
| `kotlinx-serialization-json/core-jvm` | 1.11.0 | Maven Central |
| `androidx.collection:collection-jvm` | 1.6.0 | Google Maven |
| `r8` (d8) | 8.13.22 | Google Maven |
| `org.jetbrains:annotations` | 24.1.0 | Maven Central |

Versi harus selaras dengan app (Kotlin 2.2.x / jvm-target 17) — metadata Kotlin yang lebih baru dari stdlib app akan ditolak saat load.

### 4. Langkah build (apa yang dilakukan `build.sh`)

```bash
# 1a) compile tsuki-style — perhatikan -opt-in=tsuki.InternalParsersApi (API tsuki butuh opt-in)
kotlinc -no-stdlib -no-reflect -opt-in=tsuki.InternalParsersApi \
  -classpath "$FRAMEWORK_CP" -jvm-target 17 -d out \
  src/*.kt plugin/tsuki/model/MangaParserSource.kt plugin/tsuki/MangaParserFactory.kt

# 1b) compile legacy (org.koitharu) untuk app lama
kotlinc -no-stdlib -no-reflect -opt-in=org.koitharu.kotatsu.parsers.InternalParsersApi \
  -classpath "$LEGACY_CP" -jvm-target 17 -d out-legacy \
  src-legacy/*.kt plugin/legacy/model/MangaParserSource.kt plugin/legacy/MangaParserFactory.kt

# 2) fat jar: kelas hasil compile + tsuki + core-parsers + semua dependency
(cd jarroot && zip -qr ../fatjar.jar .)

# 3) dex: min API 24 (sesuai minSdk Usagi)
d8 --release --min-api 24 --output dexout fatjar.jar

# 4) bungkus ulang: classes.dex -> indosource.jar (HANYA file ini)
(cd dexout && zip -q ../indosource.jar classes.dex)
```

### 5. Checklist plugin valid

- [ ] `indosource.jar` berisi **tepat satu file** `classes.dex` di root
- [ ] Di dex ada `tsuki.model.MangaParserSource` + `tsuki.MangaParserFactoryKt.newParser` (dan varian org.koitharu untuk app lama)
- [ ] Setiap konstanta enum punya parser nyata di `when(source)` factory
- [ ] Semua override `MangaParser` diimplement (dengan base `PagedMangaParser` yang jalan)
- [ ] Tidak ada permission/resources/manifest Android
- [ ] Jaringan hanya ke domain sumber + CDN-nya

Verifikasi cepat dengan jadx:

```
jadx -d /tmp/verify indosource.jar
grep -r "VORATOON" /tmp/verify/sources/tsuki/model/MangaParserSource.java   # enum ada
grep -n "newParser" /tmp/verify/sources/tsuki/MangaParserFactoryKt.java     # factory ada
```

---

## Disclaimer

- Repo ini tidak resmi dan tidak berafiliasi dengan situs manga mana pun. Konten adalah hak milik penerbit masing-masing.
- Situs dapat berubah struktur, memblokir, atau mati; perbaikan dirilis melalui update di repo ini.
- Untuk penggunaan pribadi. Hapus jika diminta pemilik hak cipta.

## Lisensi

[MIT](LICENSE)
