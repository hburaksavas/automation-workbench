# TestTR for VS Code

TestTR, `.testtr` dosyalarında sözdizimi renklendirmesi ve Türkçe adım şablonları sunar. Adımın ilk çift tırnağını yazınca Registry dizinindeki veya tek JSON dosyasındaki sayfa ve obje adları ile geçerli dosyada veya içe aldığı dosyalarda bulunan ortak adımlar önerilir.

## Kurulum

Bu dizinde `npm ci` çalıştırın. `npx @vscode/vsce package` ile oluşturulan VSIX paketini VS Code'da **Extensions: Install from VSIX…** komutuyla kurun. Maven projesinin kökünü güvenilen bir çalışma alanı olarak açın. JDK 17 için `JAVA_HOME` ayarlı olmalıdır. Tarayıcı koşumu için Google Chrome gerekir.

Bir `.testtr` dosyası açıkken komut paletinden aşağıdaki komutları çalıştırabilirsiniz.

- **TestTR: Senaryoları doğrula** Maven `test-compile` aşamasını çalıştırır. Derleyicinin dosya ve satır içeren DSL tanıları Problems panelinde görünür.
- **TestTR: Üretilen Java kodunu aç** aynı Maven aşamasından sonra açık dosyaya ait Java çıktısını açar. Dosyada birden fazla senaryo varsa seçim sunar.
- **TestTR: Maven testlerini çalıştır** Maven Wrapper üzerinden projenin `test` yaşam döngüsünü çalıştırır. Seçili Maven profili kapsamındaki tüm testler çalışır.

`testtr.profile` ile Selenide, Selenium veya Playwright profilini seçebilirsiniz. Varsayılan seçim POM ayarını kullanır. POM'daki Registry, DSL veya üretilen Java konumu varsayılandan farklıysa `testtr.registry`, `testtr.sourceDirectory` ve `testtr.generatedDirectory` ayarlarını test modülüne göre düzenleyin. Bu editör ayarları POM'u değiştirmez.

## MCP ile Discovery ve TestTR

Eklenti iki yerel stdio MCP server'ına doğrudan istemci olarak bağlanır. Ayrı bir
AI/chat eklentisi gerekmez. Mevcut Maven komutları kullanılabilir; MCP komutları
palete ayrıca eklenir. Bağlantı, ilgili komut ilk çalıştırıldığında açılır.

Önce [TestTR MCP server'ını](../testtr/mcp-server/README.md) ve
[Discovery JAR'ını](../testtr-discovery/README.md) derleyin. Bu deponun tamamı
workspace olarak açıksa örnek ayarlar:

```json
{
  "testtr.profile": "playwright",
  "testtr.mcp.java": "java",
  "testtr.mcp.testtrJar": "projects/testtr/mcp-server/target/testtr-mcp-server-0.1.0-SNAPSHOT.jar",
  "testtr.mcp.discoveryJar": "projects/testtr-discovery/target/testtr-discovery-0.1.0-SNAPSHOT.jar",
  "testtr.mcp.mavenHome": "C:/tools/apache-maven-3.9.9",
  "testtr.mcp.pom": "projects/testtr/example-tests/pom.xml",
  "testtr.mcp.browserChannel": "chrome"
}
```

`mavenHome` gerçek Maven kurulumunu gösterir; Wrapper'ın indirdiği Maven de
kullanılabilir. Başka bir tüketici projesi açıldığında JAR yollarını mutlak olarak
verin veya o workspace'e göre ayarlayın. Çok köklü workspace'te her klasörün
bağlantıları ve ayarları ayrıdır. JAR/POM/sayfa eşleme/akış yolları workspace'e;
`registry`, `sourceDirectory`, `mcp.helpers`, `mcp.requirements` ve
`mcp.reportsDirectory` test modülüne göredir. POM açıkça verilmezse açık dosyanın
en yakın POM'u kullanılır; dosya bağlamı yoksa POM seçimi sunulur. Birden çok modüllü
depoda `mcp.pom` ile test modülünü belirtmek önerilir.

| Komut (`TestTR:` önekiyle) | Davranış |
|---|---|
| MCP ile senaryoyu doğrula | Açık `.testtr` dosyasını mevcut derleyiciyle doğrular; dosya yazmaz. |
| MCP ile Java önizlemesi | Senaryo seçtirip Java'yı geçici editörde açar. |
| MCP ile değişiklik etkisini hesapla | `mcp.impactBase` revizyonuna göre Maven etki analizini çalıştırıp JSON sonucu açar. |
| MCP ile etkilenen testleri çalıştır | Güncel etki analizini ve `select test` koşumunu aynı Maven oturumunda çalıştırır. |
| MCP sonucundaki senaryoyu kaynakta aç | Son etki/koşum raporundaki senaryoları sonuçlarıyla listeler; seçilen `.testtr` kaynağını açar. |
| MCP ile JUnit hata analizini aç | Mevcut Surefire raporlarını tipli kanıtlarla sınıflandırır; test çalıştırmadan JSON analizini açar. |
| Discovery ile obje keşfini başlat | URL alır, görünür tarayıcı açar; tarayıcı kullanılırken kayıt yapar. Bildirimde **İptal**, keşfi durdurup varsa kısmi raporu açar. |
| Discovery raporundan Registry önerisi üret | Seçilen Discovery JSON'unu Registry ile karşılaştırır; öneriyi kaydeder ve aday seçimi sunar. |
| Registry önerisini incele | Adayın gerekçelerini listeler; seçileni önceki/önerilen JSON diff görünümünde açar. |
| Registry locator adaylarını doğrula | İki bağımsız tarayıcı context'inde doğrular; yeni öneri dosyası oluşturur. |
| Seçilen adaylardan Registry snapshot oluştur | Yalnızca açıkça seçilen, doğrulanmış ve uygulanabilir adayları yeni bir dosyaya aktarır. Mevcut Registry ayarını otomatik değiştirmez. |
| MCP bağlantılarını kapat | İki server'a EOF gönderir; sahip oldukları işleri iptal edip kaynaklarını temizlemelerini bekler. |

Önerilen akış: **keşif → Registry önerisi → diff inceleme → locator doğrulama →
seçilen adaylarla snapshot**. Snapshot'ın içeriğini inceleyip kullanmak istediğinizde
Registry yolunu ve POM'u kendiniz ayarlayın. Server, öneriden sonra Registry değişmişse
uygulamayı reddeder. Gerekçesi `review` olan ve iframe kapsamındaki adaylar otomatik
uygulanamaz.

Keşif raporları ve öneriler test modülünün `target/testtr-vscode/<kimlik>/` dizininde
kalır. Son rapor/öneri dosyası aynı oturumdaki dosya seçiminde başlangıç olarak sunulur.
Göreli Registry URL'leri için `testtr.mcp.baseUrl`, URL → sayfa eşlemesi için
`testtr.mcp.pageMap` verin. Giriş gibi hazırlık gerektiğinde `testtr.mcp.discoveryFlow`
ile Discovery akış JSON'unu seçin; locator doğrulamasında aynı akış iki kez uygulanır.

MCP önizlemesi POM okumaz. `testtr.profile=default` ise framework seçimi sunulur;
diğer seçimler doğrudan kullanılır. Gerçek etki analizi/koşum POM ayarlarını kullanır.
Özel Registry, yardımcı sözleşme, gereksinim veya kaynak dizinleri hem POM hem editör
ayarlarında uyumlu olmalıdır. Test komutu yalnızca etkilenen **TestTR** testlerini
çalıştırır; tam Maven koşumu için önceki **Maven testlerini çalıştır** komutunu kullanın.

Uzun işlemler iptal edilebilir bildirimle gösterilir. İptal isteğinden sonra terminal
durum beklenir; aynı workspace'te başka bir TestTR komutu temizlik bitmeden başlamaz.
Farklı VS Code pencereleri veya CLI'lar aynı `target` dizininde eşzamanlı çalışmamalıdır.
Bir protokol isteğinin 30 saniyelik zaman aşımında bağlantı kapatılır; sonucu belirsiz
işlem otomatik yinelenmez. Sonraki komut yeniden bağlanır. Eklenti kapanışı da server
kaynaklarının temizlenmesini ister. Ayarları değiştirirken çalışan işi önce bitirin.

Sonuç JSON'unda ham Maven logları veya JUnit sistem özellikleri gösterilmez. Başarısız
koşumun yerel log yolu sonuçta yer alır; log proje çıktıları ve hassas değerler içerebilir.
Boş sonuç listesi testlerin çalıştığı anlamına gelmez. `sourceDirectory` ayarı kaynak
gezinmesi için Maven yapılandırmasıyla aynı olmalıdır.

Koşum sonuçlarında `analysis` ve `analysisSummary` alanları da bulunur. Davranış,
locator, veri, ortam ve açık yeniden deneme kararsızlığı için gözlem sınıfları
sunulur; yeterli kanıt yoksa kategori `uncertain` olur. Kök neden doğrulanmış
sayılmaz ve otomatik bakım yapılmaz. Eski koşum desteğiyle üretilmiş kodsuz hatalar
da belirsiz kalır. Ayrıntılar [TestTR hata analizi rehberinde](../testtr/hata-analizi.md).

## Geliştirme kontrolleri

```powershell
npm ci
npm test
```

Bu komut TypeScript derlemesini, stdio protokolünü ve komutların VS Code API
sözleşmesi üzerindeki davranışını test eder; gerçek VS Code penceresi açmaz.
İki derlenmiş server ve Playwright Chromium hazırken gerçek süreç kabul testi:

```powershell
$env:TESTTR_REAL_MCP = '1'
$env:TESTTR_MAVEN_HOME = 'C:/tools/apache-maven-3.9.9'
npm test
```

Kabul testi iki server'ı aynı anda çalıştırır; keşif, öneri, tarayıcı doğrulaması,
ayrı snapshot, TestTR önizleme/etki/Maven sonuçları ve bağımsız iptali doğrular.
CI bu akışı Windows ve Ubuntu için çalıştıracak şekilde yapılandırılmıştır.
[MCP yaşam döngüsü](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle)
ve [VS Code API](https://code.visualstudio.com/api/references/vscode-api) kullanılır.
