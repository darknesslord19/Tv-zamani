# TV Zamanı — Android uygulaması (TV + telefon)

**Ana kaynak `web/index.html` dosyasıdır.** Arayüzün ve oynatıcının tamamı bu HTML'de.
Android tarafı yalnızca onu tam ekran çalıştıran bir kabuktur ve şunları ekler:

- **CORS sorunu yok:** Liste ve yayın istekleri Android tarafından yapılır, videolar uygulama içinde açılır.
- **Android TV desteği:** TV ana ekranında görünür, kumanda (yön tuşları, OK, geri, kanal +/-, renkli tuşlar, oynat/duraklat) çalışır.
- Geri tuşu önce açık pencereyi/oynatıcıyı kapatır; ana ekranda iki kez basınca çıkar.
- Yedek dosyası **İndirilenler/TV Zamani** klasörüne kaydedilir; M3U ve yedek dosyası seçilebilir.
- "Harici oynatıcı" (VLC / MX Player) seçeneği çalışır.
- Ekran video oynarken kapanmaz.

## APK'yı alma (bilgisayar gerekmeden, GitHub ile)

1. github.com'da ücretsiz hesap aç, **New repository** ile yeni bir depo oluştur (Private seçebilirsin).
2. **Add file › Upload files** ile bu klasörün **içindeki her şeyi** yükle (`.github` klasörü dahil).
   - Telefondan klasör yüklemek zorsa: GitHub Desktop veya bir bilgisayar daha kolaydır.
3. Yükleme bitince **Actions** sekmesine gir. "APK oluştur" işi kendiliğinden başlar (~5 dk).
   Başlamazsa: Actions › APK oluştur › **Run workflow**.
4. İş yeşil tik olunca içine gir, en alttaki **TV-Zamani-APK** dosyasını indir, zip'i aç.
5. `TV-Zamani.apk` dosyasını telefona/TV kutusuna kur (bilinmeyen kaynaklara izin ver).
   TV'ye kurmak için: APK'yı USB belleğe at veya "Send Files to TV" / "Downloader" uygulamasını kullan.

## Android Studio ile (bilgisayar varsa)

Klasörü Android Studio'da aç › **Build › Build APK(s)**. Çıktı: `app/build/outputs/apk/`.

## Güncelleme nasıl yapılır?

Sadece `web/index.html` dosyasını değiştir ve GitHub'a yükle — yeni APK otomatik derlenir.
Yeni APK'yı eskisinin **üstüne kurabilirsin**, profillerin ve hesapların silinmez
(uygulama sabit bir imza anahtarıyla — `app/tvzamani.keystore` — imzalanır; bu dosyayı silme).

Sürüm numarasını artırmak istersen `app/build.gradle` içindeki `versionCode` / `versionName` değerlerini değiştir.

## Notlar

- Oynatıcı kütüphaneleri (hls.js, mpegts.js) ilk açılışta internetten yüklenir ve önbelleğe alınır.
  Tamamen internetsiz çalışması için bu dosyaları `web/lib/` klasörüne koyabilirsin; uygulama otomatik oradan kullanır.
- Uygulamanın verileri tarayıcıdaki sürümden ayrıdır. Tarayıcıda eklediğin hesapları uygulamaya
  **Ayarlar › Wi-Fi aktarım** veya **Yedekleme** ile taşıyabilirsin.
- Depoyu herkese açık (Public) yaparsan imza anahtarı da görünür olur; kişisel kullanım için Private önerilir.
