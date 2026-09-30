# Supabase kurulumu

Uygulama **mevcut** Supabase projesini kullanır; yeni proje açmak gerekmez. Migration'lar yalnızca
yeni nesneler ekler (`create table`, `create or replace function`, politikalar); veri silen `drop`/`reset`
içermez. Yine de başka bir uygulamanın tabloları bulunan bir projeye uygulamadan önce şema
çakışmalarını kontrol et (özellikle `public.profiles`, `public.messages`, `public.rooms`,
`public.notifications`): aynı adlı tablo varsa `create table` hata verir ve migration durur, veri kaybı olmaz.

## 1. Migration'lar

Seçenek A — GitHub Actions (önerilir): depo ayarlarında
- Secret: `SUPABASE_ACCESS_TOKEN`, `SUPABASE_DB_PASSWORD`
- Variable: `SUPABASE_PROJECT_REF`

sonra Actions → **Supabase** → *Run workflow* → `deploy: true`. İş önce PGlite testlerini ve
fonksiyon tip kontrolünü çalıştırır, sonra `supabase db push` ve `supabase functions deploy` yapar.

Seçenek B — yerelde:
```bash
supabase link --project-ref <ref>
supabase db push
supabase functions deploy
```

## 2. Edge Function secret'ları

`supabase/functions/.env.example` listesindeki değerler (`supabase secrets set KEY=value`):

| Secret | Gerekli mi | Açıklama |
| --- | --- | --- |
| `AGORA_APP_ID`, `AGORA_APP_CERTIFICATE` | Ses/görüntü için evet | [agora-setup.md](agora-setup.md) |
| `AGORA_CUSTOMER_ID`, `AGORA_CUSTOMER_SECRET` | Hayır | Atılan kullanıcıyı kanaldan anında düşürme |
| `INTERNAL_HOOK_SECRET` | Push ve bakım işleri için evet | Rastgele uzun değer; veritabanındaki `internal_hook_secret` ile aynı |
| `FCM_PROJECT_ID`, `FCM_SERVICE_ACCOUNT_JSON` | Push için evet | Firebase servis hesabı |
| `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`, `ANDROID_PACKAGE_NAME` | Satın alma için evet | [billing.md](billing.md) |
| `PLAY_RTDN_SECRET` | Abonelik bildirimleri için evet | Pub/Sub push URL'sindeki paylaşılan sır |
| `ALLOWED_WEB_ORIGINS` | Hayır | Tarayıcıdan çağrı gerekmedikçe boş bırak |

## 3. Veritabanı çalışma zamanı yapılandırması

Push kuyruğu ve bakım işleri veritabanından Edge Function çağırır. SQL Editor'da bir kez:

```sql
insert into app_private.runtime_config (key, value) values
  ('functions_base_url', 'https://<ref>.supabase.co/functions/v1'),
  ('internal_hook_secret', '<INTERNAL_HOOK_SECRET ile aynı değer>')
on conflict (key) do update set value = excluded.value;
```

`pg_net` ve `pg_cron` eklentileri açıksa dakikalık `sitandtalk-tick` işi kurulur (süresi dolan eşleşmeler,
kuyruk temizliği, push kuyruğu). Kapalıysa eşleşme kalp atışları temizliği fırsatçı olarak yapar; push
gönderimi için `pg_net` gerekir.

## 4. Auth ayarları

- Authentication → Providers → Email: *Confirm email* açık.
- URL Configuration → Redirect URLs: [auth-callbacks.md](auth-callbacks.md).
- Google ile giriş isteğe bağlı: Google provider'ı aç, `GOOGLE_WEB_CLIENT_ID` derleme değerini ver ve
  `feature_flags` tablosundaki `google_sign_in` bayrağını aç.

## 5. İlk yönetici

Yönetici rolü istemciden verilemez. SQL Editor'da:
```sql
insert into public.admin_roles (user_id, role) values ('<auth.users id>', 'admin');
```

## 6. Özellik bayrakları

`video_matching`, `gifts`, `premium`, `google_sign_in` varsayılan olarak kapalıdır; yönetim panelindeki
*Yapılandırma* sekmesinden veya SQL ile açılır. Bayrağı açmadan önce ilgili arka uç yapılandırmasının
(ör. Play servis hesabı) hazır olduğundan emin ol.

## Testler

`supabase/tests/run.mjs` migration'ları PGlite üzerinde sıfırdan kurar ve RLS/RPC davranışını test eder:
```bash
npm ci --prefix supabase/tests && node supabase/tests/run.mjs
```
