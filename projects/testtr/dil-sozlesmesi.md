# TestTR dil sözleşmesi

Dosyalar UTF-8 kodlamalıdır. LF ve CRLF satır sonları kullanılabilir. Boş satırlar ve satır başı girintileri yok sayılır. Her adım tek satırdır. Anahtar sözcükler, nokta ve iki nokta işaretleri örneklerdeki gibi yazılır. Çift tırnak içindeki metinler JSON dize kaçışlarını kullanır. Örneğin `"Çağrı \"A\" ve C:\\test"` metni iç tırnakları ve tek ters eğik çizgiyi korur. Tanı, dosya ve bir tabanlı satır numarasıyla gösterilir.

## Dosya, blok ve değerler

| Geçerli örnek | Reddedilen örnek veya durum | Java karşılığı |
|---|---|---|
| `"ortak/oturum.testtr" dosyası kullanılır.` | Döngüsel içe alma | Üretim öncesinde ortak tanımlar çözülür |
| `Senaryo: "Giriş".` ve `Senaryo sonu.` | Aynı dosyada tekrarlı senaryo adı | Ayrı JUnit testi |
| `Gereksinim: "REQ-GIRIS-001".` | Başlıktan sonra ilk satır dışında kullanılması veya boş kimlik | Surefire raporundaki TestTR üstverisi |
| `Her senaryodan önce:` ve `Hazırlık sonu.` | Senaryo içine yerleştirilen hazırlık bloğu | Test gövdesinden önce çalışır |
| `Her senaryodan sonra:` ve `Temizlik sonu.` | Kapanışı eksik temizlik bloğu | Hata durumunda da çalışır |
| `Ortak adım: "Giriş".` ve `Ortak adım sonu.` | Tekrarlı ortak adım adı | Çağrıya özel yardımcı metot |
| `"Ad" adlı metin alınır.` veya `"Parola" adlı gizli değer alınır.` | Gövdeden sonra yazılan parametre | Parametre türü derlemede denetlenir |
| `"Giriş" ortak adımı uygulanır.` | Zorunlu girdisi olan adımı girdisiz çağırma | Yardımcı metot çağrısı |
| `"Giriş" ortak adımı uygulanır:`, `"Ad" girdisine "deneme" verilir.` ve `Ortak adım çağrısı sonu.` | Eksik, fazla veya tekrarlı girdi | Girdiler çağrıya bağlanır |
| `"testtr.user" ayarı "Ad" değişkenine alınır.` | Koşumda bulunmayan sistem özelliği | `ScenarioContext.setting` |
| `"TEST_PASSWORD" sırrı "Parola" değişkenine alınır.` | Koşumda bulunmayan ortam değişkeni | `ScenarioContext.secret` |
| `"Ad" değişkeninin değeri` | Tanımlanmamış değişkene başvuru | `ScenarioContext.value` |
| `"Temizle" yardımcı işlemi çalıştırılır.` | Kayıtlı olmayan yardımcı işlem | Kayıtlı statik Java metodu |
| `"Oluştur" yardımcı işlemi çalıştırılır:`, `"Tür" girdisine "Bireysel" verilir.`, `Sonuç "Numara" değişkenine alınır.` ve `Yardımcı işlem çağrısı sonu.` | Yanlış girdi türü veya `void` işlemden sonuç alma | Kayıtlı Java metodu ve sonuç bağlama |

Blok başlıkları, girdiler ve kapanışlar ayrı satırlardadır. İçe alma ve ortak tanımlar blok dışında yazılır. İçe alınan dosyalar ortak tanımlar ve içe almalar içerir. Hazırlık ve temizlik dosya başına en fazla bir kez tanımlanır ve her senaryoda ayrı çalışır. Parametre veya yardımcı işlem girdisi, türü uygunsa `"Ad" değişkeninin değeri` biçiminde de verilebilir.

## Tarayıcı işlemleri

| Geçerli adım | Reddedilen örnek veya durum | Selenide / Playwright / Selenium |
|---|---|---|
| `"Giriş" sayfası açılır.` | Registry'de bulunmayan sayfa | `open` / `navigate` / `get` |
| `"Ad" alanına "Ada" yazılır.` | Alan yerine düğme kullanılması | `setValue` / `fill` / `clear` ve `sendKeys` |
| `"Ad" alanı temizlenir.` | Registry tipi `input` olmayan obje | `clear` / `fill("")` / `clear` |
| `"Ad" alanından sekme tuşuyla çıkılır.` | `"Ad" alanından çıkılır.` | `pressTab` / `press("Tab")` / `sendKeys(TAB)` |
| `"Kaydet" düğmesine tıklanır.` | `"Kaydet" tıklanır.` | `click` / `click` / `click` |
| `"Para Birimi" listesinden "Avro" seçilir.` | Tekil, etkin seçenek bulunamaması | `selectOption` / `selectOption` / `Select.selectByIndex` |
| `"Onay" onay kutusu işaretlenir.` | Obje tipi `checkbox` değil | `setSelected(true)` / `check` / işaretsizse `click` |
| `"Onay" onay kutusunun işareti kaldırılır.` | `"Onay" işareti kaldırılır.` | `setSelected(false)` / `uncheck` / işaretliyse `click` |
| `"Ad" alanının değeri "Okunan" değişkenine alınır.` | Değişkenin aynı kapsamda zaten tanımlı olması | Ortak DOM gözlemi ile `UiContract.capture` |

## Doğrulamalar

Üç hedef aynı Java doğrulamalarını kullanır. Sayfa doğrulaması tarayıcının mevcut adresini okur. Diğer doğrulamalar DOM gözlemini Selenide `executeJavaScript`, Playwright `evaluate` veya Selenium `executeScript` üzerinden alır. Karşılaştırma ve bekleme kuralları [README](README.md#ortak-tarayıcı-sözleşmesi) içindedir.

| Geçerli adım | Reddedilen veya başarısız örnek | Ortak Java işlemi |
|---|---|---|
| `"Giriş" sayfası açık olmalıdır.` | Adresin sorgu parametresi farklı | `expectUrl` |
| `"Mesaj" görünür olmalıdır.` | Obje gizli veya DOM'da yok | `expect("visible")` |
| `"Mesaj" görünür olmamalıdır.` | Obje görünür | `expect("hidden")` |
| `"Mesaj" metni "Tamamlandı" olmalıdır.` | Görünen metin `tamamlandı` | `expect("text")` |
| `"Mesaj" metni "Tamam" ifadesini içermelidir.` | Görünen metin `İptal edildi` | `expect("contains")` |
| `"Tutar" alanının değeri "1000" olmalıdır.` | Alan değeri ` 1000 ` | `expect("value")` |
| `"Tutar" alanı boş olmalıdır.` | Alan değeri tek boşluk | `expect("value")` ile boş dize |
| `"Kaydet" düğmesi etkin olmalıdır.` | Düğme devre dışı | `expect("enabled")` |
| `"Kaydet" düğmesi devre dışı olmalıdır.` | Düğme etkin | `expect("disabled")` |
| `"Para Birimi" listesinde "Avro" seçili olmalıdır.` | Başka seçenek seçili | `expect("selected")` |
| `"Onay" onay kutusu işaretli olmalıdır.` | İşaret kaldırılmış | `expect("checked")` |
| `"Onay" onay kutusu işaretsiz olmalıdır.` | İşaret duruyor | `expect("unchecked")` |

Registry'de `pages` altında her sayfanın `url` ve `objects` alanları bulunur. Obje için `type` ve boş olmayan `css` alanı kullanılır. Üç hedefin ortak locator türü CSS'tir.

Registry dizininin doğrudan altındaki `.json` dosyaları birleştirilir. Önce temel dosyalar, ardından `.override.json` dosyaları yüklenir. Her grup dosya adına göre sıralanır. Aynı sayfa birden fazla dosyada yer aldığında iç içe JSON nesneleri alan alan birleştirilir. Çakışan değerlerde son dosya geçerlidir.

Örneğin `giris.override.json` içinde yalnızca bir objenin `css` değeri verilirse temel dosyadaki `url`, obje tipi ve diğer objeler korunur. [Registry örneği](example-tests/src/test/registry) · [Senaryolar](example-tests/src/test/testtr)
