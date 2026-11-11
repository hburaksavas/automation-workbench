# TestTR

Türkçe senaryoları Maven test yaşam döngüsünde Java 17 testlerine dönüştüren başlangıç uygulaması.

Desteklenen cümle kalıpları, hata örnekleri ve Java karşılıkları [dil sözleşmesinde](dil-sozlesmesi.md) yer alır.

İsteğe bağlı [gereksinim kataloğu](gereksinimler.md), senaryo bağlantılarını onay durumuyla doğrular ve gereksinim sürümünü JUnit raporuna taşır.

[AI taslak üretimi](ai-taslaklar.md), `testtr:draft` Maven hedefiyle onaylı gereksinimlerden ayrı inceleme dosyaları üretir ve mevcut derleyiciyle doğrular.

[Değişiklik etkisi analizi](etki-analizi.md), `testtr:impact` Maven hedefiyle Git değişikliklerinden gerekçeli TestTR senaryo listesi çıkarır. JavaScript ve TypeScript kaynakları açık dosya kalıplarıyla sayfalara eşlenir.

İsteğe bağlı `imports` ayarıyla sayfalardan bileşenlere import zincirleri takip edilir; `testtr:select test` yalnızca etkilenen TestTR senaryolarını çalıştırır. Çözülemeyen bağlantılar geniş seçime neden olur.

[VS Code eklentisi](../testtr-vscode/README.md) adım şablonları, Registry ve ortak adım önerileri, Maven tanıları ve üretilen Java önizlemesi sağlar.

## Çalıştırma

Bağımsız bir Maven projesiyle başlamak için [ilk test rehberini](ilk-test.md) kullan.

JDK 17 ve Google Chrome kurulmalıdır. JAVA_HOME JDK 17 dizinini göstermelidir. Maven Wrapper ilk çalışmada Maven ve bağımlılıkları indirir.

```powershell
./mvnw.cmd clean test
./mvnw.cmd clean test -Pselenium
./mvnw.cmd clean test -Pplaywright
```

Unix ortamında aynı seçeneklerle `./mvnw` kullanılır. Tarayıcılar headless çalışır. Playwright örneği kurulu Google Chrome'u kullanır. İlk koşumda Playwright tarayıcı paketlerinin indirilmesini atlamak için `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` ortam değişkeni kullanılabilir.

GitHub Actions iş akışı Windows ve Ubuntu'da JDK 17 ile üç profil için ayrı ayrı `clean verify` çalıştıracak şekilde yapılandırılmıştır.

Her matris işi, oluşan TestTR Surefire XML raporlarını kendi çıktısı olarak yükler. Dışa aktarılan kopyalar JUnit sonuçlarını, maskeli hata tanılarını ve TestTR koşum kayıtlarını içerir. JVM özellikleri, standart hata akışı ve TestTR kaydı dışındaki standart çıktı satırları çıkarılır.

## Mevcut kapsam

- Aynı dosyada birden fazla bağımsız senaryo, sayfa açma, alan doldurma, düğmeye tıklama.
- Alan temizleme, sekme tuşu, yerel HTML listesinde seçim ve onay kutusu işlemleri.
- Tam/kısmi metin, alan değeri, görünürlük, düğme etkinliği ve seçim doğrulamaları.
- Ortak adım dosyalarını içe alma, metin parametreleri ve iç içe ortak adım çağrıları.
- Her senaryodan önce hazırlık ve hata sonrasında da çalışan temizlik blokları.
- Çalışma zamanı ayarları, ortamdan sır okuma, alan değerini değişkene alma ve değişkenleri ortak adımlara aktarma.
- Kayıtlı Java yardımcı işlemleri, tür kontrolü yapılan girdiler, sonuç değişkeni ve senaryoya ait koşum kimliği.
- Kullanıcının JSON dosyasında tanımladığı sayfa, obje tipi ve CSS locator bilgisi.
- Selenide, Playwright Java ve Selenium WebDriver için doğrudan Java çağrıları üretimi.
- Maven generate-test-sources entegrasyonu ve JUnit kaynak satırı eşleme.

Örnekler `example-tests/src/test/testtr`, sayfa bazındaki Registry dosyaları `example-tests/src/test/registry/` içindedir. Üretilen Java, ilgili modülün `target/generated-test-sources/testtr` dizinine yazılır. Senaryo dosyası kalıcı kaynaktır. Örnek HTML ekranı giriş butonunun davranışını taklit eder.

Varsayılan Registry yolu `src/test/registry/` dizinidir. Dizin bulunamazsa Maven ve VS Code `src/test/registry.json` dosyasını kullanır. Maven POM'unda `<registry>` ile, VS Code'da `testtr.registry` ayarıyla farklı bir dosya veya dizin seçilebilir.

## Geliştirme durumu

Ortak adımların örneği `example-tests/src/test/testtr/ortak/oturum.testtr` içindedir. Çağrılarda girdiler açıkça verilir. Eksik, fazla veya tekrarlı girdiler ve döngüsel çağrılar derleme hatası oluşturur. Derleyici ortak adımları çağrı bağlamında doğrular ve her çağrı için özelleştirilmiş bir Java metodu üretir. İç içe çağrılar ayrı metotlar olarak korunur. Hata mesajında Türkçe çağrı zinciri yer alır.

Temizlik bloğunda UI işlemlerinden önce sayfa açıkça açılır. Hazırlık veya senaryo başarısız olsa da temizlik denenir. Temizlik de başarısızsa asıl hata korunur ve temizlik hatası suppressed exception olarak raporlanır.

Parametreler sabit metin veya çalışma zamanı değişkeni olabilir. Tanınmayan kalıplar Maven kaynak üretimini durdurur.

## Ortak tarayıcı sözleşmesi

Selenide, Selenium ve Playwright aynı DOM gözlemini ve doğrulama kurallarını kullanır. Doğrulamalar koşul sağlanana kadar 25 ms arayla yeniden gözlem yapar. Varsayılan bekleme süresi 5000 ms'dir ve `testtr.timeoutMillis` ile ayarlanabilir.

- Tam ve kısmi metin karşılaştırmaları büyük-küçük harfe duyarlıdır. Art arda gelen boşluklar tek boşluğa indirilir, baştaki ve sondaki boşluklar kaldırılır. Alan değerleri boşluklar dahil birebir karşılaştırılır.
- Görünmezlik doğrulaması, obje gizliyse veya locator hiç eşleşmiyorsa geçer. Birden çok eşleşme hata verir. Etkinlik kontrolü HTML devre dışı durumunu ve `aria-disabled="true"` değerini dikkate alır.
- Tek seçimli HTML listesinde seçenek, boşlukları normalleştirilmiş görünen metniyle belirlenir. Seçim için aynı etiketin tek bir etkin seçeneğe karşılık gelmesi gerekir.
- `"Sözleşme" sayfası açık olmalıdır.` adımı mevcut tarayıcı adresini Registry'deki adresle karşılaştırır. Göreli adres `testtr.baseUrl` üzerinden çözülür. Şema ve sunucu adı büyük-küçük harfe duyarsızdır. Varsayılan HTTP ve HTTPS portları eşdeğer sayılır. Yol, son eğik çizgi, sorgu ve fragment karşılaştırmaya dahildir.

`sozlesme.testtr` örneği adresi, metin ve alan değeri karşılaştırmalarını, gecikmeli görünürlük ve etkinliği üç profilde aynı senaryoyla sınar.

## Aynı dosyada birden fazla senaryo

`coklu.testtr` iki senaryo, ortak hazırlık ve ortak temizlik içerir. Her senaryo ayrı JUnit sınıfında, yeni tarayıcı oturumu ve yeni ScenarioContext ile çalışır. Hazırlık ve temizlik her senaryo için uygulanır. Bir senaryonun başarısız olması diğerinin koşumunu durdurmaz. Değişken adları farklı senaryolarda tekrar kullanılabilir.

Aynı dosyada senaryo adları benzersiz olmalıdır. Üretilen sınıf kimliği dosyanın göreli yolu ve senaryo adından türetilir. Senaryoların sırasını değiştirmek kimliği değiştirmez. Silinen veya yeniden adlandırılan senaryoların üretilen kaynak ve sınıf dosyaları sonraki kaynak üretiminde kaldırılır. Tek senaryo isteyen Java API çağrıları `parse`, dosyanın tüm senaryolarını isteyenler `parseAll` kullanır.

Senaryo başlığının hemen altına isteğe bağlı `Gereksinim: "REQ-GIRIS-001".` satırı eklenebilir. Üretilen her test, Surefire XML raporunun `system-out` bölümüne `TESTTR ` önekli bir JSON kaydı yazar. Kayıtta `scenarioId`, göreli `source` ve tanımlanmışsa `requirement` bulunur. Gereksinim bağlantısını değiştirmek senaryo kimliğini değiştirmez.

Maven ile üretilen testlerin kayıtlarında `registrySha256`, `compilerVersion` ve `framework` alanları da bulunur. Derleyici ve Maven eklentisi aynı proje sürümüyle paketlenir. GitHub Actions, çalıştırdığı revizyonu `codeRevision` alanına taşır. Yerelde aynı alanı eklemek için `-Dtesttr.codeRevision=<tam Git commit kimliği>` kullanılır. Revizyon verilmezse alan yazılmaz.

## Çalışma zamanı değerleri

```text
"testtr.user" ayarı "Kullanıcı" değişkenine alınır.
"TEST_PASSWORD" sırrı "Parola" değişkenine alınır.
"Kullanıcı Adı" alanına "Kullanıcı" değişkeninin değeri yazılır.
"Kullanıcı Adı" alanının değeri "Okunan kullanıcı" değişkenine alınır.
```

Ayarlar Java sistem özelliklerinden, sırlar ortam değişkenlerinden koşum anında okunur. Eksik değer ilgili Türkçe satırda hata verir. Değişkenler bir kapsamda bir kez tanımlanır. Ortak adımın yerel değişkenleri her çağrı için ayrılır. Gizli parametreler `"Parola" adlı gizli değer alınır.` ile tanımlanır ve gizli değişken ister.

Her senaryoya ayrı `ScenarioContext` oluşturulur. Hazırlık, senaryo ve temizlik aynı koşum kimliğini kullanır. Temizlik hazırlığın kısmen tamamlanmış olabileceğini dikkate almalıdır.

Senaryo yürütme aşamasına girildiğinde Surefire XML raporuna ayrıca `TESTTR_RUN ` önekli bir JSON kaydı yazılır. `runId` yardımcı işlemlerin kullandığı koşum kimliğidir. `cleanupStatus`, temizlik tamamlandıysa `passed`, hata verdiyse `failed`, çalıştırılacak temizlik adımı yoksa `not_defined` değerini alır. Tarayıcı kurulumu yürütme aşamasından önce hata verirse bu kayıt oluşmaz.

Playwright kabul kontrolünde senaryo ve temizlik doğrulamaları birlikte başarısız olduğunda rapor birincil hatayı ve `Suppressed` temizlik hatasını ayrı gösterdi. Aynı raporda koşum kimliği ve `cleanupStatus: failed` doğrulandı.

Sır okunduktan sonra üretilen adımlar hata nedenlerini JUnit'e aktarırken ayrıntıları gizler ve Türkçe kaynak konumunu korur. Selenide ekran görüntüsü ve sayfa kaydı kapalıdır. Projeye özel yardımcı işlemler kendi günlüklerinde sırları korumaktan sorumludur. Örnek POM yalnızca yerel HTML örneği için herkese açık bir deneme parolasını test sürecinin ortamına verir.

CI, `-Dtesttr.redactDiagnostics=true` ile üretilen adımların hata ayrıntılarını sınırlar. Süresi dolan DOM doğrulamalarında Türkçe kaynak satırı, işlem türü, eşleşme sayısı, görünürlük, etkinlik ve işaret durumu kalır. Beklenen ve gözlenen metinler ile alan değerleri gizlenir. Sır kullanan adımlarda yalnızca kaynak konumu ve maskeleme bildirimi gösterilir.

## Java yardımcı işlemleri

Test projesinin `src/test/helpers.json` dosyası çağrılabilen metotları ve imzalarını tanımlar.

```json
{
  "Test müşterisi oluştur": {
    "method": "dev.testtr.FixtureHelpers.createCustomer",
    "inputs": {"Müşteri türü": "text"},
    "result": "text"
  }
}
```

Metot `public static` olmalıdır. İlk parametre `ScenarioContext`, ikinci parametre `Map<String, String>` olur. Sonuç türü `text` veya `secret` için Java dönüş tipi `String`, `void` için `void` kullanılır. Girdi türleri `text` ve `secret` olabilir. Şema ve DSL girdileri kaynak üretiminde, gerçek Java metot imzası test derlemesinde doğrulanır. Yardımcı işlemler çalışma hatalarını RuntimeException ile bildirebilir.

```text
"Test müşterisi oluştur" yardımcı işlemi çalıştırılır:
  "Müşteri türü" girdisine "Bireysel" verilir.
  Sonuç "Müşteri numarası" değişkenine alınır.
Yardımcı işlem çağrısı sonu.
```

`veri.testtr` örneği veriyi yardımcı işlemle üretir, alan üzerinden geri okur ve koşum kimliğiyle temizler. `sir.testtr` ortamdan alınan parolayı ortak adıma aktarır.

## Doğrulama

Statik import grafiği eklendikten sonraki Windows Selenide `clean verify` koşumunda 65 test geçti: 39 derleyici/katalog/taslak/etki/import testi, 8 Maven/API/seçim testi, 10 koşum desteği testi ve örnek modüldeki 8 test. Göreli importlar, re-export zincirleri, index dosyaları, açık alias/harici paket ayarları, döngüler ve belirsizlikte geniş seçim sınandı.

Etki seçimiyle koşum eklendikten sonraki Windows Selenide `clean verify` koşumunda 57 test geçti: 31 derleyici/katalog/taslak/etki testi, 8 Maven/API/seçim testi, 10 koşum desteği testi ve örnek modüldeki 8 test. Bağımsız Git/Maven kabul projesinde boş seçimde sıfır test, hedefli seçimde bir test, geniş seçimde iki test ve kasıtlı başarısız senaryoda Maven hata sonucu doğrulandı. İlgisiz Java testi bu açık seçim koşumlarına dahil edilmedi. Windows Git satır sonu uyarılarının dosya listesine karışması da düzeltildi.

Etki analizi eklendikten sonraki Windows Selenide `clean verify` koşumunda 54 test geçti: 31 derleyici/katalog/taslak/etki testi, 5 Maven/API testi, 10 koşum desteği testi ve örnek modüldeki 8 test. Etki seçimi; JavaScript/TypeScript eşlemeleri, iç içe ortak çağrılar, yaşam döngüsü, belirsiz değişiklikler ve geçici Git deposundaki dosya değişiklikleriyle sınandı. Örnek projede gerçek `testtr:impact` komutu 7 senaryolu gerekçeli rapor üretti.

AI taslak akışı eklendikten sonraki Windows Selenide `clean verify` koşumunda 45 test geçti: 24 derleyici/katalog/taslak testi, 3 API istemcisi testi, 10 koşum desteği testi ve örnek modüldeki 8 test. API istemcisi yerel HTTP sunucusuyla sınandı; canlı model çağrısı yapılmadı. Çevrimdışı Maven kabul kontrolü bağımsız projede taslak üretimini, kaynak izolasyonunu ve sabit kabul örneğinin Chrome'da başarılı koşumunu doğruladı.

Gereksinim kataloğu eklendikten sonraki Windows doğrulamasında Selenide `clean verify` ile 36 test geçti: 18 derleyici/katalog testi, 10 koşum desteği testi ve örnek modüldeki 8 test. Onaysız gereksinimle Maven koşumu `giris.testtr:4 [DSL]` tanısıyla kaynak üretiminde durdu. Başarılı koşumun JUnit kaydında gereksinim sürümü ve katalog SHA-256 özeti doğrulandı.

JDK 17.0.18 ve yerel Chrome ile Maven profilleri doğrulanır. Tarayıcı senaryoları ortak giriş ve hazırlık/temizlik akışını, alan işlemlerini, çalışma zamanı verisini ve sır aktarımını kapsar. Derleyici testleri girdileri, döngüleri, türleri ve kaynak konumlarını doğrular. Koşum desteği testleri senaryo izolasyonunu ve hata zincirinde sır gizlemeyi sınar.

Son Windows doğrulamasında Selenide, Selenium ve Playwright profillerinin her birinde `clean verify` ile 33 test geçti. Bu sayı yedi tarayıcı senaryosunu, mevcut Java birim testini, 15 derleyici testini ve 10 koşum desteği testini kapsar. Önceki Playwright doğrulamasında çoklu dosyanın ilk senaryosu kasıtlı bozulduğunda ikinci senaryo geçti ve koşum verisi temizlendi. İlk senaryo dosyadan çıkarıldığında sonraki Maven koşumu başarılı oldu ve eski Java kaynak/sınıf dosyaları clean gerektirmeden kaldırıldı.

Önceki parola ve test verisi hata kontrollerinde Maven başarısız oldu, koşum verisi temizlendi ve üretilen Java ile Surefire raporlarında deneme parolası bulunmadı.

`sozlesme.testtr` dosyasının 4. satırındaki beklenen metin geçici olarak değiştirildiğinde üç profil de Surefire raporunda `sozlesme.testtr:4` konumunu gösterdi. Üç koşumda da mevcut Java birim testi geçti.

Aynı satırda eksik Registry kaydı ve desteklenmeyen adım kalıbı ayrı ayrı denendi. Her iki hata da `generate-test-sources` aşamasında `sozlesme.testtr:4 [DSL]` tanısıyla derlemeyi durdurdu. Örnek modülün Surefire aşaması başlamadı.

Playwright ile `veri.testtr` dosyasının 18. satırındaki beklenen müşteri numarası geçici olarak değiştirildi. Surefire raporu aynı kaynak satırını ve yardımcı işlemin oluşturduğu numaranın alana yazıldığını gösterdi. Başarısız koşumun ardından `example-tests/target/testtr-data` dizini boştu.

Ayrı bir Playwright denemesinde senaryo ve temizlik bloklarına kasıtlı doğrulama hataları verildi. Surefire raporu senaryo hatasını `veri.testtr:18` konumunda birincil hata, geçici temizlik adımının hatasını `veri.testtr:24` konumunda `Suppressed` olarak gösterdi.

Hazırlıkta müşteri oluşturulduktan sonra tanımsız bir ortam sırrı geçici olarak okundu. Playwright koşumu hazırlık adımında durdu. Surefire raporu `veri.testtr:12` konumunu gösterip hata ayrıntılarını gizledi. Temizlikten sonra test verisi dizini boştu.

Ortak giriş adımındaki doğrulama geçici olarak bozulduğunda Playwright raporu hem çağrı hem hata konumunu `giris.testtr:4 → ortak/oturum.testtr:5` zincirinde gösterdi. Ortak giriş çağrısı `giris.testtr` içinde senaryodan, `veri.testtr` içinde hazırlık bloğundan yapılır.

Yerel Chrome 157 ile Selenium 4.29.0 CDP sürüm eşleşmesi uyarısı verdi. Örnek WebDriver işlemleri başarılı oldu.
