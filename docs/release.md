# Yayın

## İmzalama

Keystore'u depo dışında üret ve güvenli sakla:
```bash
keytool -genkeypair -v -keystore sitandtalk-release.jks -alias sitandtalk -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 sitandtalk-release.jks > keystore.b64
```
GitHub → Settings → Secrets and variables → Actions:
- Secrets: `SITANDTALK_KEYSTORE_BASE64`, `SITANDTALK_KEYSTORE_PASSWORD`, `SITANDTALK_KEY_ALIAS`,
  `SITANDTALK_KEY_PASSWORD`
- Variables: `SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY`, `AGORA_APP_ID` (zorunlu) ve isteğe bağlı
  `AUTH_REDIRECT_URL`, `APP_LINK_HOST`, `GOOGLE_WEB_CLIENT_ID`, `FIREBASE_*`, `SUPPORT_URL`,
  `PRIVACY_URL`, `TERMS_URL`, `COMMUNITY_URL`, `VERSION_CODE`, `VERSION_NAME`

Release derlemesi zorunlu değerler veya imzalama eksikse bilerek başarısız olur (`verifyReleaseConfig`).

## Çalıştırma

`v1.0.0` gibi bir etiket gönder ya da Actions → Android → *Run workflow* → `release: true`.
İş `apksigner verify` çalıştırır ve imzalı APK, AAB ve R8 mapping dosyasını artifact olarak yükler.

## App Links

`APP_LINK_HOST` ayarlanırsa `https://<host>/r/<oda>` ve `/p/<gönderi>` bağlantıları uygulamayı açabilir.
Doğrulama için sitede `/.well-known/assetlinks.json` yayınlanmalı; parmak izi:
```bash
keytool -list -v -keystore sitandtalk-release.jks -alias sitandtalk | grep SHA256
```
Play App Signing kullanılıyorsa Play Console'daki *App signing key* parmak izi de eklenmeli.
Bu dosya gerçek parmak izi olmadan depoya konmadı.

## Yayın öncesi kontrol listesi

- [ ] Supabase migration'ları ve fonksiyonları yayınlandı, secret'lar ve `runtime_config` girildi
- [ ] Agora App Certificate açık, co-host authentication açık
- [ ] Firebase (push) yapılandırıldı veya push'un kapalı olduğu kabul edildi
- [ ] Yasal metinler işletmeci bilgileriyle gözden geçirildi ve yayınlandı; URL'ler derlemeye verildi
- [ ] Play Console: veri güvenliği formu, içerik derecelendirmesi (18+), hesap silme URL'si
- [ ] En az iki gerçek cihazla [verification.md](verification.md) listesi tamamlandı
