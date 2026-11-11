# Değişiklikten etkilenen senaryolar

`testtr:impact`, bir Git revizyonuyla mevcut çalışma ağacı arasındaki değişiklikleri okuyup etkilenen TestTR senaryolarını gerekçeleriyle JSON raporuna yazar. Model çağrısı gerektirmez. Kaynak dosyalarının sayfalara bağlantısı kullanıcı tarafından açık dosya kalıplarıyla tanımlanır; isteğe bağlı import taraması bu bağlantıyı kullanılan bileşenlere genişletir.

## Sayfa eşlemesi

Test modülüne `src/test/impact.json` eklenebilir:

```json
{
  "schemaVersion": 1,
  "pages": {
    "Giriş": [
      "frontend/src/pages/Login.*",
      "frontend/src/components/login/**"
    ]
  },
  "global": [
    "frontend/src/components/shared/**",
    "frontend/src/styles/**",
    "frontend/package.json"
  ]
}
```

Örnekteki yollar temsilidir; kendi depo yerleşimine göre düzenle. Tüm kalıplar Git deposunun köküne göredir, Maven modülüne göre değildir. Sayfa adları Registry'de bulunmalıdır. Bir dosya birden fazla sayfaya bağlanabilir. Uzantı kısıtlaması yoktur: `.js`, `.jsx`, `.ts`, `.tsx`, CSS veya başka dosyalar eşlenebilir.

`*` tek yol bölümünde, `**` alt dizinler dahil, `?` tek karakter için eşleşir. `**/` sıfır veya daha fazla dizini kapsar. Diğer karakterler düz metindir. Import taraması kapalıysa ortak bileşenler etkilenen tüm sayfalara veya `global` listesine bağlanmalıdır.

## Import bağlantıları

`impact.json` içine isteğe bağlı `imports` alanı eklenebilir:

```json
{
  "schemaVersion": 1,
  "pages": { "Giriş": ["frontend/src/pages/Login.js"] },
  "imports": {
    "roots": ["frontend/src"],
    "aliases": { "@/": "frontend/src/" },
    "external": ["react", "react-dom"]
  }
}
```

`roots` ve alias hedefleri Git depo köküne göredir. Tarama kökleri mevcut dizinler olmalıdır ve eşlenen tüm sayfaların giriş dosyalarını kapsamalıdır. Bir sayfanın giriş dosyası bu köklerde bulunamazsa değişiklikler için geniş seçim yapılır. Sayfaya eşlenen mevcut dosyadan başlanır; göreli importlar ve açık alias eşlemeleriyle yerel bağımlılıklar takip edilir. Döngüler tekrar ziyaret edilmez. `node_modules`, `.git`, `target`, `dist` ve `build` alt dizinleri kök taramasında atlanır; tarama en fazla 10000 dosyayla sınırlıdır.

Örneğin `Login.js → components/index.jsx → Button.tsx` zincirinde yalnızca `Button.tsx` değişirse Giriş sayfasını kullanan senaryolar seçilir. Gerekçede dosya zinciri yer alır. Böylece ortak bileşeni ayrıca sayfa kalıbına eklemek gerekmez. `.js`, `.jsx`, `.ts`, `.tsx`, `.mjs` ve `.cjs` içindeki sabit metinli `import`, `export ... from`, `require(...)` ve `import(...)` biçimleri taranır. Uzantısız yollar ve dizinlerin `index` dosyaları çözülür. Doğrudan import edilen stil, JSON ve görsel dosyaları da bağımlılık olarak kaydedilir.

`external` yalnızca gerçekten harici paketleri içermelidir; alt paket yollarını da kapsar. Yerel alias'ları burada harici olarak işaretleme. Bundler veya tsconfig alias ayarları otomatik okunmaz. Alias'lar açıkça tanımlanır; tanınmayan paket/alias, eksik dosya veya birden fazla dosyaya çözülen yol belirsizliktir. Değişiklik varsa bu belirsizlik tüm TestTR senaryolarını seçtirir.

Bu tarama tam JavaScript/TypeScript AST çözümleyicisi değildir ve uygulama kodunu çalıştırmaz. Değişkenle yapılan importlar, `import.meta`, `require.context`, desteklenmeyen import/export sözdizimi ve stil dosyalarındaki ek `@import`, `@use`, `@forward` veya `url(...)` bağlantıları geniş seçime neden olur. Yorumlarda veya string içinde import benzeri metinler de temkinli olarak ek bağımlılık/belirsizlik sayılabilir. Worker, çalışma anında oluşturulan script adresleri veya import dışındaki uygulama bağlantıları için açık sayfa/global eşlemelerini koru. Import taraması tanımlanmazsa önceki kalıp tabanlı davranış aynen sürer.

## Maven komutu

Depo kökünde, JDK 17 ile bileşenleri kurup örnek modülü analiz et:

```powershell
$wrapper = Join-Path (Get-Location) 'projects/testtr/mvnw.cmd'
& $wrapper -f projects/testtr/pom.xml -pl maven-plugin,runner-support -am install
& $wrapper -f projects/testtr/example-tests/pom.xml `
  dev.testtr:testtr-maven-plugin:0.1.0-SNAPSHOT:impact `
  '-Dtesttr.impactBase=HEAD'
```

Bağımsız projede `-f` ile ilgili test modülünün POM'unu seç. Unix'te aynı argümanlarla `sh projects/testtr/mvnw` kullanılır. Git komutunun erişilebilir olması gerekir. Komut test çalıştırmaz ve normal Maven test seçimini değiştirmez.

`testtr.impactBase` varsayılanı `HEAD`'dir. Staged, unstaged ve Git tarafından ignore edilmeyen untracked dosyalar hesaba katılır. `HEAD~1` gibi bir revizyon verilirse, o revizyondan mevcut çalışma ağacına kadar değişiklikler karşılaştırılır. Temiz bir checkout'ta bu, iki commit arasındaki farktır. Senaryo bağımlılıkları her zaman mevcut çalışma ağacından okunur. Silinen dosyalar ve yeniden adlandırmanın eski/yeni yolları da değerlendirilir.

`-Dtesttr.impactConfig=<dosya>` ile farklı eşleme dosyası, `-Dtesttr.impactReport=<dosya>` ile farklı rapor konumu seçilebilir. Açıkça seçilen eşleme dosyası yoksa komut hata verir. Varsayılan eşleme dosyası yoksa doğrudan senaryo/ortak adım bağımlılıkları yine izlenir; diğer değişiklikler geniş seçime neden olur. Registry ve gereksinim kataloğu, kaynak üretimindeki ayarlarla seçilir; POM'daki `<sourceDirectory>` ve `<helpers>` ayarları da desteklenir.

## Seçim kuralları ve rapor

Varsayılan çıktı test modülünde `target/testtr-impact.json` dosyasıdır. Rapor temel revizyonu, değişen dosyaları, eşlenemeyen dosyaları, geniş seçim nedenlerini ve seçilen her senaryonun kimliğini, adını, kaynak dosyasını, kullandığı sayfaları ve seçim gerekçelerini içerir. Senaryo kimliği Java kaynak üretimindeki sınıf adıyla aynıdır.

- Sayfaya eşlenen uygulama dosyası değişirse, o sayfayı kullanan senaryolar seçilir. İç içe ortak adımlar, hazırlık ve temizlik içindeki sayfa kullanımları dahildir.
- Bir `.testtr` dosyası değişirse o dosyadaki senaryolar ve o dosyada kullanılan adımları çağıran senaryolar seçilir. Ortak dosyanın mevcut genişletilmiş çağrı zincirleri izlenir.
- Registry, yardımcı işlem sözleşmesi, gereksinim kataloğu, eşleme dosyası, test modülünün veya depo içindeki üst dizinlerinin POM'u değişirse tüm TestTR senaryoları seçilir.
- `global` kalıbına uyan veya etkisi eşlenemeyen bir dosya değişirse tüm TestTR senaryoları seçilir. Belgeler ve diğer modüller de açıkça eşlenmediklerinde bu kurala dahildir. İlk sürümde sessizce göz ardı edilen dosya listesi yoktur.
- Değişiklik bir sayfaya eşleniyor fakat o sayfayı kullanan senaryo bulunamıyorsa kapsam boşluğu geniş seçim nedeni olarak raporlanır.
- Bozuk senaryo, eksik içe alma veya geçersiz eşleme komutu başarısız kılar; bunlar "etkilenen test yok" şeklinde raporlanmaz.

`mode`, temkinli geniş seçimde `all`, hedefli seçimde `selected`, değişiklik bulunmadığında `none` olur. `selected` yalnızca TestTR senaryolarını içerir; mevcut Java/JUnit ve Cucumber testlerini kapsamaz. Eşleme eksik ya da yanlışsa seçimin kapsamı da bundan etkilenir; doğru ortak bileşen eşlemesi proje sorumluluğundadır.

## Seçilen senaryoları çalıştırma

`testtr:select` etki analizini yeniden yapar ve aynı Maven oturumunda Surefire test seçimini ayarlar. Ardından gelen `test` yaşam döngüsü güncel TestTR kaynaklarını üretir, derler ve seçilen senaryoları çalıştırır:

```powershell
& $wrapper -f projects/testtr/example-tests/pom.xml `
  clean dev.testtr:testtr-maven-plugin:0.1.0-SNAPSHOT:select test `
  '-Dtesttr.impactBase=HEAD'
```

`clean` kullanılacaksa seçim hedefinden önce yazılır; böylece yeni etki raporu ve JUnit sonuçları korunur. `-Pselenium` veya `-Pplaywright` aynı komuta eklenebilir. Profil seçilmezse projenin varsayılanı kullanılır. Önceden üretilmiş rapor okunmaz; seçim her çağrıda mevcut kaynaklardan hesaplanır.

- Hedefli seçimde yalnızca seçilen TestTR sınıfları çalışır.
- Geniş seçimde mevcut tüm TestTR senaryoları çalışır.
- Seçim boşsa bu oturum için `skipTests=true` ayarlanır. Maven kaynak üretimi ve derlemeye devam edebilir; Surefire test çalıştırmaz.
- Seçilen test başarısız olursa Maven başarısız olur. Hiçbir seçili sınıf bulunamazsa da Surefire başarısız olur.

Bu açık seçim komutu, elle yazılmış Java/JUnit ve Cucumber testlerini koşuma katmaz. Normal Maven `test` komutu ve POM değiştirilmez; ayarlar yalnızca o Maven oturumunda geçerlidir. Tam regresyon için normal test komutunu kullan.

Komut tek bir test modülünde çalıştırılır; `-f` ile modülün POM'unu seç, `-am` veya çok modüllü reactor kullanma. Mevcut `-Dtest`, `-DskipTests=true` ve `-Dmaven.test.skip=true` seçenekleriyle çakışma sessizce çözülmez, hata verilir. Çalıştırma akışı Surefire'ın `test`, `skipTests` ve `surefire.failIfNoSpecifiedTests` özelliklerini kullanan yapılandırmayı hedefler; Failsafe koşumu henüz bağlanmadı.

PowerShell 7 ile depo kökünde `./.github/scripts/verify-impact-selection.ps1`, geçici bir Git/Maven projesinde boş, hedefli, import edilen bileşen üzerinden hedefli, geniş ve başarısız koşumu sınar. Ayrıca ilgisiz Java testinin seçime dahil edilmediğini doğrular. CI bu kontrolü Windows ve Ubuntu için çalıştıracak şekilde yapılandırılmıştır.

Otomatik Registry adayı keşfetmek sonraki adımdır. Import dışındaki React bağlantıları ve bundler'a özgü çözümleme ileride genişletilebilir.
