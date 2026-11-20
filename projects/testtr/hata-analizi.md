# Kanıta dayalı JUnit hata analizi

İlk hata analizi sürümü mevcut JUnit/Surefire XML raporlarını okur. Sonuç, gözlenen
hata türünün sınıflandırmasıdır; kök neden tanısı değildir. Test sonucu, beklenen
değer, Registry ve senaryo kaynakları değiştirilmez. Her sınıflandırmada
`rootCause: "unconfirmed"` ve `automaticMaintenanceAllowed: false` bulunur.

## Kullanım

`projects/testtr` altında bileşenleri kurun:

```powershell
./mvnw.cmd --batch-mode -pl maven-plugin,runner-support -am install
```

Test modülünde önce normal test komutunu çalıştırın. Koşum başarısız olsa da mevcut
raporları daha sonra ayrı bir komutla inceleyebilirsiniz:

```powershell
./mvnw.cmd -f example-tests/pom.xml dev.testtr:testtr-maven-plugin:0.1.0-SNAPSHOT:analyze
```

Varsayılan girdi `${project.build.directory}/surefire-reports`, çıktı
`${project.build.directory}/testtr-analysis.json` olur. `testtr.analysisReports`
ve `testtr.analysisOutput` ile değiştirilebilir. Unix'te `sh ./mvnw` kullanın.
Hedef test çalıştırmaz; analiz üretiminin başarılı olması testlerin geçtiği anlamına
gelmez. Eksik rapor dizini hatadır; mevcut ama boş dizin sıfır test olarak raporlanır.
Girdi JUnit dosyasının üzerine analiz çıktısı yazılamaz.

İç içe `testsuite` ve `testsuites` paketleri belge sırasıyla okunur. Çıktıdaki
`suites` listesi düz tutulur; her paketin yalnızca doğrudan içerdiği testcase'ler
sayılır. Üst paketlerdeki toplamlar testleri ikinci kez saydırmaz. Boş
`testsuites` kapsayıcısı sıfır test olarak raporlanır.

TestTR MCP server'ında `read_results`, yeni koşumun JUnit sonuçlarına aynı analizi
ekler. `analyze_results` aracı, `reportsDirectory` ile açıkça seçilen mevcut raporları
Maven kurulumu gerektirmeden okur. VS Code'da **TestTR: MCP ile JUnit hata analizini
aç** komutu, test modülünün `mcp.reportsDirectory` ayarını (varsayılan
`target/surefire-reports`) kullanır. Sonuçtan kaynağa dönüş listesinde de mevcut
koşum sınıflandırması gösterilir.

## Tanı sözleşmesi

Koşum desteği, UI gözlemleri ve eksik çalışma zamanı girdileri için tipli hatalar
üretir. `ScenarioContext.failure` bunları `ScenarioFailure` ile kaynak satırına
bağlar. JUnit hata türü `dev.testtr.runtime.ScenarioFailure`, hata mesajının sonu da
`[TESTTR_DIAGNOSTIC:<KOD>]` olur. Bu işaret yalnızca enum kodunu içerir; locator,
beklenen/gözlenen değer, parola veya sır içermez. Sır kullanılan koşumlarda asıl
neden zinciri gizlenir; tanı kodu korunur. İç içe ortak adımlar kodu taşır.

| Gözlenen kanıt | Kategori | Yorum |
|---|---|---|
| `ASSERTION_MISMATCH`, `URL_MISMATCH` | `application-behavior` | Gözlenen davranış beklentiden farklıdır. Gereksinimin, test verisinin ve ortamın doğruluğu ayrıca incelenir. |
| `LOCATOR_AMBIGUOUS` | `locator` | Locator birden fazla öğe bulmuştur. DOM'un değişmesi de bu duruma neden olabilir; otomatik onarım izni değildir. |
| `DATA_MISSING` | `test-data` | Gerekli ayar, sır, değişken veya yardımcı işlem sonucu sağlanamamıştır. |
| `ENVIRONMENT_FAILURE` | `environment` | Tanınan tarayıcı oturumu/sürücü veya JVM uyumluluk hatası vardır. |
| `ELEMENT_MISSING`, `ELEMENT_TYPE_MISMATCH`, `ACTION_NOT_READY`, `OPTION_*` | `uncertain` | Locator, uygulama, veri veya ortam nedenlerini ayıracak yeterli kanıt yoktur. |
| Aynı JUnit testcase içinde başarısız denemeden sonra başarıyı bildiren `flakyFailure`/`flakyError` | `unstable-run` | Sonuç yeniden denemede değişmiştir. Nedenin uygulama, veri veya ortam olduğu bilinmez. |
| Genel assertion/timeout, eski sürümün kodsuz hatası, birden fazla birincil hata | `uncertain` | Serbest hata metninden neden tahmini yapılmaz. |

Ortam için izin verilen tam exception türleri: Selenium `SessionNotCreatedException`,
`org.openqa.selenium.remote.NoSuchDriverException`, JVM `UnsupportedClassVersionError`
ve `UnsatisfiedLinkError`. Genel `PlaywrightException`, `TimeoutException`, bağlantı
ve assertion mesajları kendi başlarına sınıflandırma kanıtı değildir.

Sınıflandırıcı yalnızca birincil JUnit `failure`/`error` öğesinin türünü ve tanı kodunu
kullanır. Suppressed temizlik hatası birincil hatanın kategorisini değiştirmez.
Başarısız kalan yeniden denemeler (`rerunFailure`) tek başına kararsızlık kanıtı
sayılmaz. Farklı rapor dosyalarındaki başarılı/başarısız sonuçlar da aynı girdi ve
koşulları kanıtlamadığından birleştirilerek kararsızlık tanısı konmaz.

## Sonuç ve sınırlar

Her testcase önceki `status` alanını korur ve bir `analysis` nesnesi alır:

```json
{
  "category": "uncertain",
  "certainty": "insufficient-evidence",
  "rootCause": "unconfirmed",
  "automaticMaintenanceAllowed": false,
  "evidenceCodes": ["ELEMENT_MISSING"],
  "possibleCategories": ["application-behavior", "locator", "test-data", "environment"]
}
```

Raporun `analysisSummary` alanı test, başarısızlık, açık yeniden deneme kararsızlığı
ve belirsiz sonuç sayılarını içerir. MCP yeni koşumda yalnızca o iş sırasında
oluşturulan/değişen raporları analiz eder. Yeni test kanıtı olmadan başarısız olan
veya zaman aşımına uğrayan MCP işi `executionAnalysis.category: "uncertain"` döndürür.
Mevcut rapor dizinini okuyan Maven hedefi ve `analyze_results`, dosyaların farklı
koşumlardan gelebileceğini `reportScope` alanında belirtir.

Her testcase'in `retryFailures` listesi, doğrudan içerdiği `flakyFailure`,
`flakyError`, `rerunFailure` ve `rerunError` kayıtlarını XML sırasıyla gösterir.
Her kayıtta `kind` ve aynı tipli kanıt kurallarıyla üretilmiş `analysis` bulunur;
ham hata ayrıntıları aktarılmaz. Kayıt yoksa liste boştur. Bu kayıtların analizi
testcase'in nihai `status` ve `analysis` alanlarını değiştirmez. Örneğin yeniden
denemede geçen bir test `unstable-run` kalırken önceki hatanın gözlenen kategorisi
ayrıca görülebilir. Başarısız kalan yeniden denemeler kararsızlık sayılmaz.

`analysisSummary.retryFailureCount`, bu dört türdeki XML kayıtlarının toplamıdır;
ilk başarısızlık, başarılı denemeler veya toplam çalıştırma sayısı değildir.
`testCount`, `failedCount`, `flakyCount`, `uncertainCount` ve `categories` yalnızca
testcase sonuçlarını sayar; yeniden deneme kanıtları bu sayıları şişirmez.

Ham hata mesajı, stack trace, JUnit sistem özellikleri, stdout/stderr ve değerler
analiz çıktısına aktarılmaz. Test/sınıf adları ve suite özetleri normal sonuç
kimlikleri olarak korunur; adlara sır yazılmamalıdır. Tam hata kanıtları yerel JUnit
raporunda kalır. Dış XML varlıkları/DOCTYPE kapalıdır; rapor başına 4 MB, analiz
başına 1000 XML dosyası sınırı vardır. Bu sınır veya bozuk XML analiz hatasıdır;
başarılı/boş sonuç gibi gösterilmez.

Bu ilk sürümde Registry onarımı, beklenen değer güncellemesi veya yeniden koşum
otomasyonu yoktur. Locator önerileri mevcut Discovery doğrulama/inceleme akışıyla
ayrıca ele alınır. Gerçek gereksinime aykırı ürün davranışı başarısız kalır.
