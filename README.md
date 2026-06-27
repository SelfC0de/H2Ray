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

Собирается базовый Android-интерфейс и зарегистрирован `VpnService`. Реальное подключение через Xray-core пока не реализовано.

Интерактивный UI-прототип находится в каталоге [`mockups`](mockups).
