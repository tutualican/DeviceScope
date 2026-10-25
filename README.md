# DeviceScope

Android cihazın donanım bilgilerini, canlı sıcaklıklarını, erişilebilen geçmiş
kayıtlarını ve üç bağımsız performans testini (Mikyas, Kinetik, Maraton) gösteren
yerel uygulama. Ağ/tarayıcı özelliği kaldırıldı; tüm kontrol telefonun kendi
arayüzündedir.

**Depo:** https://github.com/tutualican/DeviceScope
**Sürüm:** v1.2.0 · APK sürüm ekinde (`DeviceScope.apk`, debug imzalı)

Yerel kopya: `C:\Users\Alican\Desktop\DeviceScope.apk`

Arayüz **sakin katmanlı yüzey** sistemidir: tek yönlü çok yumuşak gölge, kılcal
çizgi kenarlık ve çok hafif cam parlaklığı. Neumorphism'in "tek parçadan oyulmuş"
hissi korunur ama çift gölge, her blokta büyük harfli mikro etiketler ve üst üste
kutu katmanları kaldırıldı. Apple Intelligence diline yaklaşan üç kural:

- **Tek çapa:** her ekranın bir büyük başlığı ve tek bir baskın sayısı var.
- **Aşamalı açılım:** ayrıntılar `SoftDisclosure` kartlarında kapalı gelir; tüm
  ölçümler durur, ekran kalabalıklaşmaz.
- **Renk sadece anlam taşıdığında:** gradyan halka yalnızca manşet puanda,
  turkuaz/mavi spektrum ise dekoratif arka plan ışımasında kullanılır.

Koyu ve açık tema var; başlıktaki düğmeyle değişir.

## Sekmeler

| Sekme | İçerik |
|---|---|
| Cihaz | Üretici, marka, model, SoC, CPU kümeleri/frekansları, GPU (gerçek OpenGL sorgusu), RAM, depolama çipi, batarya, ekran paneli, kameralar, anakart, yazılım — her bölüm ilk 3 alanı gösterir, gerisi tek dokunuşla açılır |
| Sıcaklık | En sıcak bileşen manşette; işlemci / batarya / hissedilen için üç halka; bileşen maksimumları, çekirdek frekansları ve ham sensör tablosu katlanmış kartlarda |
| Geçmiş | İlk açılış, son önyükleme, ROM/çekirdek derleme tarihleri, pil döngüsü ve kapasite kaybı, panel üretim haftası (EDID varsa), çalışma süresi |
| Test | Beş segment: **Maraton** (≥15 dk birleşik uzun koşu, koşul kapısı ve 1–3 koşu tekrarı), **Mikyas** (CPU/bellek/disk), **Kinetik** (GPU/arayüz/ısıl dayanım), **Kıyas** (kayıtlı sonuçlar), **Doğrulama** (ölçümün kendisini sınayan paket) |

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

### Maraton — uzun süreli birleşik koşu

Tek koşuda Geekbench tarzı tek/çok çekirdek, bellek, depolama, Vulkan GPU,
ekranda AnTuTu tarzı görsel sahne ve ısıl kararlılık ölçülür.

- **Koşul kapısı (yeni):** ölçüm başlamadan önce pil seviyesi, şarj durumu,
  güç tasarrufu, uçak modu/Wi-Fi/mobil veri, parlaklık, ekran zaman aşımı,
  platform ısıl durumu (`getCurrentThermalStatus`) ve ısıl boşluk
  (`getThermalHeadroom`) okunur. Batarya sıcaklığı **3 dakika** boyunca izlenir;
  0,3 °C'den fazla oynarsa test **başlamaz**. Uygun olmayan koşulda kullanıcı
  açıkça "yine de başlat" derse nedenler sonuç dosyasına `forced=true` olarak
  yazılır. Tüm koşullar ve kaynakları (OK/WARN/BLOCK) sonuç dosyasındadır; iki
  sonuç ancak koşulları eşleşiyorsa kıyaslanabilir.
- **Süre bir alt sınırdır.** 15/30/45 dk seçilir; motor `coerceAtLeast(15)`
  uygular ve sürdürülen döngü duvar saati hedefe ulaşana kadar devam eder.
  15 dakikadan kısa bir koşu üretilemez. İstenirse **3 koşu** arka arkaya
  çalışır (aralarda 5 dk soğuma), medyan ve aralık yazılır; yayılım %5'i aşarsa
  "güvenilmez" uyarısı verilir.
- **Sıra:** doğrulama → tek çekirdek (7 iş yükü) → çok çekirdek → bellek → 128 MB
  depolama → Vulkan compute + çevrimdışı sahne → ekranda görsel sahne (20 sn,
  gerçek kareler) → dönüşümlü tek/çok çekirdek sürdürülen döngü (kalan süre,
  tipik olarak ~12 dk) → 2 dakikalık **soğuma eğrisi** → rapor.
- **Toplam** = yedi bileşenin ağırlıklı ortalaması: tek 0,18 · çok 0,22 ·
  bellek 0,08 · depolama 0,08 · GPU 0,20 · görsel/arayüz 0,12 · kararlılık 0,12.
  Bir bileşen ölçülemezse o ağırlık düşer, kalanlar normalize edilir; eksik
  bileşen sıfır puan gibi sayılmaz.
- **Isınma:** sürdürülen döngünün ilk **45 saniyesi** ısınma sayılır ve hiçbir
  kararlılık hesabına girmez; kaç turun dışlandığı sonuçta yazar.
- **Kararlılık** iki parçaya ayrıldı: `0,60 × hız koruma + 0,40 × dalgalanma`.
  Hız koruma = **son pencerenin ortancası / en iyi sürdürülen pencerenin
  ortancası** (ilk üçte bir referansı kaldırıldı). Oran %100'ü aşarsa kırpılır ve
  "başlangıç yavaş" işaretlenir. Dalgalanma puanı tek çekirdek ve çok çekirdek
  **CV'lerinden ayrı ayrı** hesaplanır. En az 6 geçerli örnek yoksa kararlılık
  üretilmez (puan 0 değil, eksik sayılır). Sonuç 0–1100.
- **Kirli tur:** /proc/stat ve /proc/self/stat farkından başka süreçlerin meşgul
  ettiği çekirdek sayısı çıkarılır; ≥0,75 çekirdek veya ekran kapandıysa tur
  "kirli" işaretlenir ve kararlılıktan çıkarılır. Kaç turun çıkarıldığı ve nedeni
  sonuçta yazar.
- **Isıl kanıt:** her turda platform ısıl durumu, ısıl boşluk, CPU en sıcak zone
  sıcaklığı, CPU ortalama sıcaklığı ve **çekirdek başına frekanslar** kaydedilir.
  Hangi thermal zone'un kullanıldığı ve değerin "en sıcak" mı "ortalama" mı
  olduğu dosyada açıkça belirtilir; kartlar aynı seriden beslenir.
- **Enerji:** `BATTERY_PROPERTY_CURRENT_NOW × gerilim` ile yaklaşık watt,
  batarya tasarım kapasitesi (mAh), harcanan Wh ve **W / 1000 puan** verimliliği
  yazılır. Değer yaklaşıktır ve kaynağı dosyada belirtilir.
- **Kayıt:** `filesDir/results/maraton_<zaman>.json` — şema v4: koşullar,
  denge penceresi, tüm bileşen ölçümleri, sürdürülen tur serisi (kirli işaretli),
  sıcaklık/frekans/ısıl durum serisi, soğuma eğrisi, enerji, doğrulama, uygulama
  ve iş yükü sürümü. 3 koşuda ayrıca `seri_maraton_<zaman>.json` (medyan, aralık,
  yayılım) yazılır.
- **Test modu:** koşu başlarken uygulama tam ekran test moduna girer: görsel
  sahne, ekran açık, pencere parlaklığı %100, ön plan servisi ve durdurma
  düğmeli bildirim. Rootsuz bir uygulama **diğer uygulamaları askıya alamaz**;
  bu yüzden ekranın açık kaldığı her sürdürülen turda (~2,5 sn)
  `PowerManager.isInteractive` ile kontrol edilir ve rapora yazılır. Telefon
  kilitliyse veya başka bir uygulama öndeyse görsel sahne kare üretemez: kod
  zaman aşımıyla bunu algılar, sahneyi "ölçülemedi" işaretler ve puan uydurmaz
  (arayüz puanı sayısal kare ölçümüne düşer, o da yoksa bileşen eksik sayılır).

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
- Maraton süre alt sınırı (istenen 1/5/14/−30 dk → 15 dk), ağırlık toplamı,
  ağırlıklı ortalama, eksik bileşen normalizasyonu, ısınma/kirli tur dışlama,
  en iyi pencereye göre hız koruma, CV'nin tür bazında ayrılması ve 3 koşu
  medyanı `MarathonConfigTest` + `StabilityTest` ile sınandı.
- Derlenen APK'da `TestModeService` `foregroundServiceType=specialUse` olarak
  manifestte; ağ izni yok (`INTERNET` kaldırıldı) ve `MonitorService`/panel
  dosyaları silindi. Sonuç şeması v4; yeni Kotlin/JSON dosyalarında U+FFFD veya
  mojibake yok.

Doğrulanmadı (bu makinede mümkün değil — x86_64 emülatör için AEHD/WHPX yok,
yönetici yetkisi yok, bağlı telefon yok):

- Cihaz üzerinde çalışma, izin akışı, testlerin gerçek süreleri ve skor
  tekrarlanabilirliği (%2–3 hedefi) saha ölçümüyle teyit edilmedi.
- Vulkan aygıt/komut zincirinin gerçek bir sürücüde hatasız çalışması; her adım
  `VkResult` kontrolüyle korunuyor ve başarısızlıkta GLES'e düşüyor, ama bu
  makinede çalıştırılarak kanıtlanamadı.
- **Maraton'un 15 dakikalık gerçek koşusu**: süre alt sınırı, ağırlıklar,
  ısınma/retention/CV kuralları ve 3 koşu medyanı kodla ve birim testleriyle
  doğrulandı; ancak bu makinede (hızlandırmasız emülatör, bağlı telefon yok)
  15 dakikalık saha koşusu çalıştırılamadı. Görsel sahnenin gerçek kare üretmesi,
  telefonun test modunda ekranı açık tutması, `/proc/stat` kirlilik ölçümü,
  `getThermalHeadroom`/`getCurrentThermalStatus`, batarya akımı ve tasarım
  kapasitesi cihaz üzerinde teyit edilmedi; okunamazsa sonuçta "ölçülemedi"
  yazar. Isıl denge için 3 dakikalık gerçek bekleme de sahada sınanmadı.

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
karşı sınanıyor (12 test), CSV eşleme/Spearman için `CrossDeviceTest.kt` (7 test)
ve 15 dakika alt sınırı/ağırlık normalizasyonu için `MarathonConfigTest.kt`
(7 test); toplam 26 test `:app:testDebugUnitTest` ile geçiyor.

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
- **15 dakikalık ısıl dayanıklılık koşusu**: artık **Maraton** paketinin
  içindedir (motor süreyi alt sınır olarak uygular), ama bu makinede 15 dakikalık
  gerçek bir koşu çalıştırılamadı; süre garantisi kodla ve birim testleriyle
  sınanıyor, saha ölçümüyle değil. **Kinetik 10 tam koşu** da ayrı bir saha
  çalışmasıdır; bu doğrulama paketinin içinde değil.
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


