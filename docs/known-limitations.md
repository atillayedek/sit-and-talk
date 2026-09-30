# Bilinen sınırlar

## Dış erişim / yapılandırma eksikleri

- **Supabase:** Bu çalışmada kullanılan Supabase bağlantısı "TalkRoom" projesini görmüyor; migration ve
  fonksiyonlar yayınlanmadı. Yayın için [supabase-setup.md](supabase-setup.md).
- **Agora:** App ID ve certificate kullanıcı tarafından sağlandı, ancak depoya konmadı. App ID derleme
  değişkeni, certificate yalnızca Supabase function secret'ı olarak girilmeli ([agora-setup.md](agora-setup.md)).
- **Firebase:** Proje yok; push bildirimleri kapalıdır. Uygulama bunu gizlemez: ayarlarda push "kullanılamıyor"
  görünür. Arka planda gelen arama bildirimi push olmadan çalışmaz (uygulama açıkken Realtime ile çalışır).
- **Google Play:** Ürünler, servis hesabı ve RTDN yok; `premium` ve `gifts` bayrakları kapalı.
- **Google ile giriş:** OAuth istemci kimliği yok; bayrak kapalı.
- **İmzalama ve App Links:** Keystore ve alan adı yok; `assetlinks.json` yayınlanmadı. Auth bağlantıları
  özel şema (`sitandtalk://`) kullanır.
- **Yasal metinler:** `web/` altındaki metinler işletmecinin unvanı/adresi olmadan yazıldı; yayından önce
  işletmeci ve hukuk danışmanı tarafından tamamlanmalı (KVKK aydınlatma metni dahil).

## Ürün sınırları

- Görüntülü görüşmede içerik moderasyonu (otomatik çıplaklık tespiti vb.) yoktur; bildirim + insan
  moderasyonuna dayanır.
- Agora co-host authentication kapalıysa dinleyici rolü yalnızca istemcide uygulanır.
- Customer ID/Secret yoksa odadan atılan kişi token süresi (en fazla 1 saat) dolana kadar kanalda kalabilir.
- `pg_cron` yoksa süresi dolan eşleşmelerin temizliği yalnızca aktif kullanıcıların kalp atışlarıyla olur.
- Arayüz yalnızca Türkçe; metinler kaynak dosyalarında olduğundan çeviri eklenebilir.
- Gerçek cihaz testi yapılmadı; performans, pil ve ağ geçişi davranışı ölçülmedi.
