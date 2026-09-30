# Güvenlik

## Sırlar

| Değer | Nerede durur | Uygulamada? |
| --- | --- | --- |
| Supabase URL, publishable/anon key | `local.properties` / Actions variable | Evet (genel değer; yetkiyi RLS belirler) |
| Supabase service role key | Yalnızca Edge Function ortamı (platform sağlar) | **Hayır** |
| Agora App ID | `local.properties` / Actions variable + function secret | Evet |
| Agora App Certificate | Function secret veya Supabase Vault (yalnızca `service_role` okuyabilir) | **Hayır** |
| FCM / Play servis hesabı JSON | Yalnızca function secret | **Hayır** |
| İmzalama keystore'u ve şifreleri | Actions secret (base64) | **Hayır**, depoya da girmez |

`.gitignore` `local.properties`, `*.jks`, `*.keystore`, `*.apk`, `*.aab` ve `.env` dosyalarını dışlar.
CI her çalışmada gitleaks ile depo taraması yapar. Örnek yapılandırma dosyaları yalnızca boş değer içerir.

## Yetkilendirme

- Her tabloda RLS açık; tablolara doğrudan yazma çoğunlukla kapalıdır, değişiklikler doğrulama yapan
  `security definer` RPC'lerden geçer (`set search_path = ''`).
- İç yardımcılar erişilemeyen `app_private` şemasındadır. Son migration tüm varsayılan yetkileri geri alır
  ve yalnızca açıkça listelenenleri verir; `anon` rolü yalnızca `app_bootstrap` çağırabilir.
- Yönetici, premium, bakiye ve moderasyon yetkileri istemciden alınamaz: hepsi sunucu tablolarından
  okunur. Yönetim paneli her işlemde `admin_roles` kontrolü yapar ve `audit_logs`'a yazar.
- Engellenen kullanıcılar birbirini arayamaz, eşleşemez, mesajlaşamaz, bildirim üretemez.
- Anonim eşleşmelerde karşı tarafın kullanıcı kimliği istemciye gönderilmez; mesajlar yalnızca
  "slot" numarasıyla taşınır.
- Hız sınırları (`rate_limits`) sunucuda uygulanır; hata `rate_limited` ve bekleme süresiyle döner.

## İstemci

- Oturum Android Keystore anahtarıyla AES-GCM şifrelenmiş olarak yedeklenmeyen dizinde tutulur;
  `allowBackup=false`.
- Loglar yalnızca debug derlemede açıktır ve token, e-posta, mesaj içeriği yazmaz (`SafeLog`).
- Yüklenen resimler yeniden kodlanır (EXIF/konum bilgisi silinir) ve rastgele adla saklanır.
- Özel medya kısa ömürlü imzalı URL'lerle gösterilir.
- Auth bağlantıları PKCE kodu taşır; erişim token'ı bağlantıda yer almaz.
- Push'lar yalnızca veri içerir; metin cihazda oluşturulur, mesaj önizlemesi gönderenin ayarına bağlıdır.

## Hesap silme

`delete-account` fonksiyonu son 10 dakika içinde şifreyle yeniden giriş yapılmasını ister, dosyaları siler,
sonra auth kullanıcısını siler. Ayrıntı: `web/delete-account.html`.
