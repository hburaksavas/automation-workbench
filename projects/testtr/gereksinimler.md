# Sürümlü gereksinim kataloğu

Test projesinde `src/test/requirements.json` varsa Maven, senaryolardaki `Gereksinim:` bağlantılarını bu katalogla doğrular. Farklı bir dosya POM'da eklentinin `<requirements>` ayarıyla veya `-Dtesttr.requirements=<dosya>` ile seçilebilir. Açıkça seçilen dosya yoksa derleme hata verir.

```json
{
  "schemaVersion": 1,
  "requirements": [
    {
      "id": "REQ-GIRIS-001",
      "version": "1",
      "status": "approved",
      "text": "Geçerli kullanıcıyla giriş yapıldığında karşılama mesajı görünür."
    }
  ]
}
```

Her kayıt boş olmayan `id`, `version`, `status` ve `text` metinlerini taşır. Durum `approved`, `draft` veya `rejected` olabilir. Bir katalog, her kimlik için tek bir güncel sürüm içerir. Tekrarlı kimlikler ve geçersiz alanlar derlemeyi durdurur. Onay durumu projedeki gereksinim incelemesinin sonucunu temsil eder; TestTR kendisi onay vermez.

Senaryo başlığının hemen altında bağlantı kurulur:

```text
Senaryo: "Giriş işlemi".
  Gereksinim: "REQ-GIRIS-001".
  "Giriş" sayfası açılır.
  "Mesaj" metni "Hoş geldiniz" olmalıdır.
Senaryo sonu.
```

Bağlı gereksinim katalogda bulunmuyorsa veya onaylı değilse Maven kaynak üretimini ilgili `Gereksinim:` satırında `[DSL]` hatasıyla durdurur. Tarayıcı testleri başlamaz. Katalog bulunmayan projeler önceki davranışla çalışır; `Gereksinim:` satırı ve katalog kullanıldığında bile bağlantısız senaryolar isteğe bağlı kalır.

Üretilen JUnit raporunun `TESTTR` kaydı mevcut `requirement` alanına ek olarak `requirementVersion` ve katalog dosyasının baytlarından hesaplanan `requirementsSha256` alanlarını içerir. Gereksinim metni rapora kopyalanmaz. Senaryo kimliği dosya yolu ve senaryo adından üretilmeye devam eder; gereksinimin sürümü değiştiğinde kimlik değişmez. Eski koşumu yeniden üretmek için rapordaki kod revizyonuyla birlikte o koşumun katalog dosyası da saklanmalıdır.

Katalog [AI taslak üretimine](ai-taslaklar.md) girdi sağlar. `testtr:draft` yalnızca onaylı kayıtları kullanır; modelin ürettiği taslaklar ve belirsizlik bildirimleri incelemeye sunulur.
