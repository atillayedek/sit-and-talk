# Bilinen sınırlar

## Dış erişim / yapılandırma eksikleri

- **Supabase:** Şema ve 7 Edge Function "TalkRoom" projesine (`xcqcaaejpjumhhaqqwmd`) kuruldu (30 Eylül 2026).
  Eski TalkRoom prototipinin tabloları silinmeden `talkroom_legacy` şemasına taşındı; eski `talkroom`
  fonksiyonu yerinde duruyor ama bu tablolara artık API üzerinden erişemez.
- **Eksik function secret'ları:** `AGORA_APP_CERTIFICATE`, `AGORA_APP_ID`, `INTERNAL_HOOK_SECRET` (değeri
  veritabanında hazır), FCM ve Play değerleri Dashboard'dan girilmeli; bağlantı aracı secret yazamıyor.
  Certificate girilene kadar görüşme ve odalarda ses bağlantısı kurulamaz (`service_not_configured`).
- **Auth yönlendirmeleri:** Redirect URL listesi Dashboard'dan eklenmeli ([auth-callbacks.md](auth-callbacks.md));
  eklenmezse doğrulama bağlantısı uygulamayı açmaz. Sızdırılmış şifre koruması (HaveIBeenPwned) Dashboard'dan açılmalı.
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
