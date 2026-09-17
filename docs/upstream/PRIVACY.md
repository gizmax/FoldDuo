# Data and permissions

Duo Launcher stores settings, Home layout, widget placement, and selected wallpaper locally. It has no account system, backend, advertising, analytics SDK, or automatic crash reporting.

## Data used on your device

- Installed app names, icons, launch activities, and eligible work-profile entries populate Home and All apps.
- Widget providers control their content, accounts, and network activity; Android hosts their widgets.
- Battery, Wi-Fi, cellular signal, and airplane-mode readings populate the Home status rail while visible. Signal display does not require location access.
- Selecting a photo creates a local preview. Apply commits it; cancel preserves the previous background. Android's picker grants access to chosen images only.
- Sunrise/sunset appearance stores coordinates you enter or explicitly request through approximate location. Times are calculated locally. There is no background location tracking, and Clear location removes the stored coordinates.

## Optional access

The shade-gesture accessibility service opens notifications or Quick Settings in response to your gesture. It cannot retrieve window contents or perform gesture injection and unsubscribes from accessibility events when connected. You can disable it in Android Accessibility settings and continue using the launcher.

Android controls widget-binding approval and Home-app selection. Providers can require separate setup or permissions.

## Google and other apps

Discover and Google search use the installed Google app. Apps, search results, articles, and widgets may use their providers' network services and accounts. Those apps' policies and settings apply; Duo does not proxy their traffic or collect their content.

## Export, reports, and removal

A layout export is created only when you choose Save in Backup and select a destination. It can reveal installed apps, folder names, profile metadata, and layout preferences. Photos are excluded. Review it before sharing.

There is no automatic diagnostic upload. Screenshots and logs you manually attach to issues may contain personal information, widget content, account names, or work data. Review them first.

Uninstalling or clearing storage removes Duo's local settings, photos, and widget bindings. Exported files remain where you saved them. Android and device vendors may provide their own diagnostics independently of Duo.
