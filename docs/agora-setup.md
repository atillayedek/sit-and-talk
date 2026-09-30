# Agora kurulumu

## Anahtarlar nereye gider

| Değer | Nerede | Neden |
| --- | --- | --- |
| App ID | Android derleme yapılandırması (`AGORA_APP_ID`: `local.properties` ya da GitHub Actions **variable**) ve Edge Function secret'ı | APK'nın içinde bulunması normaldir; tek başına kanala girmeye yetmez. |
| App Certificate | **Yalnızca** Supabase Edge Function secret'ı `AGORA_APP_CERTIFICATE` | Token imzalar. APK'ya, depoya, loglara veya ekran görüntülerine girmemeli. |
| Customer ID / Secret (isteğe bağlı) | Edge Function secret'ları `AGORA_CUSTOMER_ID`, `AGORA_CUSTOMER_SECRET` | Odadan atılan kişiyi Agora kanalından da düşürmek için (kicking-rule REST API). |

İki yol vardır; fonksiyonlar önce ortam değişkenine, yoksa Vault'a bakar (`public.server_secret`, yalnızca
`service_role` çalıştırabilir):

```bash
# 1) Edge Function secret'ı
supabase secrets set AGORA_APP_ID=<app id> AGORA_APP_CERTIFICATE=<certificate> --project-ref <ref>
```
```sql
-- 2) Supabase Vault (veritabanında şifreli saklanır)
select vault.create_secret('<certificate>', 'AGORA_APP_CERTIFICATE');
```

> TalkRoom projesinde (30 Eylül 2026) App ID ve Certificate Vault'a kaydedildi.

Agora konsolunda projede **App Certificate açık** olmalı (token zorunlu mod). Konsoldaki "Generate Temp
Token" yalnızca deneme içindir; uygulama bunu kullanmaz.

## Token akışı

1. Uygulama `agora-token` fonksiyonunu `{kind: "call"|"room", id}` ile çağırır.
2. Fonksiyon kullanıcının JWT'sini doğrular ve `rtc_authorize` RPC'si ile kanal adını, UID'yi ve rolü
   veritabanından alır. İstemci kanal adı veya rol seçemez.
3. Görüşmede her iki taraf yayıncıdır; odada konuşmacı/moderatör/sahip yayıncı, dinleyici abonedir.
4. Token kısa ömürlüdür; SDK süresi dolmak üzereyken uygulama yenisini ister (`renewToken`).

## Dinleyici rolünün zorlanması

Abone token'ı ile yayın yapmayı Agora'nın engellemesi için konsolda **co-host authentication**
etkin olmalıdır. Kapalıysa dinleyici rolü yalnızca uygulama içinde uygulanır.

## Atma (kick)

Odadan atılan kullanıcının üyeliği sunucuda hemen silinir ve yeni token alamaz. Customer ID/Secret
tanımlıysa `rtc-moderation` fonksiyonu kullanıcıyı Agora kanalından da anında düşürür; tanımlı değilse
elindeki token süresi dolana kadar (en fazla token TTL'i) kanalda kalabilir.
