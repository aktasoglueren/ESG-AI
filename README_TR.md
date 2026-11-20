# ESG AI Android

Bu depo, Android telefonda yerel yapay zekâ sohbeti çalıştırmak için hazırlanmış Kotlin + Jetpack Compose projesidir.

## Projedeki dosyalar

- `settings.gradle.kts`: Gradle depo ve modül ayarları
- `build.gradle.kts`: Android ve Kotlin eklentileri
- `gradle.properties`: Derleme ayarları
- `app/build.gradle.kts`: Uygulama bağımlılıkları
- `app/src/main/AndroidManifest.xml`: Android izinleri ve uygulama tanımı
- `app/src/main/java/.../MainActivity.kt`: Sohbet ekranı, model yükleme, sesli giriş ve sesli yanıt
- `app/src/main/res/drawable/ic_esg.xml`: ESG uygulama simgesi
- `.github/workflows/build.yml`: GitHub Actions APK ve AAB derlemesi

## Özellikler

- Cihaz üzerinde LiteRT-LM ile yerel metin modeli
- Türkçe metin sohbeti
- Android konuşma tanıma üzerinden mikrofonla giriş
- Android TextToSpeech ile sesli yanıt
- Asistan adını değiştirme
- Sohbet geçmişini telefonda saklama
- Sohbet geçmişini yeni model oturumuna aktarma
- Sohbeti temizleme
- GitHub Actions ile APK ve AAB derleme

## Önemli sınırlar

- Kaynak kod paketi, derlenmiş APK değildir.
- Proje GitHub Actions üzerinde derlenmelidir. Bu çalışma alanında Android derlemesi çalıştırılmadı.
- Uygulama bir `.litertlm` model dosyası ister. Model APK içine eklenmez.
- Model dosyasını ilk seferde indirmen gerekir. Sonra modelin kendisi internet olmadan çalışır.
- Android ses tanıma servisi çevrimdışı Türkçe tanımayı desteklemiyorsa mikrofonla yazıya çevirme internet isteyebilir.
- Sürekli arka planda uyandırma sözcüğü dinleme bu sürümde yoktur.
- Yerel modelin yanıt hızı telefonun işlemcisine, RAM'e ve model boyutuna bağlıdır.
- 8 GB fiziksel RAM için küçük modelle başla. 0.6B sınıfı model, 2B sınıfına göre daha hafiftir.

## Model dosyası

İlk deneme için LiteRT Community Qwen3-0.6B model sayfası:
https://huggingface.co/litert-community/Qwen3-0.6B

Model sayfasından `.litertlm` dosyasını indir. Uygulamada `Model seç` düğmesine bas ve indirdiğin dosyayı seç.

Model lisansını, dosya boyutunu ve cihaz gereksinimlerini indirmeden önce kontrol et.

## Android uygulamasını GitHub'dan derleme

1. GitHub uygulamasını veya tarayıcıyı aç.
2. Yeni bir depo oluştur. Depo adı `ESG-AI` olsun.
3. Bu ZIP içindeki klasör ve dosyaları deponun ana dizinine yükle. `settings.gradle.kts` ana dizinde görünmeli.
4. GitHub deposunda `Actions` sekmesine gir.
5. `ESG AI APK and AAB` iş akışını aç.
6. `Run workflow` düğmesine bas.
7. Derleme bitince iş akışı sayfasındaki `Artifacts` bölümünü aç.
8. `ESG-AI-APK` dosyasını indir.
9. ZIP içindeki APK dosyasını telefona kur.

AAB dosyası `ESG-AI-AAB` adıyla ayrı çıkar. Play Store için AAB dosyasının ayrıca imzalanması gerekir. Bu başlangıç paketi debug APK üretir; release AAB imzalı değildir.

## Uygulama kimliği

`com.esgmuhendislik.esgai`

## Sorun giderme

Derleme hata verirse GitHub Actions içindeki kırmızı hata satırlarını veya log ekran görüntüsünü paylaş. Hata mesajına göre dosyaları düzeltmek gerekir.
