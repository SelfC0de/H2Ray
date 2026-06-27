# H2Ray

Android-клиент для VLESS, VMess, Trojan и Shadowsocks на базе Xray-core.

## Требования

- Android 9 или новее;
- package name: `com.h2ray.app`;
- min SDK 28;
- target SDK 36.

## Сборка в GitHub Actions

1. Откройте вкладку **Actions**.
2. Выберите workflow **Build Android APK**.
3. Нажмите **Run workflow**.
4. Выберите `debug` или `release`.
5. После завершения скачайте APK из раздела **Artifacts**.

Release APK на текущем этапе не подписывается production-ключом.

## Текущее состояние

GitHub Actions собирает официальный `XTLS/libXray` и встраивает Xray-core в APK. Приложение:

- импортирует `vless://`, `vmess://`, `trojan://`, `ss://` и Xray JSON;
- создаёт Android TUN через `VpnService`;
- передаёт TUN FD напрямую в Xray-core;
- исключает outbound-сокеты core из VPN через `VpnService.protect`;
- запускает VPN как foreground service.

Реальная совместимость конкретной конфигурации проверяется на Android-устройстве с доступным сервером.

Интерактивный UI-прототип находится в каталоге [`mockups`](mockups).
