# Gereksinimden AI senaryo taslağı

`testtr:draft` Maven hedefi, onaylı gereksinimleri, birleşik Registry'yi ve mevcut `.testtr` dosyalarını kullanarak eksik senaryo önerileri üretir. Normal `test` yaşam döngüsüne bağlı değildir; açıkça çağrılır. Yalnızca `approved` gereksinimler modele gönderilir.

## Çalıştırma

JDK 17 ile depo kökünde TestTR bileşenlerini yerel Maven deposuna kur:

```powershell
$wrapper = Join-Path (Get-Location) 'projects/testtr/mvnw.cmd'
& $wrapper -f projects/testtr/pom.xml -pl maven-plugin,runner-support -am install
```

`OPENAI_API_KEY` ortam değişkenini kendi ortamında ayarla. Anahtarı POM'a veya senaryo dosyalarına yazma. Responses API ve Structured Outputs destekleyen, hesabında erişebildiğin modelin kimliğini `MODEL_ID` yerine yaz:

```powershell
& $wrapper -f projects/testtr/example-tests/pom.xml `
  dev.testtr:testtr-maven-plugin:0.1.0-SNAPSHOT:draft `
  '-Dtesttr.aiModel=MODEL_ID'
```

Bağımsız projede `-f` ile o projenin POM'unu seç. Unix'te aynı argümanlarla `sh projects/testtr/mvnw` kullanılır. Gereksinim kataloğu varsayılan olarak `src/test/requirements.json`, Registry `src/test/registry/` veya eski `src/test/registry.json`, mevcut senaryolar `src/test/testtr/` konumundan okunur. POM'daki `<sourceDirectory>`, `<helpers>`, `<registry>` ve `<requirements>` eklenti ayarları özelleştirilebilir.

API isteği proje gereksinimlerini, Registry içeriğini ve mevcut senaryo metinlerini OpenAI'a gönderir. Ortam değişkenlerinin değerleri ve Java yardımcı işlem uygulamaları bağlama eklenmez. Mevcut senaryolardaki sabit metinler bağlama dahildir. API anahtarı yalnızca HTTP yetkilendirme başlığında kullanılır. İstek `store: false` kullanır. Protokol [OpenAI Responses API belgesine](https://developers.openai.com/api/reference/resources/responses/methods/create) dayanır.

## Çıktı ve inceleme

Her çağrı `target/testtr-drafts/<kimlik>/` altında ayrı bir sonuç üretir:

- `draft-1.testtr` gibi dosyalar: Registry, dil kuralları, gereksinim bağlantısı ve sürümü doğrulanmış taslaklar.
- `manifest.json`: inceleme durumu, gerekçeler, katalog/Registry özetleri, derleyici sürümü ve istenen model.
- `attempt-1.json` gibi dosyalar: modelin taslak ve inceleme yanıtları.
- `diagnostic-1.txt` gibi dosyalar: geçersiz yanıtların derleyici tanıları.

Taslak, tek bir senaryo ve eşleşen `Gereksinim:` satırı içerir. İlk sürüm bağımsız UI adımlarıyla sınırlıdır; dosya içe alma ve Java yardımcı işlem çağrısı üretilmez. Her taslak mevcut derleyiciyle ayrıştırılır ve üç framework için Java üretimi denenir. Bu aşama `javac` veya tarayıcı çalıştırmaz. Taslak dizini test kaynaklarına eklenmez.

Gereksinim yeterli değilse, beklenen davranış belirsizse veya model çelişki görürse sonuç `review` listesinde gerekçesiyle bulunur. Yalnızca inceleme maddeleri içeren bir sonuç da geçerlidir. Derlenebilir olmak, beklenen davranışın doğruluğunu veya kapsamın yeterliliğini kanıtlamaz. Mevcut dosyanın birebir tekrarı reddedilir; anlamsal kapsam karşılaştırması model önerisidir ve inceleme gerektirir.

Gerekçeyi, beklenen sonuçları ve mevcut testlerle farkı incele. Kabul ettiğin `.testtr` dosyasını `src/test/testtr/` içine anlamlı bir adla kopyalayıp projenin normal Maven `test` komutunu çalıştır. Taslakları saklamak istiyorsan `clean` öncesinde kopyala veya `-Dtesttr.draftDirectory=<ayrı-dizin>` kullan. Kaynak senaryo dizini ile taslak dizini çakışamaz.

## Sınırlar ve çevrimdışı kontrol

Varsayılan toplam model çağrısı sınırı 2'dir; `-Dtesttr.aiMaxAttempts=1` ile tek çağrı yapılabilir, üst sınır 3'tür. Geçersiz taslakta önceki yanıt ve derleyici tanısı modele geri verilir. Son deneme de geçersizse komut başarısız olur; inceleme dosyaları kalır ve çalıştırılabilir taslak yazılmaz. HTTP hataları ve tamamlanmayan/reddedilen API yanıtları otomatik yeniden denenmez.

`testtr.aiMaxOutputTokens` varsayılan 4000, izin verilen aralık 256–16000'dür; her çağrı için uygulanır. İstek zaman aşımı 90 saniye, proje bağlamı sınırı 250000 karakter, yanıt başına taslak sınırı 20'dir. API kullanımı seçilen modelin ücretlendirmesine tabidir.

API olmadan kayıtlı bir taslak JSON yanıtını `-Dtesttr.aiResponse=<dosya>` ile doğrulayabilirsin. Bu modda model ve anahtar gerekmez; manifest kaynağı `response_file` olur. Örnek yanıt biçimi:

```json
{
  "drafts": [],
  "review": [
    {"requirement": "REQ-GIRIS-001", "reason": "Pozitif akış mevcut testte kapsanıyor; negatif akışın beklenen sonucu belirtilmemiş."}
  ]
}
```

Depo kökünde PowerShell 7 ile `./.github/scripts/verify-ai-drafts.ps1`, bağımsız bir Maven projesinde kayıtlı bir yanıtı doğrular, kaynak izolasyonunu denetler ve yalnızca betikteki elle yazılmış kabul örneğini Chrome'da çalıştırır. Canlı API çağrısı yapmaz. CI aynı kontrolü Windows ve Ubuntu için çalıştıracak şekilde yapılandırılmıştır.
