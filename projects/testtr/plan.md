# Türkçe UI test otomasyonu geliştirme planı

## Faz 1 — Çalışan proje

**Uygulama durumu.** Maven entegrasyonu, JSON Registry ve üç framework için ekran işlemleri çalışıyor. Dosya içe alma, parametreli ortak adımlar ve hazırlık/temizlik blokları eklendi. Çalışma zamanı değişkenleri, ayar ve sır okuma, tip kontrollü Java yardımcı işlem çağrıları ve senaryo başına koşum bağlamı uygulanmıştır. Aynı dosyada birden fazla senaryo desteklenir. Ortak adımlar çağrı bağlamına göre özelleştirilmiş Java metotları olarak üretilir. Sayfa adresi ve DOM doğrulamaları üç hedefte ortak kuralları kullanır. Güncel kapsam ve koşum bilgileri README.md, örnekler ve Java karşılıkları dil-sozlesmesi.md içindedir.

İlk teslimde kullanıcı UTF-8 bir `.testtr` dosyasına tam Türkçe cümlelerle senaryo yazar ve kendi Object Registry dosyasını test projesine ekler. Projenin normal Maven test yaşam döngüsü Türkçe senaryoları Java'ya dönüştürür, derler ve POM'da seçilen Selenide, Playwright Java veya Selenium WebDriver hedefiyle tarayıcıda çalıştırır.

### 1. Java projesini kur

**Yerleşim.** Maven kök projesinde `compiler`, `maven-plugin`, `runner-support` ve `example-tests` modülleri kullan. Derleyici dosya girdilerini ve tanıları yönetir. Maven eklentisi derleyiciyi test kaynakları üretilirken çağırır. Koşum desteği kaynak konumlarını, sır okumayı ve yardımcı işlem çağrılarını sağlar. Örnek test modülü üretilen kaynakları Maven test kaynaklarına ekler.

**Bağımlılık yönü.** Maven eklentisi derleyiciyi kullanır. Üretilen Java testleri koşum desteğine, JUnit 5'e ve seçilen framework'e bağlanır. Derleyicinin bir tarayıcıya veya test projesine erişmesi gerekmez.

**Çıkış ölçütü.** Windows'ta `mvnw.cmd clean verify`, Unix ortamında `./mvnw clean verify` JDK 17 ile çalışır. Örnek test modülü Maven eklentisini POM üzerinden kullanır. Windows doğrulaması tamamlandı. Ubuntu koşumu, GitHub Actions matrisi bir depoda çalıştırıldığında doğrulanacak.

- [x] Java 17 ve Maven Wrapper ile Windows ve CI üzerinde kullanılabilen bir proje kur. Derleme hedefini Java 17 olarak sabitle.
- [x] Java 17 ile uyumlu JUnit 5, Selenide, Playwright Java ve Selenium WebDriver sürümlerini seç ve sabitle. İlk tarayıcı hedefi Chrome olsun.
- [x] Derleyici çekirdeğini Maven entegrasyonundan ayır ve Java kütüphanesi olarak paketle.

### 2. Türkçe dil sözleşmesini yaz

**Belge düzeni.** Dosya başında kullanımlar, ardından ortak adımlar, hazırlık, senaryolar ve temizlik gelir. Hazırlık ve temizlik dosya başına en fazla bir kez tanımlanır. Bir dosya birden fazla senaryo içerebilir. Girintiler okunabilirlik içindir. Adım başına bir satır, cümle sonunda nokta ve blok başlıklarında tanımlı ayraç kullanılır. Çift tırnak içindeki değerlerde tırnak ve ters eğik çizgi kaçışları desteklenir.

| İşlev | Standart kalıp örnekleri |
|---|---|
| Dosya ve blok | `"ortak/oturum.testtr" dosyası kullanılır.` · `Senaryo: "Kredi açılır".` · `Senaryo sonu.` |
| Yaşam döngüsü | `Her senaryodan önce:` · `Hazırlık sonu.` · `Her senaryodan sonra:` · `Temizlik sonu.` |
| Ortak adım | `Ortak adım: "Uygulamaya giriş yap".` · `"Kullanıcı adı" adlı metin alınır.` · `"Parola" adlı gizli değer alınır.` · `Ortak adım sonu.` |
| Çağrı | `"Uygulamaya giriş yap" ortak adımı uygulanır:` · `"Parola" girdisine "Parola" değişkeninin değeri verilir.` · `Ortak adım çağrısı sonu.` |
| Yardımcı işlem | `"Test müşterisi oluştur" yardımcı işlemi çalıştırılır:` · `"Müşteri türü" girdisine "Bireysel" verilir.` · `Sonuç "Müşteri numarası" değişkenine alınır.` · `Yardımcı işlem çağrısı sonu.` |
| Değer alma | `"Kullanıcı adı" ayarı "Kullanıcı" değişkenine alınır.` · `"Parola" sırrı "Parola" değişkenine alınır.` · `"Müşteri Numarası" alanının değeri "Numara" değişkenine alınır.` |
| Gezinme | `"Giriş" sayfası açılır.` · `"Ana Sayfa" sayfası açık olmalıdır.` |
| Alan ve tıklama | `"Tutar" alanına "1000" yazılır.` · `"Tutar" alanına "Değer" değişkeninin değeri yazılır.` · `"Tutar" alanı temizlenir.` · `"Tutar" alanından sekme tuşuyla çıkılır.` · `"Kaydet" düğmesine tıklanır.` |
| Seçim | `"Para Birimi" listesinden "Türk Lirası" seçilir.` · `"Onay" onay kutusu işaretlenir.` · `"Onay" onay kutusunun işareti kaldırılır.` |
| Durum doğrulama | `"Mesaj" görünür olmalıdır.` · `"Mesaj" görünür olmamalıdır.` · `"Kaydet" düğmesi etkin olmalıdır.` · `"Kaydet" düğmesi devre dışı olmalıdır.` |
| Metin ve değer doğrulama | `"Mesaj" metni "Tamamlandı" olmalıdır.` · `"Mesaj" metni "tamam" ifadesini içermelidir.` · `"Tutar" alanının değeri "1000" olmalıdır.` · `"Tutar" alanı boş olmalıdır.` |
| Seçim doğrulama | `"Para Birimi" listesinde "Türk Lirası" seçili olmalıdır.` · `"Onay" onay kutusu işaretli olmalıdır.` · `"Onay" onay kutusu işaretsiz olmalıdır.` |

**İşlem anlamları.** Alan doldurma mevcut değeri değiştirir. Seçim görünen seçenek metnini kullanır. Boş alan doğrulaması alanın değerini kontrol eder. Onay kutusuna istenen durum zaten sağlanıyorsa işlem yapmaz. Sayfa doğrulamasında Registry'deki adres ve mevcut tarayıcı adresi için yol, sorgu ve son eğik çizgi karşılaştırma kuralları açıkça tanımlanır. Metin doğrulamasında büyük-küçük harf ve boşluk davranışı, üç hedefteki uygulamasıyla birlikte sözleşmeye yazılır.

**Çıkış ölçütü.** Her kalıp için geçerli örnek, bozuk örnek ve beklenen Java işlemi tanımlanır.

- [x] Dosya içe alma, senaryo, ortak adım, hazırlık ve temizlik bloklarının tam sözcüklü yazımını tanımla.
- [x] Sayfa açma, tıklama, alan doldurma ve temizleme, Tab ile odaktan çıkma, listeden seçim ve onay kutusu işlemlerini tanımla.
- [x] Sayfa, görünürlük, tam ve kısmi metin, alan değeri, boşluk, etkinlik ve seçim durumu doğrulamalarını tanımla. Her kalıbın üç framework'teki karşılığını ve karşılaştırma davranışını belirt.
- [x] Tırnak içindeki metinlerde kaçışları, boş satırları ve Türkçe karakterleri tanımla. Desteklenen her kalıp için örnek ver.

### 3. Ayrıştırma ve doğrulama katmanını geliştir

**İş sırası.** Önce UTF-8 dosyayı oku ve blok sınırlarını tanı. Sonra adımları tipli kayıtlara dönüştür. Ardından içe alma grafiğini çöz, değişkenleri ve çağrı imzalarını denetle, Registry'deki referansları bağla. Bir tanı varsa koşumu başlatma.

**Object Registry.** İlk biçim olarak UTF-8 JSON önerilir. Sayfa adı ve adresi ile sayfaya bağlı obje adı, tipi ve locator bilgisi yeterli. Kullanıcı dosyayı kendisi düzenler. İlk sürümde aynı sayfadaki farklı objeler benzersiz adlarla tanımlanır. Derleyici sayfa açma ve sayfa doğrulama adımları üzerinden kapsamı izler. Ortak adımın bir objeyi kullanmadan önce hangi sayfada çalıştığı açıkça belirlenebilmelidir.

**Tanılar.** Dosya, satır, mümkünse sütun, hata kodu ve Türkçe açıklama üret. Örnek `kredi.testtr:7:3 [NESNE_BULUNAMADI] "Kaydet" düğmesi "Kredi Açılış" sayfasında tanımlı değil.`

**Çıkış ölçütü.** Türkçe karakterler ve Windows satır sonları doğru işlenir. Tekrarlı tanım, bozuk tırnak, döngüsel çağrı, eksik parametre ve yanlış obje tipi ayrı ayrı doğrulanır.

- [x] Satır bazlı parser ile senaryo, adım, değer ve çağrıları tipli bir modele dönüştür. Her düğümün kaynak dosyasını ve satırını sakla.
- [x] Kullanıcının hazırladığı Object Registry için tek bir dosya biçimi seç ve örneğini ver. Sayfa ve obje başvurularını doğrula. Eksik veya belirsiz eşleşmeleri ilgili Türkçe satırda göster.
- [x] İçe alınan dosyaları, ortak adım çağrılarını, zorunlu girdileri, tipleri ve değişken kapsamını doğrula. Döngüsel içe alma ve ortak adım çağrılarını derleme hatası olarak bildir.

### 4. Ortak akışları ve yardımcı işlemleri bağla

**Giriş akışı.** Ortak giriş dosyası kullanıcı adı ve parola alır, giriş sayfasını açar, alanları doldurur ve beklenen sayfanın açıldığını doğrular. Her senaryoda giriş gereken dosyalarda hazırlıktan çağrılır. Giriş davranışının kendisini sınayan senaryolar giriş adımını kendi akışında kullanır.

**Değişken kapsamı.** Hazırlığın ürettiği değerler ilgili senaryoda kullanılabilir. Ortak adım kendi yerel değerleri ve parametreleriyle çalışır. Temizlik, hazırlık tamamlanmadan da çağrılabileceği için koşum kimliğine göre çalışır ve eksik veriyi güvenli biçimde ele alır.

**Yardımcı işlem sözleşmesi.** Ad, izin verilen girdiler, tipler ve varsa dönüş değeri test projesinde kayıt altına alınır. Derleyici bu imzaları okur. Koşumda kayıtlı Java uygulaması çağrılır. Örnek proje müşteri oluşturmayı ve koşum kimliğine bağlı temizliği gösterir. Sırlar ortamdan okunur ve sır kullanan adımlarda raporlanan değerler maskelenir.

**Çıkış ölçütü.** Giriş akışı iki senaryoda tekrar kullanılır. Yardımcı işlemin sonucu sonraki bir alan adımına aktarılır. Hazırlık hatasında temizlik çalışır ve ilk hata raporda korunur.

- [x] Parametreli ortak adımları destekle. Giriş akışını ayrı bir `.testtr` dosyasında tanımla ve hem senaryodan hem hazırlık bloğundan çağırmayı göster.
- [x] Ayarları ve sırları çalışma anında çöz. Parola gibi sırların üretilen Java koduna, hata mesajlarına ve raporlara yazılmasını engelle.
- [x] Test projesinde kayıtlı Java yardımcı işlemlerine tipli girdi ve sonuç bağla. Bir mevcut betik gerekiyorsa çağrısını bu Java işleminin arkasında tut.
- [x] Hazırlık ve temizliği senaryo başına çalıştır. Hazırlık yarıda kaldığında da temizliği dene. Test ve temizlik birlikte başarısız olursa iki hatayı da raporla.

### 5. Java kod üretimini tamamla

**Üretim kuralları.** Her senaryoyu seçilebilir bir JUnit testi olarak üret. Türkçe görünen adı koru ve Java sınıf ve metot adlarını kararlı, çakışmasız biçimde üret. Kodu örnek projenin `target/generated-test-sources` alanında tut. Her koşumda yalnızca seçilen güncel kaynakları test derlemesine ekle.

**Kaynak eşleme.** Üretilen adımın Java konumunu Türkçe kaynak konumuyla ilişkilendir. Ortak çağrıda çağrı zincirini koru. JUnit sonucunda senaryo adı, adım ve beklenen ile gözlenen değerler mevcutsa gösterilir. Sır içeren değerler maskelenir.

**Çıkış ölçütü.** Aynı senaryo, Registry ve yardımcı işlem sözleşmesi iki derlemede aynı Java çıktısını verir. Tırnak, ters eğik çizgi ve Türkçe karakter içeren değerlerle üretilen kaynak JDK 17'de derlenir.

- [x] Doğrulanmış modelden okunabilir seçilen framework için Java 17 ve JUnit 5 kaynak kodu üret. Ortak adımları yardımcı metotlara dönüştür.
- [x] Aynı girdilerle aynı çıktıyı üret. Üretilen dosyaları kullanıcı kaynaklarından ayrı tut ve Türkçe metinleri Java koduna güvenli biçimde aktar.
- [x] Java koşum hatasını Türkçe kaynak satırına bağla. Ortak adım hatasında çağrı satırını ve ortak dosyadaki başarısız satırı göster.

### Kod üretim hedefleri ve ortak sözleşme

- [x] Derleyici, Türkçe senaryoyu sayfa, çözülmüş obje, aksiyon, doğrulama, ortak çağrı ve kaynak konumu içeren framework bağımsız bir modele dönüştürür.
- [x] Selenide, Playwright Java ve Selenium WebDriver için ayrı Java kod üreticileri geliştir. Her üretici seçilen framework'ün çağrılarını ve gereken tarayıcı yaşam döngüsünü üretir. JUnit, yardımcı işlemler ve Türkçe kaynak eşleme ortak kalır.
- [x] Hedefi POM'da açıkça seç. Maven eklentisi ilgili üreticiyi çağırır. Test projesi seçtiği hedefin koşum bağımlılıklarını kullanır. Örnek projede üç ayrı Maven profiliyle her hedefi bağımsız doğrula.
- [x] Registry için üç hedefte kullanılabilen locator türlerini tanımla. Bir hedefin desteklemediği locator veya işlem varsa kaynak üretiminde Türkçe dosya ve satırla hata ver.
- [x] Bekleme, tam metin eşleşmesi, görünmezlik, etkinlik ve seçim davranışlarını ortak dil sözleşmesinde tanımla. Her hedefte aynı anlamı doğrula. Selenium tarafında gereken açık beklemeleri üret.
- [x] Aynı senaryo ve Registry ile üç hedefi ayrı ayrı çalıştır. Ortak giriş akışı geçmeli, bilinçli bozulan doğrulama üçünde de aynı Türkçe adımda başarısız olmalı ve elle yazılmış JUnit testleri normal Maven akışında çalışmalı.

**Uygulama sırası.** Önce Selenide ile Maven kaynak üretimi ve tarayıcı koşumunu uçtan uca doğrula. Ardından Playwright Java ve Selenium üreticilerini ekle. Faz 1 kabulü üç hedefin de aynı dil sözleşmesini karşılamasını gerektirir.
### 6. Maven test yaşam döngüsüne entegre et

| Maven aşaması | Yapılacak iş |
|---|---|
| `generate-test-sources` | Projenin POM'unda yapılandırılan entegrasyon Java derleyici çekirdeğini çağırır. Senaryoları, Registry'yi ve yardımcı işlem imzalarını doğrular. Java kaynaklarını `target` altında üretir ve Maven'a test kaynağı olarak kaydeder. |
| `test-compile` | Maven Compiler Plugin, üretilen testleri ve mevcut Java testlerini projenin JDK 17 ayarlarıyla derler. |
| `test` | Surefire, mevcut JUnit testleriyle birlikte yapılandırmaya dahil edilen UI testlerini çalıştırır. Sonuçlar normal Maven test raporlarında görünür. |

**Proje ayarları.** Senaryo dizini, Registry yolu ve üretilen kaynak dizini POM'da tanımlanır. Test projesi taban adresini ve tarayıcı ayarlarını sağlar. Tarayıcı gerektiren testler proje profiliyle ayrıca seçilebilir. UI testlerini entegrasyon testi olarak ayıran projelerde aynı kaynak üretim aşaması kullanılır ve tarayıcı koşumu Failsafe'e bağlanabilir.

**Çıkış ölçütü.** Maven test yaşam döngüsü hem elle yazılmış hem üretilen testleri derleyip çalıştırır. Türkçe senaryoda hata varsa Maven derlemesi `generate-test-sources` aşamasında ilgili dosya ve satırla durur. Tarayıcıda başarısız olan bir doğrulama JUnit raporunda Türkçe kaynak konumuyla görünür.

- [x] Maven kaynak üretimini üç hedefle çalıştırılabilen örnek Maven test projesinin POM'una bağla ve silinen senaryoların eski Java çıktılarının koşuma girmediğini doğrula.
- [x] Örnek projede mevcut bir Java birim testiyle üretilen UI testinin Maven yaşam döngüsünde birlikte çalıştığını göster.
- [x] DSL hatalarını Maven kaynak üretimi hatası olarak, başarısız doğrulamaları ise JUnit test sonucu olarak raporla.

### 7. İlk teslimi uçtan uca doğrula

| Durum | Beklenen sonuç |
|---|---|
| Geçerli senaryo ve ortak giriş | Java üretilir, Chrome açılır ve test geçer. |
| İş kuralına aykırı UI davranışı | Test başarısız olur ve Türkçe doğrulama satırı gösterilir. |
| Ortak giriş adımı başarısız | Rapor senaryodaki çağrı ve ortak dosyadaki hata satırını gösterir. |
| Hatalı Türkçe cümle veya eksik obje | Konumlu hata gösterilir ve koşum başlamaz. |
| Eksik sır veya başarısız hazırlık | Hata kaydedilir ve temizlik bloğu denenir. |
| Test ve temizlik birlikte başarısız | Birincil hata ile temizlik hatası ayrı görünür. |
| Ardışık koşumlar | Önceki üretimden kalan testler sonraki seçime karışmaz. |

**Teslim içeriği.** Kaynak kodu, Java 17 uyumlu Maven eklentisi, Maven Wrapper, dil sözleşmesi, üç hedefle çalıştırılabilen örnek Maven test projesi, yerelde çalıştırılabilen test ekranı, örnek Registry ve ortak giriş ile yardımcı işlem kullanan senaryolar. README kurulum, derleme, koşum ve hata okuma adımlarını kapsar.

- [x] JDK 17 ile temiz ortamda Maven Wrapper üzerinden projeyi derle ve test yaşam döngüsünü çalıştır.
- [x] Örnek test uygulamasında ortak giriş adımını kullanan başarılı bir senaryoyu tarayıcıda çalıştır.
- [x] Beklenen iş davranışı karşılanmadığında koşumun başarısız olduğunu ve doğru Türkçe doğrulama satırını gösterdiğini doğrula.
- [x] Hatalı Türkçe adım veya eksik Registry kaydı bulunduğunda tarayıcı koşumunun başlamadığını doğrula.
- [x] Kayıtlı yardımcı işlemle test verisi oluşturma, dönen değeri kullanma ve başarısız koşumdan sonra veriyi temizleme akışını doğrula.
- [x] Kurulumdan koşuma kadar gerekli komutları, örnek senaryoları ve Registry dosyasını projeyle birlikte teslim et.

## Faz 2 — VS Code eklentisi

- [x] TypeScript eklentisinde sözdizimi renklendirme, tam cümle şablonları, ortak adım ve obje adı önerileri ekle.
- [x] Derleyici çekirdeği üzerinden hata gösterimi ve Java önizlemesi ekle. Editörden test çalıştırmayı projenin Maven yaşam döngüsüne bağla. Dil kurallarını derleyicide tut.

## Uzun vadeli hedef

Onaylı bir gereksinim veya kod değişikliği geldiğinde sistem etkiyi belirler, mevcut kapsamı kontrol eder, eksik senaryoları üretir, gerekiyorsa obje ve test verisi hazırlar, Maven üzerinden testleri çalıştırır ve sonucu kanıtlarıyla kaydeder. Başarısız koşumlarda neden sınıflandırılır ve doğrulanabilir bakım değişiklikleri uygulanır. Yetki sınırları ve incelemeye düşen durumlar proje politikasıyla belirlenir.

## Faz 3 — İzlenebilir ve tekrarlanabilir CI koşumu

- [x] Senaryo kimliğini ve gereksinim bağlantısını kaynak dosyadan JUnit raporuna kadar taşı. Koşum kaydına kod, Registry ve derleyici sürümünü ekle.
- [ ] CI'da Maven test akışını çalıştır. Test ortamı, kullanıcı ve veri hazırlığını kayıtlı yardımcı işlemlere bağla. Her koşuma ayrı veri kapsamı ver ve temizlik sonucunu kaydet.
- [x] Başarısız adımın Türkçe konumunu ve uygun tarayıcı kanıtını sakla. Sırları ve hassas test verisini raporlarda maskele.

Windows'ta CI için kullanılan Maven komutları ve XML dışa aktarımı üç profilde yerel olarak doğrulandı. GitHub Actions üzerinde Ubuntu koşumu ve çıktı yüklemesi doğrulama bekliyor.

**Kabul ölçütü.** Aynı sürümlerle koşum tekrarlanabilir. Bir rapordan ilgili senaryoya, gereksinime ve kullanılan girdilere ulaşılabilir.

## Faz 4 — AI ile gereksinimden senaryo üretimi

- [ ] Onaylı gereksinimleri kimlik ve sürümüyle al. AI, mevcut senaryoları dikkate alarak eksik pozitif, negatif ve sınır durumları için Türkçe senaryo taslakları üretir.
- [ ] Her beklenen sonucu bir gereksinim veya kullanıcı onayına bağla. Çelişkili gereksinimleri ve tanımsız adımları inceleme kuyruğuna ayır.
- [ ] Taslakları mevcut derleyiciye ver. Derleme tanılarıyla sınırlı düzeltme denemeleri yap, ardından Maven ile çalıştır ve farkı incelemeye sun.

**Kabul ölçütü.** Bir gereksinim değişikliğinden izlenebilir, derlenebilir ve çalıştırılmış bir senaryo önerisi çıkar. Çalışan UI ile gereksinim çelişirse rapor bu farkı gösterir.

## Faz 5 — Değişiklik etkisi ve otomatik obje keşfi

- [ ] React diff'i ile route, bileşen, Registry ve mevcut testler arasındaki bağlantıları çıkar. Önce ilgili testleri çalıştır, kapsam boşluğu varsa Faz 4'ü tetikle. Etki belirsizse daha geniş test kümesine dön.
- [ ] React kaynağından ve canlı UI'dan Registry adayları çıkar. Locator'ı hedef sayfa ve kapsamda tarayıcıyla doğrula. İlk aşamada değişikliği diff olarak sun.
- [ ] Test veri ihtiyacını senaryo önkoşullarından çıkar. Kayıtlı veri hazırlama işlemlerini seç, çalıştır ve temizlik kanıtını sakla.

**Kabul ölçütü.** Bir PR değişikliğinden etkilenen testler, varsa eksik senaryo ve doğrulanmış Registry adayı gerekçeleriyle üretilir.

## Faz 6 — Hata analizi ve kontrollü bakım

- [ ] Koşum hatalarını uygulama davranışı, locator, veri, ortam ve kararsız koşum olarak sınıflandır. Kanıt yetersizse sonucu belirsiz olarak işaretle.
- [ ] Locator değişikliğini sınırlı kapsamda öner ve etkilenen testleri yeniden çalıştır. İş kuralına ait doğrulamayı ve beklenen değeri koru. Ürün davranışıyla gereksinim arasındaki farkı hata olarak raporla.
- [ ] Önce öneri modunda ölçüm yap. Sonra yalnızca proje politikasının izin verdiği, tarayıcıda doğrulanmış ve geri alınabilir değişiklikleri otomatik uygula.

**Kabul ölçütü.** Kontrollü locator değişikliği Registry güncellemesiyle giderilir. Kontrollü iş kuralı ihlalinde doğrulama başarısız kalır ve doğru bulgu raporlanır.

## Faz 7 — Otonom orkestrasyon

- [ ] Gereksinim ve PR olaylarını tek iş akışına bağla. Her işin durumunu, girdi sürümlerini ve kararlarını kaydet. Aynı olay tekrar geldiğinde mükerrer öneri veya PR üretme.
- [ ] Aşamaları yeniden denenebilir kıl. Deneme ve model bütçesi, koşum zaman aşımı ve yetkileri proje bazında sınırla. Çelişki, yetki dışı işlem veya belirsiz eşleşmeyi incelemeye yönlendir.
- [ ] Derleme başarısı, kabul edilen öneri oranı, yanlış bakım önerisi, kararsız koşum ve maliyeti izle. Otomatik uygulama kapsamını ölçülen sonuçlara göre genişlet.

**Kabul ölçütü.** Tanımlı bir değişiklik türü, tetiklenmeden doğrulanmış test çıktısına kadar elle müdahale gerektirmeden ilerler. İstisnalar kanıtıyla incelemeye düşer.

## Değerlendirmeye açık ilk faz önerileri

1. **Maven entegrasyonunu erken doğrula.** Faz 1 kapsamını koruyarak önce bir Türkçe senaryoyu `generate-test-sources` → `test-compile` → `test` hattından geçir. Tam dil kapsamını, ortak adımları ve yardımcı işlemleri bu çalışan akışa ekle.
2. **Otomasyonun ihtiyaç duyacağı bilgileri ilk günden sakla.** Kararlı senaryo kimliği, kaynak konumu ve makinece okunabilir derleyici tanıları erken tanımlansın. Gereksinim bağlantısı isteğe bağlı başlayabilir. Merkezi servis ve veri tabanı sonraki fazlarda değerlendirilsin.
3. **Tarayıcı koşumunu proje politikasıyla seçilebilir yap.** Örnek proje mevcut birim testleriyle üretilen Selenide testlerinin birlikte çalıştığını göstersin. Hedef projeler tarayıcı testlerini profil veya Failsafe ile ayırabilsin.
4. **VS Code ile otomasyon altyapısı arasında zorunlu bağımlılık kurma.** VS Code sonraki fazda kalsın. Maven ve raporlama hattı hazır olduğunda CI çalışmaları editör eklentisinden bağımsız ilerleyebilsin.
