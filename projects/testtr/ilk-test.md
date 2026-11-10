# İlk TestTR projesi

Bu örnekte bağımsız bir Maven projesi oluşturup Chrome'da yerel bir HTML sayfasını açacağız. Test, sayfadaki karşılama metnini doğrular. Komutlar Windows PowerShell içindir. JDK 17, Google Chrome ve Automation Workbench deposunun yerel kopyası gerekir.

## 1. TestTR bileşenlerini kur

Automation Workbench deposunun kökünde aşağıdaki komutları çalıştır. İlk komut Maven Wrapper'ın kullandığı Java sürümünü gösterir. İkinci komut TestTR Maven eklentisini ve koşum kütüphanesini yerel Maven deposuna kurar.

```powershell
$workbench = (Get-Location).Path
$testtr = Join-Path $workbench 'projects/testtr'
$wrapper = Join-Path $testtr 'mvnw.cmd'
& $wrapper --version
& $wrapper -f (Join-Path $testtr 'pom.xml') -pl maven-plugin,runner-support -am install
```

## 2. Örnek proje dizinini oluştur

```powershell
$demo = Join-Path (Split-Path $workbench -Parent) 'ilk-testtr'
New-Item -ItemType Directory -Force -Path @(
  "$demo/src/test/registry",
  "$demo/src/test/resources",
  "$demo/src/test/testtr"
) | Out-Null
Set-Location $demo
```

`pom.xml` dosyasını proje köküne kaydet. Örnek Selenide hedefini kullanır.

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>dev.example</groupId>
  <artifactId>ilk-testtr</artifactId>
  <version>1.0.0-SNAPSHOT</version>

  <properties>
    <maven.compiler.release>17</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <testtr.version>0.1.0-SNAPSHOT</testtr.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>dev.testtr</groupId>
      <artifactId>runner-support</artifactId>
      <version>${testtr.version}</version>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>5.11.4</version>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>com.codeborne</groupId>
      <artifactId>selenide</artifactId>
      <version>7.7.3</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>dev.testtr</groupId>
        <artifactId>testtr-maven-plugin</artifactId>
        <version>${testtr.version}</version>
        <executions>
          <execution>
            <goals><goal>generate</goal></goals>
          </execution>
        </executions>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.13.0</version>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.5.2</version>
      </plugin>
    </plugins>
  </build>
</project>
```

### Test sayfası

`src/test/resources/index.html` dosyasını UTF-8 olarak kaydet.

```html
<!doctype html>
<html lang="tr">
<head>
  <meta charset="utf-8">
  <title>İlk TestTR örneği</title>
</head>
<body>
  <h1 id="mesaj">Merhaba TestTR!</h1>
</body>
</html>
```

### Sayfa Registry’si

`src/test/registry/ana-sayfa.json` dosyası, senaryoda kullanılan Türkçe isimleri adres ve CSS locator ile eşler.

```json
{
  "pages": {
    "Ana Sayfa": {
      "url": "index.html",
      "objects": {
        "Karşılama Mesajı": {
          "type": "text",
          "css": "#mesaj"
        }
      }
    }
  }
}
```

### Türkçe senaryo

`src/test/testtr/ilk-test.testtr` dosyasını UTF-8 olarak kaydet.

```text
Senaryo: "Karşılama mesajı görünür".
  "Ana Sayfa" sayfası açılır.
  "Karşılama Mesajı" metni "Merhaba TestTR!" olmalıdır.
Senaryo sonu.
```

## 3. İlk testi çalıştır

Örnek proje kökünde, önceki adımlardaki PowerShell oturumunda çalıştır. Komut, HTML dizininin adresini `file:///` URI’si olarak verir. Chrome headless açılır.

```powershell
$resources = (Resolve-Path './src/test/resources').Path
$baseUrl = ([uri]($resources + [IO.Path]::DirectorySeparatorChar)).AbsoluteUri
& $wrapper "-Dtesttr.baseUrl=$baseUrl" clean test
```

Maven çıktısında `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` ve `BUILD SUCCESS` görünür. Java kodu `target/generated-test-sources/testtr/`, JUnit XML raporu `target/surefire-reports/` dizinindedir.

Hata raporunu görmek için senaryodaki beklenen metni `"Farklı mesaj"` olarak değiştirip aynı komutu yeniden çalıştır. Surefire hatayı `ilk-test.testtr:3` konumunda gösterir. Beklenen metni geri yüklediğinde test yeniden geçer.
