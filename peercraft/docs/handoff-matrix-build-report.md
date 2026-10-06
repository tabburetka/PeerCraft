# Сборка матрицы после hardening handoff

Дата: 2026-09-27. Проверено текущее рабочее дерево, включая параллельные изменения интерфейса и аккаунтов. JAR в Prism не устанавливались; публикации не выполнялись.

Всего 32 конфигурации: 30 в основном Gradle-проекте и два отдельных Forge-backport.

| Конфигурация | Результат |
| --- | --- |
| Fabric 1.16.5 | Полный build успешен |
| Fabric 1.21.1 | Полный build успешен, включая тесты |
| Forge 1.12.2 | assemble/reobfJar успешны |
| Forge 1.7.10 | assemble/reobfJar успешны |
| NeoForge 1.21.1 | assemble успешен; полный build не прошёл compileTestJava для SteampunkButtonTest |
| Остальные 27 современных конфигураций | compileJava не прошёл из-за версионных ограничений экранов Steampunk |

В основном проекте запущен `./gradlew buildAll --continue --offline --max-workers=2`. Он завершился ошибкой через 2 минуты 4 секунды: 257 задач, 28 неуспешных узлов. Оба Forge-проекта собраны через Gradle 8.8, `assemble --offline --max-workers=1`.

Файлы, в которых зарегистрированы ошибки: PeerCraftRegisterScreen, PeerCraftMultiplayerScreen, PeerCraftFriendRequestsScreen, PeerCraftProgressNoticeScreen, SteampunkButtonTest. SteampunkSettingsTheme и SteampunkDialog ограничены `//? if =1.21.1`, а потребляющие их экраны присутствуют в других версиях. Тест NeoForge дополнительно не видит клиентские Minecraft-классы в своём classpath.

Ошибок компиляции в исходниках handoff в этом прогоне не зарегистрировано. Это не означает успешную сборку заблокированных конфигураций или применение Mixin в Minecraft. Сгенерированные копии не исправлялись вручную; параллельные изменения интерфейса сохранены.

После адаптации экранов и теста требуется повторить полный прогон. Игровые проверки handoff описаны в [сценариях](handoff-game-test-scenarios.md).
