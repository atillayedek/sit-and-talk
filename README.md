# Sit & Talk

Kotlin + Jetpack Compose ile yazılmış Android sosyal sohbet uygulaması: anonim sesli eşleşme, isteğe bağlı
görüntülü eşleşme, metinle tanışma, arkadaşlık ve mesajlaşma, keşfet akışı ve hikâyeler, bağımsız sesli
odalar, hediyeler ve premium, bildirimler, moderasyon ve yönetim paneli.

Arka uç Supabase (Auth, Postgres + RLS, Realtime, Storage, Edge Functions); ses/görüntü Agora RTC ve
sunucunun ürettiği kısa ömürlü token'larla çalışır. Uygulamada sahte kullanıcı, sahte oda veya örnek içerik
yoktur: veritabanı boşsa ekranlar gerçek boş durumları gösterir.

## Durum

Kod tamamlandı ve CI'da derleniyor; arka uç TalkRoom Supabase projesine kuruldu. **Henüz gerçek bir cihazda
uçtan uca doğrulanmadı** ve bazı sunucu secret'ları (Agora certificate, push, ödeme) girilmeyi bekliyor. Ayrıntılar: [docs/verification.md](docs/verification.md),
[docs/known-limitations.md](docs/known-limitations.md), [docs/feature-matrix.md](docs/feature-matrix.md).

## Proje yapısı

```
app/                   Uygulama kabuğu: başlangıç durumu, navigasyon, deep link, push, WorkManager
core/model             DTO'lar, hata kodları, yapılandırma modeli
core/network           Supabase istemcisi, şifreli oturum, RPC yardımcıları, güvenli log
core/database          Room: sohbet önbelleği ve gönderim kuyruğu (outbox)
core/security          Android Keystore ile AES-GCM
core/rtc               Agora sarmalayıcısı ve ön plan servisi
core/designsystem      Tema, bileşenler, logo, hata metinleri
core/data              Depolar (repository) ve aktif görüşme/oda denetleyicileri
feature/*              auth, profile, matching, call, rooms, friends, chat, feed,
                       notifications, wallet, settings, moderation
supabase/migrations    Şema, RLS, RPC'ler, depolama, zamanlanmış işler
supabase/functions     Edge Functions (agora-token, verify-purchase, play-rtdn, send-push, …)
supabase/tests         PGlite üzerinde migration + RLS/davranış testleri
web/                   Tanıtım, yasal metinler, hesap silme, destek, e-posta geri dönüş sayfası
docs/                  Mimari, kurulum, güvenlik, yayın belgeleri
```

## Hızlı başlangıç

1. JDK 21 ve Android SDK kurulu olmalı.
2. Varsayılan olarak `app/public-config.properties` içindeki **genel** değerler (TalkRoom Supabase projesi
   URL'si, publishable key, Agora App ID) kullanılır. Farklı bir arka uç için kök dizinde
   `local.properties` oluştur (git'e girmez):
   ```properties
   SUPABASE_URL=https://<project-ref>.supabase.co
   SUPABASE_PUBLISHABLE_KEY=sb_publishable_...
   AGORA_APP_ID=<Agora App ID>
   ```
   Yalnızca **genel** değerler buraya yazılır. Supabase service role anahtarı ve Agora App Certificate
   **asla** uygulamaya konmaz; bunlar Edge Function secret'larıdır ([docs/security.md](docs/security.md)).
3. `./gradlew :app:assembleDebug`

Yapılandırma eksikse uygulama çökmeden "Uygulama yapılandırılmamış" ekranını gösterir.

Arka uç kurulumu: [docs/supabase-setup.md](docs/supabase-setup.md), [docs/agora-setup.md](docs/agora-setup.md).

## CI

- `.github/workflows/android.yml` — debug APK (artifact), lint, birim testleri, gitleaks; keystore
  secret'ları varsa imzalı release APK/AAB.
- `.github/workflows/supabase.yml` — migration + RLS testleri, Edge Function tip kontrolü; elle
  tetiklenen `deploy` işi.

## Belgeler

[Mimari](docs/architecture.md) · [Tasarım sistemi](docs/design-system.md) ·
[Özellik matrisi](docs/feature-matrix.md) · [Supabase](docs/supabase-setup.md) ·
[Agora](docs/agora-setup.md) · [Auth geri dönüşleri](docs/auth-callbacks.md) ·
[Güvenlik](docs/security.md) · [Ödemeler](docs/billing.md) · [Yayın](docs/release.md) ·
[Doğrulama](docs/verification.md) · [Bilinen sınırlar](docs/known-limitations.md)
