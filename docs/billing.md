# Ödemeler (Google Play Billing)

Premium abonelik ve jeton paketleri yalnızca Google Play üzerinden satılır. **İstemcinin "satın alma
başarılı" bildirimine hiçbir zaman güvenilmez**: bakiye ve premium yalnızca sunucu doğrulamasından sonra
verilir.

## Akış

1. Uygulama ürünleri Play Billing 8 ile sorgular; ürün listesi `app_settings` içindeki
   `coin_products` (ör. `{"coins_100": 100}`) ve `premium_products` (ör. `["premium_monthly"]`)
   değerlerinden gelir. Ayarlanmamışsa cüzdan satın alma seçeneği göstermez.
2. Satın almada `obfuscatedAccountId = sha256(userId)` gönderilir.
3. Uygulama `verify-purchase` fonksiyonuna `{kind, product_id, purchase_token}` yollar.
4. Fonksiyon Google Play Developer API ile satın almayı doğrular, `obfuscatedAccountId`'nin çağıran
   kullanıcıyla eşleştiğini kontrol eder, `record_verified_purchase` ile kaydeder, jetonları defter
   (ledger) kaydıyla ekler, sonra satın almayı onaylar (acknowledge) / tüketir (consume).
5. Aynı token ikinci kez gönderilirse kayıt idempotenttir; bakiye iki kez artmaz.
6. İade ve abonelik değişiklikleri `play-rtdn` (Real-time Developer Notifications) ile işlenir.

Bakiye yalnızca `wallet_transactions` defteri üzerinden değişir; istemcinin bakiye yazma yetkisi yoktur. Hediyeler
jeton harcar, paraya çevrilemez.

## Kurulum

1. Play Console'da ürünleri oluştur (tek seferlik: jeton paketleri; abonelik: premium).
2. Google Cloud'da servis hesabı oluştur, Play Console → Users and permissions'ta *View financial data*
   ve *Manage orders and subscriptions* izinlerini ver. JSON anahtarını
   `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` secret'ı olarak kaydet.
3. RTDN: Pub/Sub konusu + push aboneliği →
   `https://<ref>.supabase.co/functions/v1/play-rtdn?token=<PLAY_RTDN_SECRET>`.
4. `app_settings` içinde `coin_products` ve `premium_products` değerlerini gir.
5. `premium` / `gifts` bayraklarını aç.

Play Billing yalnızca Play'den yüklenmiş ve Play Console'a yüklenmiş bir sürümle (dahili test kanalı
yeterli) test edilebilir; debug APK ile gerçek satın alma yapılamaz.
