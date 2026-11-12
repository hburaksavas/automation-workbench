# TestTR Discovery

Bağımsız Java 17+ / Maven projesi. Sayfa açıldığında objeleri otomatik keşfeder;
DOM değişikliklerinde taramayı yeniler ve kullanıcının tıklama, doldurma ve seçim
işlemlerini sıralı JSON kaydına ekler. TestTR veya VS Code kurulumu gerekmez.
İlk sürüm CLI'dır; ayrı bir kontrol paneli henüz yoktur.

## Derleme ve çalıştırma

Windows PowerShell:

```powershell
.\mvnw.cmd clean verify
java -jar target/testtr-discovery-0.1.0-SNAPSHOT.jar --url https://uygulama.example/musteriler --output target/discovery.json
```

Linux/macOS: aynı dizinde `sh ./mvnw clean verify`, ardından aynı `java -jar` komutu.
Playwright ilk çalıştırmada tarayıcılarını indirebilir. Linux container/CI'da sistem
bağımlılıklarıyla önceden kurmak için:

```sh
java -cp target/testtr-discovery-0.1.0-SNAPSHOT.jar com.microsoft.playwright.CLI install --with-deps chromium
```

Tarayıcıda giriş yapın, sekmeleri/modalleri açın ve normal akışınızı kullanın.
Taramayı ayrıca başlatmanız gerekmez. JSON her gözlemde güncellenir; bu oturuma ait
tüm sayfaları kapatınca komut biter. Aynı çıktı yolu yeni oturumda yeniden yazılır.
Chrome için `--channel chrome`, Edge için `--channel msedge` kullanılabilir.

## Çıktı

Her JSON çıktısının yanında otomatik olarak aynı adlı HTML raporu oluşturulur:
`target/discovery.json` → `target/discovery.html`. Her ikisi de gözlemlerle birlikte
güncellenir. HTML'yi tarayıcıda açın; son kayıtları görmek için sayfayı yenileyin.
Rapor çevrimdışı çalışır, harici font veya JavaScript gerektirmez; yazdırılarak
PDF olarak da saklanabilir. Özet sayıları, sayfa/frame/URL bazında obje tablolarını,
inceleme gereken locator'ları ve sıralı etkileşim akışını içerir.

`objects` listesinde örnek kayıt:

```json
{
  "name": "Müşteri No",
  "css": "[data-testid=\"customer-number\"]",
  "type": "input",
  "quality": "attribute",
  "matches": 1,
  "visible": true,
  "enabled": true,
  "interacted": true,
  "page": "page-1",
  "frame": "frame-1",
  "url": "https://uygulama.example/musteriler"
}
```

`interactions` işlem sırasını, zamanını, obje bilgisini ve gerektiğinde
`parameterRequired: true` bilgisini taşır. Girilen değerler, seçilen seçenek
değerleri ve tuşlar kaydedilmez; parola alanlarıyla etkileşimler atlanır.
URL sorgusu ve fragment rapora alınmaz. DOM etiketleri ve selector özellikleri
uygulamanızın gösterdiği metinleri içerebilir; bu alanlarda genel kişisel veri
maskelemesi yapılmaz.

Adlandırma sırası: label → aria-label → aria-labelledby → placeholder → düğme/link
metni → name → id → HTML türü. Locator için tekil data-testid/data-test/data-qa,
id, name veya aria-label denenir. Tekillik bulunamazsa yapısal CSS üretilir;
`quality: structural` kayıtları bakım açısından incelenmelidir. `attribute` da
yeniden açılışlar arasında kararlılık garantisi değildir.

Objeler sayfa/frame/URL/selector/ad ile biriktirilir. `lastSeen` son görülmeyi,
görünürlük son gözlemi gösterir; kaldırılmış objeler envanterden silinmez.
Aynı isimli objeler ayrı kayıtlardır. CSS kendi frame belgesinde doğrulanır;
iframe kayıtlarını üst belgede doğrudan kullanmayın. Bu çıktı bir keşif raporudur;
TestTR Registry'ye doğrudan otomatik uygulanmaz. Ad/kapsam seçimi gerekir.

## Uzak tarayıcı ve CI

```sh
java -jar target/testtr-discovery-0.1.0-SNAPSHOT.jar --url https://uygulama.example --connect ws://browser-host:3000/ --duration 60
java -jar target/testtr-discovery-0.1.0-SNAPSHOT.jar --url https://uygulama.example --cdp http://browser-host:9222 --duration 60
java -jar target/testtr-discovery-0.1.0-SNAPSHOT.jar --url https://uygulama.example --headless true --duration 10
```

`--connect` Playwright protokolünü, `--cdp` yalnızca Chromium CDP'yi kullanır.
Uzak Playwright sunucusunu istemciyle uyumlu sürümde çalıştırın (1.51.x).
Her oturum yeni, izole bir browser context açar; mevcut kullanıcı sekmelerini veya
giriş oturumunu devralmaz ve uzak tarayıcıyı kapatmaz. Hedef uygulamaya erişim
tarayıcı makinesinden sağlanmalıdır. Uzak tarayıcıyı görme/etkileşim altyapısı
(örneğin VNC) bu aracın dışında sağlanır.

## Tekrarlanabilir adımlar

`--flow examples/customer-flow.json --headless true` ile önceden tanımlı adımlar
çalıştırılabilir. `fill` ve `select` değerlerini `env` ile ortam değişkeninden alır.
Akış bittiğinde son tarama için kısa süre beklenir; `--duration` verilirse gözlem
belirtilen süre devam eder. Desteklenen adımlar: click, fill, select, check,
waitFor, scan. `waitFor` görünürlük bekler; keyfi sabit bekleme gerekmez.

Etkileşim raporu doğrudan replay dosyası değildir: doldurmalara parametre,
geçişlere bekleme ve iframe akışlarına kapsam tanımı gerekir. Kayıt bir test
doğrulaması veya iş kuralı üretmez.

## Sınırlar ve doğrulama

İlk sürüm form kontrolleri, bağlantılar ve yaygın ARIA etkileşim rollerini tarar.
Shadow DOM, canvas kontrolleri, yalnızca hover/özel klavye olayları ve genel
görüntü/metin envanteri kapsam dışıdır. Başlangıç ve DOM değişikliği taramaları
250 ms gecikmeyle birleştirilir; sürekli değişen sayfalarda yaklaşık 1 saniyede
tarama yapılır. Çok kısa süre görünen elemanlar kaçabilir.

`clean verify` gerçek headless Chromium ile otomatik başlangıç, dinamik modal,
tekil/yapısal selector, frame, yeni sayfa, navigasyon, etkileşim kaydı, değerlerin
çıktıya girmemesi ve tanımlı akışın çalışmasını sınar. İkinci test yerel CDP
endpoint'ine bağlanır ve keşif sonunda mevcut tarayıcı/sekmenin açık kaldığını
doğrular. Harici uzak sunucu, Playwright WebSocket bağlantısı ve diğer işletim
sistemleri ayrıca ortam doğrulaması gerektirir.
