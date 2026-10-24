# DeviceScope

Android cihazın donanım bilgilerini, canlı sıcaklıklarını, erişilebilen geçmiş
kayıtlarını ve iki bağımsız performans testini gösteren; aynı Wi-Fi ağındaki
laptop tarayıcısından izlenebilen uygulama.

APK: `C:\Users\Alican\Desktop\DeviceScope.apk`
Proje: `C:\Users\Alican\AndroidProjects\DeviceScope`

Arayüz **sakin katmanlı yüzey** sistemidir: tek yönlü çok yumuşak gölge, kılcal
çizgi kenarlık ve çok hafif cam parlaklığı. Neumorphism'in "tek parçadan oyulmuş"
hissi korunur ama çift gölge, her blokta büyük harfli mikro etiketler ve üst üste
kutu katmanları kaldırıldı. Apple Intelligence diline yaklaşan üç kural:

- **Tek çapa:** her ekranın bir büyük başlığı ve tek bir baskın sayısı var.
- **Aşamalı açılım:** ayrıntılar `SoftDisclosure` kartlarında kapalı gelir; tüm
  ölçümler durur, ekran kalabalıklaşmaz.
- **Renk sadece anlam taşıdığında:** gradyan halka yalnızca manşet puanda,
  turkuaz/mavi spektrum ise dekoratif arka plan ışımasında kullanılır.

Koyu ve açık tema var; başlıktaki düğmeyle değişir. Tarayıcı paneli aynı token
setini kullanır (koyu, yüzen pill navigasyon, aynı gölge ve yarıçap ölçeği).

## Sekmeler

| Sekme | İçerik |
|---|---|
| Cihaz | Üretici, marka, model, SoC, CPU kümeleri/frekansları, GPU (gerçek OpenGL sorgusu), RAM, depolama çipi, batarya, ekran paneli, kameralar, anakart, yazılım — her bölüm ilk 3 alanı gösterir, gerisi tek dokunuşla açılır |
| Sıcaklık | En sıcak bileşen manşette; işlemci / batarya / hissedilen için üç halka; bileşen maksimumları, çekirdek frekansları ve ham sensör tablosu katlanmış kartlarda |
| Geçmiş | İlk açılış, son önyükleme, ROM/çekirdek derleme tarihleri, pil döngüsü ve kapasite kaybı, panel üretim haftası (EDID varsa), çalışma süresi |
| Uzaktan | Telefon içinde çalışan HTTP sunucusu + tarayıcı paneli; adres, kopyala/paylaş ve tek ana düğme |
| Test | Dört segment: **Mikyas** (CPU/bellek/disk), **Kinetik** (GPU/arayüz/ısıl dayanım), **Kıyas** (kayıtlı sonuçlar), **Doğrulama** (ölçümün kendisini sınayan paket) |

Her test sonucu tek bir manşet kartla açılır: gradyan halka içinde puan, altında
tek cümlelik özet ve doğrulama rozeti. Kategori çubukları açık gelir; ölçüm
tabloları, sistem izleme, ısıl eğri ve yöntem notları `Ayrıntılar` kartlarındadır.


## Test motoru

### Mikyas — Geekbench tarzı

C++ (NDK) içinde 8 iş yükü: AES-128, LZ sıkıştırma, JSON ayrıştırma, matris
çarpımı, görüntü filtresi (Gauss + Sobel), ışın izleme, tam sayı işlemleri,
bellek bant genişliği. Her iş yükü:

1. **Bilinen-cevaplı doğrulamadan** geçer (FIPS-197 AES vektörü, LZ round-trip,
   JSON düğüm sayısı, 2×2 matris çarpımı, Sobel rampasının tam 128 cevabı,
   küre-isabet t=2, sabit xorshift64 dizisi, memcpy). Doğrulama geçmezse test
   başlamaz — bozuk bir derleme puan üretemez.
2. **Isınma** turu atılır.
3. Cihazdan bağımsız olarak her örnek ~260 ms sürecek şekilde **iterasyon
   sayısı kalibre** edilir.
4. **3 ölçüm** yapılır, **ortanca** raporlanır (tek uç değer puanı kaydıramaz).
5. Tekrarlar arası **sağlama toplamı** karşılaştırılır (iş gerçekten koştu mu).
6. **CV (varyasyon katsayısı)** gösterilir; %3 üstü satırlar sarı/kırmızı işaretlenir.

Tek çekirdek ve çok çekirdek ayrı ayrı ölçülür. Depolama testi uygulamanın kendi
cache dizininde 128 MB gerçek yazma (`fsync` ile) + okuma yapar.

### Kinetik — AnTuTu tarzı

- **GPU (Vulkan):** iki gerçek Vulkan iş yükü — 16 MiB SSBO üzerinde 96 turluk
  tamsayı ALU compute (`vk_compute.comp`) ve 1024×1024 çevrimdışı sahne
  (`vk_scene.vert` + `vk_scene.frag`, piksel başına 64 sin/cos). Gölgelendiriciler
  derleme sırasında NDK'nın `glslc`'si ile SPIR-V'ye çevrilir, `spirv-val` ile
  doğrulanır ve `libdsbench.so` içine bayt dizisi olarak gömülür. Her örnekte
  1 ısınma turu atılır, 3 ölçümün ortancası alınır.
  Sürücü Vulkan'ı reddederse ölçüm **OpenGL ES 3.1** compute ile yapılır ve hangi
  API'nin kullanıldığı ekranda, JSON'da ve paylaşım kartında yazar.
- **Arayüz:** 4,5 saniye boyunca her karede ~420 yuvarlatılmış kart çizilir;
  `Choreographer` ile gerçek kare aralıkları toplanır, ortalama FPS, ortanca,
  95. yüzdelik, en kötü kare ve takılma yüzdesi hesaplanır.
- **Açılış:** uygulamanın kendi soğuk açılışı ölçülür — süreç oluşturulma anı
  (`Process.getStartUptimeMillis`) ile ilk `Choreographer` karesi arasındaki süre.
- **Isıl dayanım:** 60 saniye tam yük altında saniyelik hız ve sıcaklık
  kaydedilir; son üçte birin ilk üçte bire oranı **hız koruma yüzdesi** olarak
  verilir. Grafikler sonucun içinde çizilir.
- **Sistem izleme:** test boyunca 0,5 saniyede bir CPU sıcaklığı, batarya
  sıcaklığı, çekirdek frekansı ve batarya yüzdesi kaydedilir.
- **Kıyas sekmesi:** kayıtlı tüm sonuçlar cihaz içinde sıralanır, aynı testin en
  iyisine göre yüzde fark gösterilir; son iki Mikyas çalıştırması yan yana
  karşılaştırılır (gerçek tekrarlanabilirlik kanıtı).
- **Paylaşım:** sonuç, uygulamanın kendi Canvas'ı ile 1080×1350 PNG karta çizilir
  ve `FileProvider` üzerinden paylaşılır (metin paylaşımı da var).

## Güvenilirlik hakkında dürüst notlar

- **Ham metrikler asıl kanıttır:** her satırda MB/s, GFLOP/s, Mpix/s, Mray/s,
  fps gibi ölçülmüş değer ve CV yüzdesi görünür.
- **Puan (index)** = ölçülen hız / sabit referans hız × 1000. Referans sabitleri
  şu an **geçici kalibrasyon değerleridir** (referans cihazda ölçüm yapılmadan
  belirlendi) ve UI'da bu şekilde etiketlenir. Kıyaslama için kullanılabilir,
  mutlak sıralama iddiası taşımaz.
- GPU tarafında **Vulkan birincil yoldur**: gölgelendiriciler derleme sırasında
  `glslc` ile SPIR-V'ye çevrilip `spirv-val` ile doğrulanıyor ve kütüphaneye
  gömülüyor; çalışan baytlar kaynakla birebir aynı. Vulkan başlatılamazsa
  (eski sürücü, kısıtlı cihaz) ölçüm GLES 3.1 compute ile yapılır ve hangi
  API'nin kullanıldığı her yerde açıkça yazılır — gizlenmez.
- Testler cihaz sıcakken farklı çıkar. Mikyas başlangıç/tepe sıcaklığını,
  Kinetik ise tüm ısıl eğriyi kaydeder; sonuç JSON'unda ikisi de saklanır.

## Uzaktan bağlantı

1. Telefon ve laptop aynı Wi-Fi'da olmalı.
2. Uygulamada **Uzaktan → Sunucuyu başlat**.
3. Ekranda çıkan adresi laptop tarayıcısına yaz (`http://192.168.x.x:8080/?k=ANAHTAR`).
4. Durdurmak için aynı düğme veya bildirimdeki **Durdur**.

Sunucu, VPS, abonelik, internet trafiği yok. Veri cihazdan çıkmaz. Her istek
16 haneli anahtarı ister; anahtarsız istek 401 döner. Tarayıcı paneli de aynı
Soft UI dilinde (koyu neumorphism) tasarlandı.

## Etiketler

- **DOĞRULANDI** — platformdan/sensörden doğrudan okundu.
- **TAHMİN** — gerçek verilerden hesaplandı; formülü açıklamada yazar.
- **KISITLI** — donanımda var, Android rootsuz uygulamaya kapalı.
- **KAYIT YOK** — cihaz bu veriyi hiçbir yerde saklamıyor (uydurulmadı).

## Rootsuz cihazda gerçekten okunamayanlar

Anakart üretim tarihi, pil değişim tarihi, kamera/ekran modülü tedarikçisi,
gerçek CPU-GPU jonksiyon sıcaklığı (bazı cihazlarda). Bunlar bilerek
"okunamıyor / kayıt yok" gösterilir. İlk kullanım tarihi cihazın kendi ilk
"Start" kaydından okunur, yazılım kurulum tarihinden değil.

## Derleme

```
cd C:\Users\Alican\AndroidProjects\DeviceScope
C:\Users\Alican\.gradle\wrapper\dists\gradle-8.14.3-bin\cv11ve7ro1n3o1j4so8xd9n66\gradle-8.14.3\bin\gradle.bat :app:assembleDebug
```

APK dört ABI için native kütüphane içerir: arm64-v8a, armeabi-v7a, x86_64.

## Doğrulananlar / doğrulanmayanlar

Doğrulandı:

- Derleme başarılı; `libdsbench.so` üç ABI için üretildi ve JNI sembolleri
  (`Native_nRun`, `Native_nVerify`, `Native_nCores`,
  `VulkanBench_nRun`) ihraç edildi.
- Üç Vulkan gölgelendiricisi `glslc` ile derlendi, `spirv-val --target-env
  vulkan1.0` üçü için de **VALID** döndü.
- Gömülen SPIR-V baytları, derlenen `.spv` dosyalarıyla **bayt bayt aynı**
  (1244 bayt compute shader için karşılaştırıldı).
- AES S-box tablosu, matematiksel tanımdan yeniden üretilerek 256/256 bayt
  karşılaştırıldı.
- AES algoritması (C++ ile birebir aynı adımlar) FIPS-197 C.1 vektörünü
  (`69c4e0d86a7b0430d8cdb78070b4c55a`) üretti.
- Tarayıcı panelinin JS'i `node --check` ile sözdizimi doğrulandı.

Doğrulanmadı (bu makinede mümkün değil — x86_64 emülatör için AEHD/WHPX yok,
yönetici yetkisi yok, bağlı telefon yok):

- Cihaz üzerinde çalışma, izin akışı, testlerin gerçek süreleri ve skor
  tekrarlanabilirliği (%2–3 hedefi) saha ölçümüyle teyit edilmedi.
- Vulkan aygıt/komut zincirinin gerçek bir sürücüde hatasız çalışması; her adım
  `VkResult` kontrolüyle korunuyor ve başarısızlıkta GLES'e düşüyor, ama bu
  makinede çalıştırılarak kanıtlanamadı.

## Doğrulama paketi (Test → Doğrulama)

Uygulamanın kendi ölçümünü sınamak için ayrı bir sekme var. Hiçbir adım puan
uydurmaz; ölçülemeyen şey raporda "yapılamadı" olarak görünür.

Sırasıyla çalışan bölümler:

| Bölüm | Ne yapıyor |
|---|---|
| Bilinen cevaplar | 8 iş yükünün her biri `verify()` ile kontrol edilir (FIPS-197 AES vektörü, aç-kapa eşitliği, referans matris, bilinen JSON çıktısı…). |
| Gürültü tabanı | Boş görevin süresi ve `clock_gettime` çözünürlüğü ölçülür; en kısa gerçek ölçümün en az 100× / 1000× üzerinde olması istenir. |
| Derleyici hilesi | Aynı ALU çekirdeği iki ayrı TU'da `-O2` ve `-O3` ile derlenir (`kernel_o2.cpp` / `kernel_o3.cpp`), süre oranı 0,70–1,50× aralığında olmalıdır. |
| Tekrarlanabilirlik | 7 iş yükü × tek/çok çekirdek, her biri **3 ısınma + 30 ölçüm**. Ortanca, ortalama, SS, CV, %95 GA (Student-t), IQR aykırı sayısı. Eşik: tek çekirdek CV < %2, çok çekirdek CV < %3. |
| Sıcaklık kapısı | Her tekrardan önce batarya sıcaklığı başlangıç değerinin ±2 °C içinde olmalı; dışındaysa 30 sn'ye kadar soğuma beklenir, hâlâ dışındaysa o tekrar **geçersiz** sayılır ve raporlanır. |
| Ölçekleme | Boyut 1×/2×/4×/8× doğrusallığı (R², en büyük sapma) ve iş parçacığı 1/2/4/8 hız artışı. Doğrusallık bozuksa "veri önbelleğe sığıyor" uyarısı. |
| Çekirdek | `sched_setaffinity` ile en yüksek frekanslı çekirdeğe sabitleme vs serbest ölçüm; `sched_getcpu` ile ölçümün hangi çekirdeğe düştüğü. |
| Bellek | 32 KiB / 256 KiB / 4 MiB / 128 MiB çalışma kümesi; GB/s eğrisi basamaklı olmalı. |
| Depolama | Sıralı ve rastgele 4K, **önbellekli ve O_DIRECT** ayrı ayrı; O_DIRECT reddedilirse "desteklenmiyor" yazılır. |
| GPU | 120 kare GPU zaman damgasıyla ölçülür: ortanca, %1 düşük, %0,1 düşük, kare süresi CV'si. Ayrıca 5 örnek piksel gölgelendiricinin analitik sonucuyla karşılaştırılır, yenileme hızı kilidi ve Vulkan/GLES karşılaştırması yapılır. |
| Puanlama | Referans = tam 1000 kontrolü, ağırlık toplamı, ±5 puan ağırlık duyarlılığı ve sıra değişimi. Girdiler `-scoring.csv` olarak yazılır. |
| Ortam | Paket adı, imza özeti (repackaging için), root/emülatör/debug, governor, güç tasarrufu, ısıl durum. |

Çıktılar `cacheDir/shared/` altına yazılır ve ekrandan paylaşılabilir:

- `validation-<zaman>.md` — özet: hangi test güvenilir, hangisi değil, nedeni ve önerilen düzeltme.
- `validation-<zaman>-raw.csv` — her ham ölçüm.
- `validation-<zaman>-stats.csv` — n, ortanca, ortalama, SS, CV, %95 GA, aykırı.
- `validation-<zaman>-scoring.csv` — puanlama girdileri ve uygulamanın hesapladığı sonuç.

### Puanlamanın bağımsız doğrulaması

```powershell
# Formülün kendi testleri (referans=1000, %20 iş yükü ve %30 kategori
# yavaşlatma, ±5 puan ağırlık, eksik grup normalizasyonu):
powershell -ExecutionPolicy Bypass -File tools\verify_scoring.ps1

# Uygulamanın yazdığı dosyayı satır satır yeniden hesapla:
powershell -ExecutionPolicy Bypass -File tools\verify_scoring.ps1 -File validation-...-scoring.csv
```

Aynı formülün Python sürümü `tools/verify_scoring.py` dosyasında
(`--self-test`, `--file`). Bu derleme makinesinde Python kurulu olmadığı için
Python betiği burada çalıştırılamadı; aynı kontroller PowerShell ikiziyle
çalıştırıldı ve geçti. Kotlin tarafı da
`app/src/test/.../ScoringVerificationTest.kt` ile bağımsız hesaplanmış değerlere
karşı sınanıyor (12 test), CSV eşleme/Spearman için `CrossDeviceTest.kt` (7 test);
toplam 19 test `:app:testDebugUnitTest` ile geçiyor.

Doğrulanan sayısal sonuçlar (bu makinede):

- Referans cihaz tam **1000** puan.
- Tek iş yükü %20 yavaşlarsa toplam puan **%4,222912** düşer
  (`0,40 × (1 − √0,8)`), ölçülen ile birebir aynı.
- Tek kategori (GPU) %30 yavaşlarsa toplam **%9,000000** düşer.
- Eksik grup puanı sıfırlamaz; kalan ağırlıklar yeniden normalize edilir.

### Bu paketin kapsamadığı (ve nedeni)

- **20 °C / 30 °C sabit ortam**: uygulama ortam sıcaklığını kontrol edemez.
  Başlangıç batarya sıcaklığı kaydedilir; koşular buna göre gruplanır.
- **0 / 5 / 15 arka plan uygulaması**: Android bir uygulamanın başka
  süreçleri saymasına izin vermez. Yalnızca kendi sürecinin yükü ölçülebilir.
- **Yeniden paketleme (imza değiştirme) testi**: cihaz kendi APK'sını yeniden
  imzalayamaz. Paket adı + imza özeti her koşuyla kaydedilir; iki APK'nın
  karşılaştırması kullanıcı tarafından yapılır.
- **Geekbench/AnTuTu Spearman korelasyonu**: aynı cihazların hem referans hem
  DeviceScope puanı gerekir. Tek cihazda n=1 olduğundan Spearman tanımsızdır;
  en az 5–8 cihazlık CSV gerekir.
- **15 dakikalık ısıl dayanıklılık koşusu** ve **Kinetik 10 tam koşu**: ayrı,
  uzun süreli testler; bu doğrulama paketinin içinde değil.
- Depolama testi 64 MB dosya kullanır (RAM'den büyük değil); sayfa önbelleği
  O_DIRECT ile atlanır ve iki sonuç ayrı raporlanır.

### Cihazlar arası korelasyon (CSV)

Doğrulama sekmesindeki **Cihazlar arası tutarlılık** kartı, bir CSV dosyası seçip
aynı cihazların referans ve DeviceScope puanlarını Spearman ρ ile karşılaştırır.
Sütunlar anlamsal etikete göre eşlenir (`single`, `multi`, `total`, `cpu`, `gpu`,
`mem`, `ux`); hedef toplam için > 0,9, kategoriler için > 0,8. Virgül ve noktalı
virgül ayraçları, Türkçe ondalık virgülü ve tırnaklı alanlar desteklenir.
5 cihazdan az veri varsa korelasyon **hesaplanmaz** ve neden yazılır; sıralamadan
en çok sapan ilk 5 cihaz rank farkıyla listelenir.


