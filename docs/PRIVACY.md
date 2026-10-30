# Privacy and diagnostics

DiPlay's product flow uses local authentication and a direct USB/Wi-Fi connection to the iPhone. No account, remote authentication service or automatic diagnostic upload is used. The iPhone's CarPlay apps have their own internet and privacy behavior.

The head unit stores app preferences, paired-device selections, pairing data and bounded diagnostic logs in app storage. Authentication and pairing material are kept out of Android backup. Uninstalling removes app-private data; exported reports in Downloads remain until you delete them.

Diagnostic export is initiated by you. Reports include app/device versions, display settings and negotiation, connection transitions, Wi-Fi band/channel and state, and decoder recovery events. With the optional wheel key service on, they also list the last hardware keys it was given, as key and scan codes with what DiPlay did with each; a key that writes text is counted and never listed, and no input device name is recorded. The exporter filters protocol payloads, credential-bearing lines and common identifiers. Redaction cannot promise to recognize every vendor-specific string: review reports before posting them publicly. A GitHub issue is public.

On Android 9, **Save diagnostic report** asks for storage access and saves to `Download/DiPlay`; if you decline, the fallbacks below apply. When public Downloads storage is unavailable, reports save under `Android/data/<package>/files/diagnostic-reports/` on primary external storage without requesting storage permission. The confirmation shows the actual TXT file path. The Flyme release package is `com.shihab.diplay.flyme`; test builds use `com.shihab.diplay.flyme.test`. The former `.flyme.hudtest` installation has separate private data and is not automatically migrated. File managers on newer Android versions may restrict access to `Android/data`; use **View** or **Share** in DiPlay instead. If external storage is also unavailable, reports save in private app storage. Each fallback location retains its newest eight exports, and uninstalling removes them. Sharing grants read access to the selected report only; nothing is sent automatically. On Android 9, other apps with storage permission may be able to read the external reports.

Optional Usage Access lets the map card follow the foreground home screen; permission can be revoked in Android settings. Foreground state is processed locally.

Microphone access supports Siri and calls. Bluetooth/Nearby devices and Wi-Fi/Location permissions support discovery and transport. The optional local VPN permission supports the USB link; it does not provide a remote internet VPN.

The static website has no analytics script or account. GitHub Pages, GitHub and Telegram apply their own policies when you use those services.
