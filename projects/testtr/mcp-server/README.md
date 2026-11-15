# TestTR MCP server

TestTR Maven reactor'ındaki bağımsız `testtr-mcp-server` modülü. Discovery'den ayrı
bir stdio süreci olarak çalışır. [VS Code eklentisi](../../testtr-vscode/README.md)
bu server ve Discovery için MCP istemci komutları sunar.

## Derleme ve başlatma

JDK 17 ve Maven 3.9.x gerekir. `projects/testtr` dizininde:

```powershell
./mvnw.cmd --batch-mode -pl maven-plugin,runner-support -am install
./mvnw.cmd --batch-mode -pl mcp-server -am -Pmcp-acceptance verify
java -jar mcp-server/target/testtr-mcp-server-0.1.0-SNAPSHOT.jar --root D:/dev/workspace/test-otomation-tools --maven-home C:/tools/apache-maven-3.9.9
```

`--maven-home`, içinde `boot/plexus-classworlds-*.jar` ve `bin/m2.conf` bulunan
gerçek Maven kurulum dizinidir. Maven Wrapper'ın indirdiği kurulum da kullanılabilir.
Unix'te derleme için `sh ./mvnw` kullanılır. `mcp-acceptance` profili gerçek Maven
alt süreçleriyle başarılı/başarısız koşum, etki analizi, boş seçim, iptal, zaman aşımı
ve server kapatılırken temizlik testlerini çalıştırır. İlk komut hedef Maven
işlemlerinin yerel depoda bulunmasını sağlar; tüketici projede eklenti yapılandırması
ve test bağımlılıkları normal TestTR kurulumundaki gibi olmalıdır.

`--maven-home` verilmezse doğrulama ve Java önizlemesi kullanılabilir; Maven araçları
açıklayıcı bir hata döndürür. `--allow-tools validate_scenario,generate_java` gibi
bir listeyle sunulan araçlar daraltılabilir.

## İstemci ayarı

MCP istemcisinin server ayarlarına eklenebilecek örnek (yolları kendi ortamına uyarla):

```json
{
  "mcpServers": {
    "testtr": {
      "command": "java",
      "args": [
        "-jar", "D:/dev/workspace/test-otomation-tools/projects/testtr/mcp-server/target/testtr-mcp-server-0.1.0-SNAPSHOT.jar",
        "--root", "D:/dev/workspace/test-otomation-tools",
        "--maven-home", "C:/tools/apache-maven-3.9.9"
      ]
    }
  }
}
```

İstemci önce `initialize`, sonra `notifications/initialized` gönderir. JSON-RPC
mesajları UTF-8 ve satır başına tek JSON biçimindedir; stdout yalnızca protokol
içindir. Discovery ve TestTR ayrı server adlarıyla aynı istemciye eklenebilir.
[MCP araç sözleşmesi](https://modelcontextprotocol.io/specification/2025-11-25/server/tools).

## Araçlar

| Araç | Girdiler ve sonuç |
|---|---|
| `validate_scenario` | `source`, `registry`; isteğe bağlı `helpers`, `requirements`, `framework`, `relativeSource`. Kaynağı ve çağırdığı ortak adımları mevcut derleyiciyle doğrular, kod üretimini kontrol eder; dosya yazmaz. |
| `generate_java` | Aynı girdilerle senaryo kimliklerini ve Java kaynaklarını yanıtta döndürür; `javac` veya tarayıcı çalıştırmaz. |
| `calculate_impact` | `pom`; isteğe bağlı `base` (varsayılan `HEAD`), `profile`, `timeoutSeconds`. Mevcut Maven `impact` hedefini çalıştırır ve iş kimliği döndürür. |
| `run_tests` | `pom`; isteğe bağlı `selection` (varsayılan `false`), `base`, `profile`, `timeoutSeconds`, `reportsDirectory`. Normal Maven `test`; `selection:true` ile aynı oturumda `select test`. |
| `job_status` | `jobId`. `queued`, `running`, `completed`, `failed`, `cancelled` veya `timed-out`; iptal isteği ve varsa çıkış kodu. |
| `stop_job` | `jobId`. İptal ister; terminal durum süreç temizliği tamamlanınca yayınlanır. |
| `read_results` | `jobId`. Terminal işin değişmez sonucunu, etki raporunu ve varsa yeni JUnit sonuç özetlerini döndürür. |

Dosya yolları `--root` dizinine göredir. Doğrulama/önizleme POM okumaz;
`registry` açıkça verilir, `helpers` ve `requirements` varsayılanları root altında
`src/test/helpers.json` ve `src/test/requirements.json` olur. Bir alt modülde bunları
da açık yollarla ver. Katalog mevcutsa gereksinim onayı/sürümü doğrulanır.
`framework`: `SELENIDE`, `SELENIUM` veya `PLAYWRIGHT` (varsayılan `SELENIDE`).
`relativeSource`, Maven'daki `sourceDirectory`'ye göre kaynak yoludur; aynı senaryo
kimlikleri ve Java için bunu ver. Atlanırsa yalnızca kaynak dosyasının adı kullanılır.

Örnek `tools/call` parametreleri:

```json
{
  "name": "generate_java",
  "arguments": {
    "source": "projects/testtr/example-tests/src/test/testtr/coklu.testtr",
    "relativeSource": "coklu.testtr",
    "registry": "projects/testtr/example-tests/src/test/registry",
    "helpers": "projects/testtr/example-tests/src/test/helpers.json",
    "requirements": "projects/testtr/example-tests/src/test/requirements.json",
    "framework": "PLAYWRIGHT"
  }
}
```

```json
{
  "name": "run_tests",
  "arguments": {
    "pom": "projects/testtr/example-tests/pom.xml",
    "selection": true,
    "base": "HEAD",
    "profile": "playwright",
    "timeoutSeconds": 600
  }
}
```

İş kimliğini `job_status` ile sorgula; terminal olduğunda `read_results` çağır.
Başarısız Maven/test koşumu `failed` olur; `completed`, testlerin gerçekten
çalıştığını tek başına kanıtlamaz (örneğin boş seçim). Etki analizi her çağrıda
yenilenir; eski seçim raporu kullanılmaz. Seçimli koşum elle yazılmış Java/Cucumber
testlerini kapsamaz. Ayrıntılar [etki analizi rehberinde](../etki-analizi.md).

## Koşum ve sonuç sınırları

- Tek server'da aynı anda bir Maven işi çalışır; ikinci istek reddedilir. CLI veya
  başka server'ın aynı `target` dizinini eşzamanlı kullanmaması gerekir. Server
  başına 64 iş tutulur; sonuçlar toplandıktan sonra server yeniden başlatılabilir.
- Zaman aşımı 1–3600 saniye (varsayılan 600). İptal ve server kapanışı yalnızca
  başlatılan Maven sürecini ve gözlenen alt süreçlerini kapatır. Harici CDP
  tarayıcılarına veya başka server işlerine erişmez. Kendini süreç ağacından
  ayıran özel proje servisleri ayrıca kendi temizlik sözleşmesini gerektirir.
- Etki raporu ve yerel Maven logu modülün `target/testtr-mcp/<jobId>/` dizinindedir.
  Log normal Maven çıktısıdır ve proje tarafından yazılan hassas veriler içerebilir;
  MCP sonucu ham logu, XML sistem özelliklerini, stdout'u veya hata metnini taşımaz.
- JUnit özetleri varsayılan olarak modülün `target/surefire-reports` dizininden
  okunur. POM farklı rapor dizini kullanıyorsa `reportsDirectory` ver. Yalnızca
  iş sırasında oluşturulan/değişen XML dosyaları alınır; boş liste başarılı test
  kanıtı sayılmaz. Tam XML hata kanıtları mevcut TestTR raporlama akışında kalır.
  Rapor başına 4 MB sınırı vardır; XML dış varlıkları kapalıdır.
- `pom` tek test modülünü göstermelidir; reactor/Failsafe koşumu desteklenmez.
  Maven hedefleri mevcut POM/profil ayarlarını kullanır. Maven doğrudan server'ın
  JDK'sıyla başlatılır; shell komutu, genel Maven argüman listesi, `MAVEN_OPTS` veya
  `.mvn/jvm.config` yüklemez. Özel JVM ayarlarına ihtiyaç duyan projeler bu sürümde
  ayrıca uyarlanmalıdır.
- Root bir güvenilir proje sınırıdır, kod sandbox'ı değildir. Açık girdi yollarında
  traversal ve root dışına çıkan symlink reddedilir; POM, Java yardımcıları ve
  derleyicinin çözdüğü importlar projenin güvenilir kodu olarak çalışır. Java
  önizlemesi kaynaklardaki sabit metinleri içerir. Sırlar mevcut sır adımlarıyla
  çalışma anında okunmalıdır.

VS Code istemcisinin komut/protokol testleri ve iki gerçek server'la keşif → Registry
snapshot → TestTR önizleme/koşum kabul testi bulunur. Gerçek Extension Host üzerinde
etkileşimli arayüz kontrolü ve iframe Registry desteği ayrıca bekleyen işlerdir.
