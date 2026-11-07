# HakuX-Mali 1.7.3 / code33 — local verification build

## Русский

В меню паузы, общих настройках и настройках запущенной игры добавлена кнопка **«Перезагрузить»**. Она перезапускает текущую игру с выбранными настройками, без возврата в библиотеку. Разрешение, FXAA, фильтр увеличения и графический профиль применяются при новом запуске ядра.

Перезапуск начинается с загрузки самой игры, а не состояния эмулятора. Перед ним появляется подтверждение: несохранённый прогресс будет потерян. Существующие сохранения остаются. Кнопка недоступна без активной игры; настройки другого тайтла не перезапускают текущую игру.

Настройки графики хранятся в эмуляторе, отдельно от состояний. Загрузка состояния перезапускает ядро и читает текущие общие и индивидуальные параметры. Индивидуальные параметры игры имеют приоритет над общими.

Для NFS Most Wanted Black Edition (`4541007B.7`) на определённом как Unisoc T820 устройстве и точном встроенном P77r1 добавлено узкое исключение: избыточное разделение проходов при изменении смешивания и маски записи отключается. Остальные игры, драйверы, T618 и Dimensity 8300 сохраняют прежние защиты. Физическая приёмка исключения выполнена на RG476H; RG556 этим тестом не проверена.

На RG476H в контрольной неподвижной сцене при 2×, FXAA и GSR улучшилась скорость виртуального времени; до полной скорости ещё остаётся разрыв. Это не подтверждение стабильной полной скорости во всей гонке. Дополнительный эксперимент упрощения шейдеров сохранён в отдельной сборке 1.7.4/code34 и по умолчанию выключен: короткий OFF/ON/OFF показал лишь около 0,93 процентного пункта расчётного улучшения.

В отдельной контрольной сцене гонки RG477M P77r1, прежний M2+DS1 и системный драйвер дали около 30 запросов вывода кадра в секунду, HUD показывал 100% скорости при 2×/FXAA/GSR/120. Это короткая проверка одной сцены; она не доказывает равную скорость драйверов на всех трассах.

129 модульных тестов и релизная проверка lint прошли. APK установлен и проверен на RG477M и RG476H. Выбранные драйверы, настройки и прежние состояния сохранены. Библиотеки ядра и четыре пакета драйверов совпадают с предыдущей сборкой. На GitHub эта версия не опубликована.

## English

**Restart** is available in the pause menu, global settings and settings for the running game. It restarts the current game with the selected graphics settings without returning to the library. Resolution, FXAA, upscaling and graphics profiles take effect when the core starts again.

Restart boots the game rather than loading an emulator state. A confirmation warns about losing unsaved progress; existing saves remain. The action is hidden without an active game, and another title's settings do not restart the current session.

Graphics settings belong to the emulator and are separate from states. Loading a state starts a new core using current global and per-game settings. Per-game overrides take precedence.

A narrow compatibility exception disables redundant render-pass splitting for Most Wanted Black Edition (`4541007B.7`) on detected Unisoc T820 devices using the exact bundled P77r1 binary. Other games, binaries, T618 and Dimensity 8300 retain their existing protections. Physical validation covered RG476H, not RG556.

The stationary RG476H checkpoint improved at 2× with FXAA/GSR, but a remaining speed gap persists. Full-speed sustained racing is not established. A separate default-off shader experiment in local 1.7.4/code34 produced only about 0.93 percentage points of estimated clock improvement in a short OFF/ON/OFF comparison.

In a separate RG477M race checkpoint, P77r1, M2+DS1 and System each delivered approximately 30 frame-present requests per second, with the HUD showing 100% speed at 2×/FXAA/GSR/120. This short scene does not establish equal performance across all tracks.

All 129 unit tests and release lint passed. The APK was installed and checked on RG477M and RG476H, preserving selected drivers, settings and pre-existing states. Native libraries and all four driver packages remain unchanged. This local version has not been published on GitHub.
