# Tasarım sistemi

Kaynak: `core/designsystem`. Eski yeşil tasarım kullanılmaz; mavi/turkuaz referanslar esastır.

## Renkler

| Token | Değer | Kullanım |
| --- | --- | --- |
| Blue | `#349AF4` | Ana vurgu, dolgu |
| BlueDeep | `#1B6FC2` | Beyaz üzerinde metin/ikon (5.0:1 kontrast) |
| Turquoise | `#08C5E8` | İkincil vurgu, degrade ucu |
| Success | `#00C99B` | Bağlı/başarılı durum, aktif görüşme çubuğu |
| Coral | `#FF7047` | Beğeni, dikkat |
| EndCall | `#FF5967` | Görüşmeyi bitir |
| White / PageLight | `#FFFFFF` / `#F4F8FC` | Yüzey / sayfa |
| Ink / InkSecondary | `#172B4D` / `#63758B` | Metin |

"Konuş" alanı degradesi `#1B6FC2 → #0B7F99` (beyaz metin için yeterli kontrast). Koyu tema ayrı
tanımlıdır.

## Bileşenler

`StPrimaryButton`, `StSecondaryButton`, `StDangerButton`, `StTextButton`, `StTextField`, `ToggleChip`,
`SwitchRow`, `NavRow`, `Avatar`, `RemoteImage`, `LoadingState`, `EmptyState`, `ErrorState`,
`LoadStateContent`, `InfoBanner`, `SectionHeader`, `StTopBar`, `ConfirmDialog`, `ReportDialog`;
görüşme için `WaveRings`, `BigRoundAction`, `CircularCountdown`, `CallControlButton`.

## Kurallar

- Dokunma hedefleri en az 48dp; düğmeler 52dp.
- Tüm metinler `strings.xml` içinde (Türkçe); hata kodları `errors.xml`'de. Dil ekleme yeni
  `values-xx` klasörüyle yapılır.
- Sistem animasyonları kapalıysa (`reduced motion`) dalga/nabız animasyonları durur (`Motion.kt`).
- Logo özgündür: konuşma balonu içinde üç ses çubuğu (`SitAndTalkMark`, uygulama ikonu, web).
- Boş/hata/yükleniyor durumları her listede vardır; sahte içerik gösterilmez.
