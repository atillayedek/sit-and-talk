# Özellik matrisi

Durum sütunları:
- **Kod**: istemci + sunucu kodu yazıldı.
- **Test**: sunucu davranışı PGlite testleriyle kontrol edildi (`supabase/tests/run.mjs`).
- **Cihaz**: gerçek cihazda uçtan uca denendi. **Henüz hiçbiri denenmedi** — arka uç yayınlanmadı.
- **Dış bağımlılık**: çalışması için gereken harici yapılandırma.

| Özellik | Kod | Test | Cihaz | Dış bağımlılık |
| --- | --- | --- | --- | --- |
| E-posta ile kayıt / giriş / doğrulama (PKCE) | ✅ | — | ❌ | Supabase Auth + redirect URL'leri |
| Şifre sıfırlama | ✅ | — | ❌ | Supabase Auth |
| Google ile giriş | ✅ | — | ❌ | Google OAuth istemcisi, `google_sign_in` bayrağı (kapalı) |
| Profil oluşturma (18+), ilgi alanları, diller | ✅ | ✅ | ❌ | — |
| Anonim sesli eşleşme, süre, karşılıklı uzatma | ✅ | ✅ | ❌ | Agora App ID + certificate |
| Karşılıklı "tekrar konuş" → arkadaşlık | ✅ | ✅ | ❌ | — |
| Görüntülü eşleşme (isteğe bağlı, karşılıklı onay) | ✅ | ✅ | ❌ | Agora, `video_matching` bayrağı (kapalı) |
| Metinle tanışma | ✅ | ✅ | ❌ | — |
| Arkadaş listesi, istekler, engelleme | ✅ | ✅ | ❌ | — |
| Arkadaşı doğrudan arama | ✅ | ✅ | ❌ | Agora; arka planda çalma için FCM |
| Mesajlaşma (metin, resim, sesli not, okundu, yazıyor, grup) | ✅ | ✅ | ❌ | — |
| Çevrimdışı mesaj kuyruğu | ✅ | — | ❌ | — |
| Keşfet akışı, paylaşım, yorum, beğeni, kaydetme | ✅ | ✅ | ❌ | — |
| Hikâyeler (24 saat) | ✅ | — | ❌ | — |
| Sesli odalar: roller, el kaldırma, davet, sessize alma, atma | ✅ | ✅ | ❌ | Agora; anında atma için Customer ID/Secret |
| Şifreli / davetli odalar, favoriler, etkinlik planlama | ✅ | ✅ | ❌ | — |
| Push bildirimleri | ✅ | — | ❌ | Firebase projesi + `FCM_*` secret'ları + `pg_net` |
| Uygulama içi bildirimler | ✅ | ✅ | ❌ | — |
| Jeton, hediye, premium | ✅ | ✅ (defter) | ❌ | Play Console ürünleri, servis hesabı, `gifts`/`premium` bayrakları (kapalı) |
| Bildirme, kısıtlama, itiraz | ✅ | ✅ | ❌ | — |
| Yönetim paneli + denetim kaydı | ✅ | ✅ | ❌ | İlk yöneticinin SQL ile atanması |
| Veri dışa aktarma | ✅ | ✅ | ❌ | — |
| Hesap silme | ✅ | — | ❌ | `delete-account` fonksiyonu |
| Bakım modu, zorunlu güncelleme, duyuru | ✅ | ✅ | ❌ | — |
| Web: tanıtım, yasal metinler, hesap silme, destek, auth köprüsü | ✅ | — | — | Statik barındırma, işletmeci bilgileri |
