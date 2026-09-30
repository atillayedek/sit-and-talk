# E-posta doğrulama ve şifre sıfırlama bağlantıları

Kimlik doğrulama PKCE ile yapılır. E-postadaki bağlantı yalnızca tek kullanımlık bir `code` taşır;
bu kod, akışı başlatan telefondaki doğrulayıcı (verifier) olmadan işe yaramaz. Erişim token'ı hiçbir
zaman bağlantıda yer almaz.

## Varsayılan: özel şema

- Kayıt: `sitandtalk://auth-callback/signup?code=…`
- Şifre sıfırlama: `sitandtalk://auth-callback/recovery?code=…`

Supabase Dashboard → Authentication → URL Configuration → **Redirect URLs** listesine şunlar eklenmeli:

```
sitandtalk://auth-callback/signup
sitandtalk://auth-callback/recovery
```

Farklı bir şema/host için `AUTH_REDIRECT_URL` derleme değeri değiştirilir; manifest filtresi ve
Supabase'e gönderilen `redirectTo` bu değerden üretilir.

## İsteğe bağlı: https köprüsü

Bazı e-posta uygulamaları özel şemalı bağlantıları açmaz. `web/auth/callback.html` bir https adresinde
yayınlanırsa e-posta şablonundaki bağlantı `https://<site>/auth/callback.html?flow=signup&code=…`
biçiminde bu sayfaya yönlendirilebilir; sayfa yalnızca `code` ve hata alanlarını uygulamaya aktarır.

## Başka cihazda açılan bağlantı

Doğrulama bağlantısı başka bir cihazda açılırsa PKCE tamamlanamaz, ancak e-posta adresi sunucuda
doğrulanmış olur. Uygulama bu durumda "E-posta adresin doğrulandı. Şimdi giriş yapabilirsin." der.

## Uygulamadaki davranış

`MainActivity` bağlantıyı alır → `AuthRepository.handleCallback` kodu oturuma çevirir → kurtarma
akışındaysa "Yeni şifre" ekranı açılır, değilse uygulama normal başlangıç durumuna geçer.
Hatalar (`link_expired`, `auth_failed`) giriş ekranında Türkçe mesajla gösterilir.
