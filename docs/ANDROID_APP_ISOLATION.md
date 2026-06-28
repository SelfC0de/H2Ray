# Изоляция приложений и обнаружение VPN

Обычный Android `VpnService` нельзя скрыть от приложения, трафик которого идёт
через этот VPN. Android сообщает `TRANSPORT_VPN`, убирает
`NET_CAPABILITY_NOT_VPN` и создаёт TUN-интерфейс.

Поддерживаемый системой способ совместимости — per-app VPN:

- `addDisallowedApplication` выводит выбранное приложение из VPN;
- приложение использует системную сеть и реальный IP;
- остальные приложения продолжают использовать H2Ray.

Рабочий профиль, используемый Island и Shelter, является отдельным Android
профилем. VPN личного профиля не применяется к приложению рабочего профиля.
Это изоляция, а не скрытая передача его трафика через H2Ray.

H2Ray не может незаметно создать рабочий профиль. Для этого приложение должно
быть отдельным Device Policy Controller, а пользователь должен пройти системное
provisioning-подтверждение. Объединение VPN-клиента и DPC существенно меняет
модель доверия и не должно выполняться как скрытая функция.

Источники:

- https://developer.android.com/develop/connectivity/vpn
- https://developer.android.com/reference/android/net/NetworkCapabilities
- https://developer.android.com/work/managed-profiles
- https://www.securitylab.ru/analytics/563719.php
- https://pikabu.ru/story/izolyatsiya_prilozheniy_kotoryie_trebuyut_vyiklyuchit_vpn_13916748
