# Материалы аудита PeerCraft от 23 октября 2026

Основной отчёт: [release-readiness-2026-10-23.md](../../release-readiness-2026-10-23.md).

`test-summary.json` содержит свежие полные прогоны. `fixture-test.log` — отдельный запуск одного ранее пропущенного теста с настоящим локальным AppleSkin JAR. Сам сторонний JAR не копировался в репозиторий. Сборочные логи отражают срез на момент запуска; после них в дереве появились новые admission-изменения.

`client-probe.log`, `server-probe.log`, `blocking-auth-probe.log`, `shim-probe.log` фиксируют воспроизведения. Программы расположены в `probes/`. Они не включены в production source sets. Все аккаунты/room codes в probe-логах синтетические. Инициализация экранов в ClientAuditProbe обходится через Unsafe: это проверка реальной функции tickJoin, а не работающей графики Minecraft.

Для повторения после локальной сборки клиента и server jar:

1. Запустить Gradle с `-I <путь-к-probes/classpath.gradle> :1.21.1-fabric:auditRuntimeClasspath`. Init script пишет classpath в `/tmp/peercraft-release-audit-20261023/client-classpath.txt`; каталог должен существовать.
2. Скомпилировать ClientAuditProbe с полученным classpath и запустить с тем же classpath. Нужна Java 21.
3. ServerAuditProbe использует `rendezvous-server/build/libs/rendezvous-server-1.0.0.jar` и `rendezvous-server/build/classes/java/test` (PasswordCrypto компилируется серверной контрактной конфигурацией).
4. BlockingAuthProbe компилируется с server jar, запускается по полному имени `net.peercraft.rendezvous.account.BlockingAuthProbe` и использует только localhost. Потребуется разрешение на локальные сокеты, если среда их ограничивает.
5. LoggerShimProbe использует настоящие Logger.java/LoggerFactory.java из Forge 1.12.2, общий PrivateNetworkLogger.java, compile-only `probes/shim-src/org/slf4j/Marker.java` и log4j-api. Ожидается перехваченное исключение `org.slf4j.Logger is not an interface`. Marker здесь только позволяет отдельно показать вторую проблему; это не патч.
6. RunFixtureTest — JUnit Launcher одного метода `ModSyncLoopbackIntegrationTest#transfersRealAppleSkinJarWhenAvailable(java.nio.file.Path)` с `PEERCRAFT_TEST_APPLESKIN_JAR`, указывающим на отдельную временную копию локального JAR. Он использует тестовый classpath и не запускает Minecraft.

Основные команды Gradle запускались с `--offline --no-daemon --max-workers=1`, Java 21 как `org.gradle.java.home` и `/usr/lib/jvm/java-21-openjdk/bin` в PATH. Полные тесты использовали `--rerun-tasks`. Сборочная матрица запускалась с `buildAll -x test -x compileTestJava`. Forge — соответственно `build` и `build modsyncTest`.
