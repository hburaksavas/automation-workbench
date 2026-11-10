# TestTR for VS Code

TestTR, `.testtr` dosyalarında sözdizimi renklendirmesi ve Türkçe adım şablonları sunar. Adımın ilk çift tırnağını yazınca Registry dizinindeki veya tek JSON dosyasındaki sayfa ve obje adları ile geçerli dosyada veya içe aldığı dosyalarda bulunan ortak adımlar önerilir.

## Kurulum

Bu dizinde `npm ci` çalıştırın. `npx @vscode/vsce package` ile oluşturulan VSIX paketini VS Code'da **Extensions: Install from VSIX…** komutuyla kurun. Maven projesinin kökünü güvenilen bir çalışma alanı olarak açın. JDK 17 için `JAVA_HOME` ayarlı olmalıdır. Tarayıcı koşumu için Google Chrome gerekir.

Bir `.testtr` dosyası açıkken komut paletinden aşağıdaki komutları çalıştırabilirsiniz.

- **TestTR: Senaryoları doğrula** Maven `test-compile` aşamasını çalıştırır. Derleyicinin dosya ve satır içeren DSL tanıları Problems panelinde görünür.
- **TestTR: Üretilen Java kodunu aç** aynı Maven aşamasından sonra açık dosyaya ait Java çıktısını açar. Dosyada birden fazla senaryo varsa seçim sunar.
- **TestTR: Maven testlerini çalıştır** Maven Wrapper üzerinden projenin `test` yaşam döngüsünü çalıştırır. Seçili Maven profili kapsamındaki tüm testler çalışır.

`testtr.profile` ile Selenide, Selenium veya Playwright profilini seçebilirsiniz. Varsayılan seçim POM ayarını kullanır. POM'daki Registry, DSL veya üretilen Java konumu varsayılandan farklıysa `testtr.registry`, `testtr.sourceDirectory` ve `testtr.generatedDirectory` ayarlarını test modülüne göre düzenleyin. Bu editör ayarları POM'u değiştirmez.
