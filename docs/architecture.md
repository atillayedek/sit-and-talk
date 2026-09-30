# Mimari

## Katmanlar

```
feature/* (Compose ekranları + ViewModel)
      │  StateFlow<UiState>, tek yönlü veri akışı
core/data (repository'ler, ActiveCallController, ActiveRoomController, MatchmakingController)
      │
core/network (Supabase: Auth, Postgrest RPC, Realtime, Storage, Functions)   core/rtc (Agora)
core/database (Room: önbellek + outbox)   core/security (Keystore)
      │
Supabase Postgres (RLS + security definer RPC'ler)  ←→  Edge Functions  ←→  Agora / FCM / Google Play
```

- **MVVM + Hilt.** Her ekran bir `@HiltViewModel` ve değişmez bir UI durumu kullanır. Hatalar
  `AppException(code)` olarak taşınır ve `errorMessage()` ile Türkçe metne çevrilir; ham istisna metni
  kullanıcıya gösterilmez.
- **Sunucu gerçeğin kaynağıdır.** Eşleşme, süre, uzatma, oda rolleri, bakiye, premium ve moderasyon
  kararları sunucuda verilir. İstemci yalnızca RPC çağırır ve Realtime değişikliklerini dinler.
- **Room yalnızca önbellek/outbox içindir.** Çevrimdışı yazılan mesajlar `message_outbox`'a girer,
  sabit bir UUID ile gönderilir; yeniden deneme çift kayıt üretmez (`duplicate` başarı sayılır).
  WorkManager ağ gelince kuyruğu boşaltır.

## Uygulama kabuğu

`AppStateViewModel` başlangıç durumunu yönetir: `ConfigMissing → Loading → SignedOut |
PasswordRecovery | NeedsOnboarding | Suspended | UpdateRequired | Maintenance | Ready`.
`app_bootstrap` RPC'si sürüm zorunluluğu, bakım, bayraklar, profil tamamlığı ve askı durumunu tek
çağrıda döndürür. `Ready` durumunda: push kaydı, ilgi alanı kataloğu, yarım kalan görüşmenin geri
yüklenmesi, bildirim sayacı (Realtime), dakikalık çevrimiçi sinyali ve outbox gönderimi başlar.

Navigasyon: Keşfet · Odalar · **Konuş** (ortada) · Mesajlar · Profil. Aktif görüşme veya oda varken
ekranın üstünde "geri dön" çubuğu görünür. Yeni bir görüşme oturumu (eşleşme kabulü, arama, gelen arama)
görüşme ekranını otomatik açar.

## Eşleşme ve görüşme

1. `join_queue` → kuyruğa girilir; `queue_heartbeat` (4 sn) hem canlılığı bildirir hem eşleştirmeyi
   tetikler. Eşleştirme `FOR UPDATE SKIP LOCKED` ile atomiktir; engellenmiş, yakın zamanda eşleşmiş veya
   filtreye uymayan kişiler eşleşmez.
2. İki taraf da kabul edince `call_sessions` oluşur. Süre, iki taraf da Agora kanalına katılıp
   `mark_rtc_joined` çağırınca başlar.
3. Uzatma iki tarafın da istemesiyle olur; mod değişikliği (ses ↔ görüntü) karşılıklı onay ister.
4. Görüşme bitince iki taraf da "tekrar konuşmak istiyorum" derse arkadaş olurlar ve kimlikler açılır.
5. Kopan istemciler kalp atışı ile, pg_cron varsa dakikalık iş ile temizlenir.

## Odalar

Sahip, moderatör, konuşmacı, dinleyici rolleri; el kaldırma, konuşmaya davet, sessize alma, atma/yasaklama.
Şifreli odaların şifresi `app_private.room_secrets` içinde bcrypt ile saklanır. Sahip ayrılırsa sahiplik
moderatöre, yoksa konuşmacıya geçer; kimse yoksa oda kapanır. Etkinlik planlama ve hatırlatma vardır.

## Bildirimler

Veritabanı bildirim satırı yazar ve `app_private.push_outbox`'a iş ekler; `pg_net` ile `send-push`
tetiklenir, FCM v1 ile yalnızca veri içeren mesaj gider. Uygulama metni cihazda yerelleştirir.
