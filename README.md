# ÓraJegyzet — Android jegyzetelő app diákoknak

Automatikus órai jegyzetelő: becsengetéskor értesítés, egy koppintásra felvétel,
kicsengetéskor automatikus leállás, majd leirat + strukturált jegyzet + összefoglaló.
Nap végén email-kivonat. **Két választható feldolgozási mód:**

- **Offline** — Vosk (magyar STT) + Gemma (MediaPipe LLM Inference) a készüléken.
  A hang soha nem hagyja el a telefont.
- **Online** — Gemini Flash-Lite: a hangfájlból egyetlen API-hívás készít
  leiratot és jegyzetet, majd a fájl törlődik a felhőből.
- **Automatikus** — hálózat és beállítások alapján dönt; az „Adatvédelmi zár"
  kapcsoló mindig offline-ra kényszerít.

Referenciakészülék: **Poco F7 Pro** (Snapdragon 8 Gen 3, 12 GB RAM) — az offline
mód ezen kényelmesen fut.

## 1. Fordítás és telepítés

1. Nyisd meg a projektet **Android Studio**-ban (Koala vagy újabb).
2. Hagyd, hogy a Gradle szinkronizáljon (internet kell az első buildhez).
3. Csatlakoztasd a telefont USB-n (fejlesztői mód + USB-hibakeresés), majd **Run**.

> Megjegyzés: a függőség-verziók és a Gemini modellnév (`gemini-3.1-flash-lite`)
> időnként változnak — ha a build vagy az API-hívás hibát ad, ellenőrizd az
> aktuális verziókat ill. a https://ai.google.dev dokumentációt.

## 2. Online mód beüzemelése (a leggyorsabb út)

1. Szerezz ingyenes Gemini API-kulcsot: https://aistudio.google.com/apikey
2. App → Beállítások → illeszd be a kulcsot, válaszd az **Online** vagy
   **Automatikus** módot. Kész.

## 3. Offline mód beüzemelése (adb NEM szükséges)

A modellek nagyok, ezért nem részei az APK-nak — egyszeri letöltés és import
után minden helyben fut.

### 3a. Whisper magyar beszédfelismerő modell (AJÁNLOTT — a legjobb magyar minőség)

Az offline felismerés elsődleges motorja a Whisper (large-v3-turbo), ami magyarul
lényegesen pontosabb a Vosknál. A Poco F7 Pro elbírja.

1. A **telefon böngészőjével** töltsd le a ggml modellt a whisper.cpp gyűjteményből:
   https://huggingface.co/ggerganov/whisper.cpp/tree/main
   - Legjobb minőség: `ggml-large-v3-turbo.bin` (~1,6 GB)
   - Kíméletesebb (kvantált, szinte ugyanolyan jó, gyorsabb): `ggml-large-v3-turbo-q5_0.bin` (~575 MB)
2. App → Beállítások → **„Whisper modell (.bin) importálása"** → válaszd ki a letöltött
   fájlt. A státusz „telepítve ✓"-re vált. Ha a Whisper telepítve van, az app
   automatikusan azt használja a Vosk helyett.

### 3a-alt. Vosk magyar modell (tartalék, opcionális)

Ha nem akarsz nagy Whisper-modellt letölteni, a Vosk kisebb, de gyengébb:
1. Töltsd le: https://alphacephei.com/vosk/models → `vosk-model-small-hu-0.22.zip` (~50 MB)
2. App → Beállítások → **„Vosk .zip"** → válaszd ki. A Whisper hiányában ezt használja.

### 3b. Gemma modell az összefoglalóhoz (ajánlott, de opcionális)

Gemma nélkül az offline mód leiratot ad, összefoglaló nélkül.

1. A telefon böngészőjével tölts le egy MediaPipe-kompatibilis `.task`
   formátumú Gemma modellt (pl. Gemma 3 4B int4 a Poco F7 Pro-ra, 1B gyengébb
   készülékre) — a forrásokat a MediaPipe LLM Inference dokumentáció sorolja:
   https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference
   (A letöltéshez Kaggle/Hugging Face fiók és a Gemma licenc elfogadása kellhet.)
2. App → Beállítások → **„Gemma .task importálása"** → válaszd ki a fájlt.

*(Fejlesztőknek az adb-s push továbbra is működik alternatívaként:
`files/models/vosk-hu` ill. `files/models/gemma.task` a célhelyek.)*

### 3c. Megjegyzés a felismerő motorokról

A felismerés sorrendje automatikus: ha a Whisper modell telepítve van, azt
használja (legjobb magyar minőség); különben a Vosk a tartalék.

**FONTOS — a Whisper forrásból fordul, magyarra állítva.** A `:whispercore`
modul a whisper.cpp-t tartalmazza, és a `jni.c`-ben a nyelv magyarra van
állítva (`params.language = "hu"`) — a publikus könyvtár ugyanis fixen angolra
volt drótozva, ami magyarra használhatatlan. Ezért a build NDK-t és CMake-et
igényel:

1. Android Studio → **Tools → SDK Manager → SDK Tools** fül.
2. Pipáld be és telepítsd: **NDK (Side by side)** és **CMake**.
3. Az első Gradle sync után, ha hiányt jelez, Android Studio felajánlja a
   megfelelő NDK letöltését — fogadd el.

Az első APK-build emiatt jóval lassabb (a natív whisper.cpp fordítása arm64-re,
5–20 perc). A további buildek gyorsabbak. Csak `arm64-v8a`-ra fordít (Poco F7 Pro).

## 4. Email-kivonat beállítása

Gmail esetén: Google-fiók → Biztonság → 2FA bekapcsolása →
**Alkalmazásjelszavak** → új jelszó „Mail" alkalmazásra. Ezt írd be a
Beállítások → SMTP jelszó mezőbe (host: `smtp.gmail.com`, port: `587`).

## 5. Használat

1. **Órarend** fülön vidd fel az óráidat (nap, kezdés, vége).
2. Becsengetéskor értesítés érkezik → egy koppintás → indul a felvétel.
   (Android 14+ szabály miatt a mikrofonos felvétel háttérből nem indítható
   automatikusan — a leállás viszont automatikus a kicsengetéskor.)
3. A jegyzet a feldolgozás után a főképernyőn jelenik meg; a beállított órában
   (alapértelmezés: 18:00) kimegy a napi email-kivonat.
4. A kezdőképernyőre widget is kitehető (hosszú nyomás → Widgetek → ÓraJegyzet).

## 6. Fontos jogi figyelmeztetés

Más személyek (tanár, diáktársak) hangjának rögzítése **hozzájárulás-köteles**
(GDPR + személyiségi jogok). Használat előtt kérd a tanár / az iskola
engedélyét. Az app adatminimalizálásra épül: a nyers hangfájl a feldolgozás
után azonnal és automatikusan törlődik, csak a szöveges jegyzet marad.

## 7. Projektszerkezet

```
app/src/main/java/hu/orajegyzet/
  App.kt                  — értesítési csatornák, indítási újraütemezés
  MainActivity.kt         — navigáció, engedélyek, felvétel-indítás
  data/Db.kt              — Room: órarend + jegyzetek
  data/Settings.kt        — DataStore: mód, kulcsok, SMTP
  bell/Bell.kt            — AlarmManager csengetés-időzítés + értesítés
  rec/RecordingService.kt — mikrofonos foreground service (M4A, auto-stop)
  ai/NotePipeline.kt      — pipeline-interfész + módválasztó (AUTO logika)
  ai/GeminiPipeline.kt    — online: Files API + generateContent
  ai/OfflinePipeline.kt   — offline: MediaCodec→PCM, Vosk STT, Gemma összefoglaló
  work/ProcessingWorker.kt— feldolgozási sor, hangfájl-törlés, kész-értesítés
  work/DigestWorker.kt    — napi kivonat + SMTP email
  widget/LessonWidget.kt  — Glance kezdőképernyő-widget
  ui/                     — Compose képernyők (Ma, Jegyzet, Órarend, Beállítások)
```

## 8. Ismert korlátok (MVP)

- A becsengetési értesítésből az indítás egy koppintás (platformkorlát).
- Az órarend hetente ismétlődő; A/B hetek még nincsenek.
- A modellek importja fájlválasztóval megy; közvetlen app-on belüli letöltő (URL-ből): 2. fázis.
- Hő-adaptív ütemezés (THERMAL_STATUS) és Vosk élő leirat: 2. fázis.
