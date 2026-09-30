# Doğrulama

## Yapılan (otomatik)

| Kontrol | Nasıl | Sonuç |
| --- | --- | --- |
| Android derleme, lint, birim testleri (bağlantı yönlendirme, zaman damgası ayrıştırma, canlı `app_bootstrap` yanıtının çözümlenmesi) | GitHub Actions `android.yml` | Durum PR'daki son çalışmada; debug APK artifact olarak indirilebilir |
| Gizli bilgi taraması | gitleaks v8.28.0 | CI'da |
| Migration'lar sıfırdan kurulum + RLS/RPC davranışı | PGlite, `supabase/tests/run.mjs` (31 kontrol) | Geçiyor |
| Edge Function tip kontrolü ve lint | `deno check`, `deno lint` | Geçiyor |
| Agora token üretimi | `agora-token` kütüphanesiyle yerel üretim | Token üretildi (Agora sunucusuna karşı denenmedi) |
| Canlı veritabanı kurulumu | TalkRoom projesine 12 migration; MD5 ile doğrulanmış kaynak | 58 tablo, hepsinde RLS; `anon` yalnızca `app_bootstrap`; 5 bucket; pg_cron işi |
| Canlı Edge Function açılış testi | 7 fonksiyona kimliksiz istek | Hepsi kendi hata gövdesiyle yanıt verdi (401 / 503 yapılandırma eksik) |

## Yapılmayan

- Arka uç kuruldu ancak gerçek kullanıcı akışlarıyla (kayıt, eşleşme, oda) **denenmedi**.
- Gerçek cihazda **hiçbir akış** denenmedi. "Derleniyor" "çalışıyor" anlamına gelmez.
- Agora, FCM ve Google Play entegrasyonları gerçek servislere karşı denenmedi.

## Cihaz test listesi (iki gerçek telefon, gerçek hesaplar)

Test için üretim veritabanına sahte kullanıcı veya içerik eklenmez; iki gerçek test hesabı kullanılır.

1. Kayıt → doğrulama e-postası → bağlantı uygulamayı açar → profil oluşturma (17 yaş reddedilir).
2. Şifre sıfırlama bağlantısı → yeni şifre → giriş.
3. İki cihazda "Konuş" → eşleşme → iki taraf kabul → ses iki yönlü → süre sayacı iki cihazda aynı.
4. Uzatma: tek taraf isteyince uzamaz, iki taraf isteyince uzar.
5. Görüşme sonu "tekrar konuş" iki taraf → arkadaş listesinde görünür.
6. Uygulamayı görüşme sırasında arka plana al → ön plan bildirimi → "Bitir" görüşmeyi sonlandırır.
7. Uçak modu → mesaj yaz → "gönderiliyor" → ağ gelince gider, çift kayıt yok.
8. Oda aç → diğer cihaz dinleyici olarak girer → el kaldırır → konuşmacı yapılır → sessize al → at.
9. Dinleyici cihazda mikrofonun yayınlanmadığı doğrulanır (co-host authentication açıkken).
10. Engelle → eşleşme, arama, mesaj engellenir.
11. Bildir → yönetim panelinde görünür → karar → itiraz.
12. Veri dışa aktarma dosyası açılır; hesap silme → yeniden giriş reddedilir.
13. Push: uygulama kapalıyken mesaj ve arama bildirimi gelir, dokununca doğru ekran açılır.
14. Satın alma (Play dahili test): doğrulama olmadan bakiye artmaz; iade sonrası RTDN ile düşer.
