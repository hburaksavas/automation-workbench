# TestTR Discovery

Bağımsız Java 17+ / Maven projesi. Sayfa açıldığında objeleri otomatik keşfeder;
DOM değişikliklerinde taramayı yeniler ve kullanıcının tıklama, doldurma ve seçim
işlemlerini sıralı JSON kaydına ekler. TestTR veya VS Code kurulumu gerekmez.
CLI ve stdio MCP server aynı Java servislerini kullanır. Registry önerisi, tarayıcı
doğrulaması ve seçili adayları ayrı Registry dosyasına aktarma desteklenir.
Ayrı bir kontrol paneli yoktur; inceleme için çevrimdışı HTML raporları üretilir.

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
TestTR Registry'ye doğrudan otomatik uygulanmaz. Aşağıdaki öneri ve doğrulama
akışından geçirilir; ad/kapsam seçimi gerekir.

## Registry önerisi: keşiften kullanılabilir kayda

Komutları proje dizininden çalıştırın. Önce normal keşifle `target/discovery.json`
üretin. Sonra mevcut TestTR Registry JSON dosyasını veya Registry dizinini verin:

```powershell
java -jar target/testtr-discovery-0.1.0-SNAPSHOT.jar suggest-registry --discovery target/discovery.json --registry ../testtr/example-tests/src/test/registry --base-url https://uygulama.example/ --output target/registry-proposal.json
```

`--base-url`, Registry'deki göreli URL'leri çözmek içindir; son `/` standart URI
çözümlemesini etkiler. Sayfa eşleştirmesi tam adresle yapılır; farklı host'lar
yalnızca aynı yola sahip oldukları için eşleştirilmez. Query/fragment gerektiren
sayfalar keşifte bu parçalar tutulmadığından otomatik eşleşmeyebilir.
Registry dizininde normal `.json` dosyaları isim sırasıyla, ardından
`.override.json` dosyaları uygulanır; bu sıra TestTR ile aynıdır.

Yeni sayfalar veya belirsiz sayfa eşleşmeleri için `--page-map page-map.json`
ekleyin. Biçim, keşif raporundaki URL'den seçilen Registry sayfa adına eşlemedir:

```json
{
  "https://uygulama.example/musteriler": "Müşteriler"
}
```

Yeni bir Registry başlatırken `{"pages":{}}` içeren JSON dosyası kullanılabilir.
Öneri JSON'u ve yanında HTML diff raporu üretilir. Durumlar:

| Durum | Anlamı |
|---|---|
| `new` | Yeni obje veya açık eşlemeyle yeni sayfa |
| `unchanged` | Mevcut ad, tür ve CSS aynı |
| `locator-change` | Aynı sayfa ve adda locator değişikliği adayı |
| `review` | Belirsiz veya doğrudan uygulanamayan kayıt; gerekçesi raporda |

Aynı adlı birden fazla obje, tür çelişkisi, yapısal CSS, başka ada bağlı locator,
iframe veya bilinmeyen frame kapsamı incelemeye ayrılır. Mevcut TestTR Registry
biçimine iframe kapsamı uydurulmaz. Eski keşif JSON'larında `mainFrame` bilgisi
olmadığından bu kayıtlar da inceleme gerektirir; yeni sürümle tekrar keşfedin.
Önce etkileşilmiş objeler listelenir; iş kuralı veya beklenen sonuç üretilmez.

Birden fazla gözlemi karşılaştırmak için `--discovery ilk.json,ikinci.json`
kullanın. Aynı ad için farklı CSS/türler incelemeye ayrılır. Aynı dosyanın iki kez
verilmesi yeni kanıt sayılmaz. `repeated-observation` yalnızca farklı raporlarda
görülmeyi belirtir; bağımsız oturum kararlılığı garantisi değildir.

Tarayıcı doğrulaması her hedef URL'yi **iki yeni, izole context** içinde açar;
CSS tekilliğini, obje adını ve türünü kontrol eder. Locator değişikliğinde eski
CSS hâlâ başka bir objeyi buluyorsa doğrulama başarısız olur:

```powershell
java -jar target/testtr-discovery-0.1.0-SNAPSHOT.jar validate-registry --proposal target/registry-proposal.json --output target/registry-validated.json
```

Varsayılan Chromium ve headless modudur; `--browser`, `--channel`, `--connect`,
`--cdp` ve `--headless` seçenekleri kullanılabilir. Giriş/modal hazırlığı için
`--flow examples/customer-flow.json` eklenebilir. Akış **her URL için iki kez**
çalışır; buna uygun tekrar çalıştırılabilir test verisi kullanın. Giriş oturumu
devralınmaz. İnceleme gereken adaylar doğrulanmaz; başarısız kayıtların kanıtı
JSON'da, özeti HTML'de kalır. Bu doğrulama locator'ın gelecekte değişmeyeceğini
veya uygulamanın iş kuralının doğru olduğunu kanıtlamaz.

HTML/JSON raporundan kabul ettiğiniz adayların `id` değerlerini seçin:

```powershell
java -jar target/testtr-discovery-0.1.0-SNAPSHOT.jar apply-registry --proposal target/registry-validated.json --registry ../testtr/example-tests/src/test/registry --accept ADAY_ID_1,ADAY_ID_2 --output target/registry-next.json
```

Yalnızca `new`/`locator-change` durumundaki ve iki doğrulamadan geçmiş adaylar
uygulanır. Registry öneriden sonra değişmişse tekrar öneri üretmeniz gerekir.
Çıktı yeni bir **tam Registry snapshot** dosyasıdır; mevcut Registry'ye veya
Registry dizininin içine yazılamaz ve mevcut çıktı dosyasının üzerine yazılmaz.
Dosyayı inceleyip projenizdeki Registry'ye taşıyın; kaynak Registry geri dönüş
için korunur. Öneri ve doğrulama çıktıları da kendi girdi dosyalarını ezemez.

## MCP server

Yerel stdio server'ı başlatma:

```powershell
java -jar target/testtr-discovery-0.1.0-SNAPSHOT.jar mcp --root D:/dev/my-test-project
```

MCP istemcisinin server yapılandırmasına `command` olarak `java`, `args` olarak
aşağıdaki listeyi verin; JAR ve root yollarını kendi makinenize göre değiştirin:

```json
{
  "mcpServers": {
    "testtr-discovery": {
      "command": "java",
      "args": [
        "-jar", "D:/tools/testtr-discovery-0.1.0-SNAPSHOT.jar",
        "mcp", "--root", "D:/dev/my-test-project"
      ]
    }
  }
}
```

| Tool | Girdiler / çıktı |
|---|---|
| `start_discovery` | `url`, `output`; isteğe bağlı tarayıcı seçenekleri, `flow`, `duration`, `timeoutSeconds`; iş kimliği döner |
| `job_status` | `jobId`; durum, çıktı yolu ve varsa hata |
| `stop_job` | `jobId`; iptal isteği gönderir |
| `read_report` | `path`; JSON raporunu döndürür |
| `suggest_registry` | `discovery` dosya listesi, `registry`, `output`; isteğe bağlı `pageMap`, `baseUrl` |
| `validate_registry` | `proposal`, `output`; isteğe bağlı `flow`, tarayıcı seçenekleri, `timeoutSeconds`; iş kimliği döner |
| `apply_registry` | `proposal`, `registry`, `accept` kimlik listesi, `output`; yeni snapshot oluşturur |

Örnek `start_discovery` argümanları:

```json
{"url":"https://uygulama.example/musteriler","output":"target/discovery.json","headless":false,"duration":300}
```

Dosya yolları `--root` altından çözülür; dışarı çıkan yollar ve symlink'ler
reddedilir. Registry dizinindeki JSON bağlantıları da kontrol edilir. URL için
HTTP/HTTPS veya root içindeki yerel HTML dosyası kullanılabilir. Bu bir ağ
sandbox'ı değildir; sayfa ve yönlendirmeleri tarayıcının ağ erişimini kullanır.
Standart protokol mesajları stdout'a, uygulama logları stderr'e gider.
[MCP stdio sözleşmesi](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)
ve initialize → initialized → tools/list/tools/call yaşam döngüsü desteklenir.
HTTP taşıması bulunmaz.

İş durumları: `queued`, `running`, `completed`, `failed`, `cancelled`, `timed-out`.
İptal isteğinden sonra terminal duruma kadar `job_status` sorgulayın; durum ancak
işin kaynakları temizlendikten sonra terminal olur. İptal devam eden tarayıcı
işleminin bitmesini/zaman aşımını bekleyebilir. Keşifte işlem zaman aşımı 10 sn,
doğrulamada 5 sn, navigasyonda 15 sn'dir. MCP iş zaman aşımı varsayılan 600 sn,
keşif süresi 300 sn; her ikisi 1–3600 sn arasında yapılandırılabilir. Server aynı
anda iki iş yürütür; oturum başına en fazla 64 iş kabul eder. Raporları aldıktan
sonra server yeniden başlatılabilir. EOF'de işler iptal edilip kaynaklar kapatılır.
Uzak tarayıcıya bağlanıldığında yalnızca bu işin oluşturduğu context kapatılır.

Kullanılabilir işlemleri sınırlamak için örneğin
`--allow-tools start_discovery,job_status,stop_job,read_report,suggest_registry,validate_registry`
ekleyin. Böylece `apply_registry` listelenmez ve çağrılamaz. CLI ve MCP aynı
öneri/doğrulama kodunu kullanır; TestTR veya VS Code MCP entegrasyonu bu bağımsız
server'ı kullanmak için zorunlu değildir.

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

Ek testler Registry karşılaştırmasını, katmanlı Registry okumasını, belirsiz
eşleşmeleri, HTML kaçışlarını, eski öneri/girdi dosyası korumasını, gerçek
tarayıcıyla iki oturumda doğrulamayı ve seçili aday aktarımını kapsar. MCP testleri
initialize/tool şemalarını, stdio JSON-RPC çıktılarını, tool kısıtlamasını,
proje kökü kontrolünü, gerçek keşif işi iptalini ve iş zaman aşımını doğrular.
