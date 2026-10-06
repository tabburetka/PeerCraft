# Данные для обновления laptop-server

Проверено по SSH 05.10.2026 без изменения сервера. Перед установкой сверить снова.

| Поле | Наблюдение |
|---|---|
| Служба | rendezvous.service, active |
| Пользователь | ikuku |
| WorkingDirectory | /home/ikuku/peercraft_server |
| Unit | /etc/systemd/system/rendezvous.service |
| Аргумент -jar процесса | rendezvous-server-1.0.0.jar |
| Путь текущего JAR | /home/ikuku/peercraft_server/rendezvous-server-1.0.0.jar |
| Явный peercraft.rendezvous.dataDir | Не задан в аргументах процесса |
| Существующая папка data | /home/ikuku/peercraft_server/data |
| Файлы данных | data/accounts.json и data/handoffs существуют; содержимое не читалось |
| Владелец data и accounts.json | ikuku:ikuku |
| Права data / accounts.json | 0775 / 0600 |

SHA-256 текущего JAR для распознавания исходной версии при откате:

```
4355ee0e1c9d4bd4f4481b28022156fe82a10053522bcba8764b094f7d4e448f
```

Наличие файлов и отсутствие явного JVM-параметра не заменяют резервную копию.
На этапе обновления сохранить всю рабочую папку, unit и его drop-ins, подтвердить
фактический dataDir и права новой конфигурации. Не менять владельца существующих
аккаунтов, не создавать второй экземпляр rendezvous с пустой папкой данных.

Реле и пакеты не устанавливались; служба не перезапускалась. Административные
действия ранее требовали ввода sudo-пароля пользователем на ноутбуке.
