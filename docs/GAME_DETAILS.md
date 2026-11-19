# Game details / Страница игры

## English

Long-press a game in **All games**, search results or **Recently played** to open its own page.
With a controller, use **Y / Menu** on the focused card. A normal tap keeps the launch chooser.

The page provides:

- Play, individual game settings, and emulator-state slots.
- Mods and cheats, including games without a bundled pack.
- Export and import of this title's **UDATA and TDATA**.
- Cover selection/removal and full-disc-to-XISO conversion when applicable.

Individual settings keep the existing title-ID + disc-version keys. Xbox saved data uses the title ID,
as on the original console; revisions of a game can share saved data.

### Saved data

**Backup folder** remembers a user-selected Android document-provider folder for this title.
**Export game data** writes a dated ZIP there. Without a remembered folder, each export opens the
system file-location chooser. These are manual backups: the running game continues saving to its
normal emulated Xbox HDD. Emulator-state slots and the rest of the console HDD are not part of this export.

Import accepts a hakuX saves ZIP, the E: portion of a hakuX full backup, or Xbox UDATA/TDATA folders in a ZIP.
The preview counts files and save folders to replace. Other title IDs are skipped. Saves absent from
the archive are preserved; matching save folders are replaced and matching TDATA files are overwritten.
Data is staged and validated before replacing either title folder. A failed second-folder install rolls
back the first. If rollback itself fails, original folders are retained in the reported recovery directory.
Transfers are blocked while an emulation process is running. The per-title transfer limit is 2 GiB.

Foreign console/xemu saves use the existing re-signing mechanism when console dumps and title keys
are available. The confirmation explains missing keys; this does not guarantee every third-party save
will pass a game's own validation.

### Imported mods and cheats

Imports use the [hakuX Xbox format](https://github.com/rfandango/hakuX/blob/v1.3.0/mods/README.md).
Switch/Eden cheats and unrelated patch formats are not compatible.

```text
4D530004.1/                 # optional enclosing title ID and certificate version
  cheats.txt               # optional UTF-8 hakuX cheat list
  widescreen/
    mod.txt
    files/                 # optional replacement disc files
      media/texture.xpr
```

A pack may omit the enclosing game-key directory and contain `cheats.txt` and/or mod directories
at the ZIP root. Such a pack is explicitly assigned to the selected game; the user must verify its
disc-version compatibility. A keyed pack for another title or certificate version is rejected.
`cheats.txt` can also be imported directly. Text files are limited to 256 KiB, a mod pack to 2 GiB
of expanded files and 20,000 entries. Absolute paths, traversal and duplicate case-insensitive paths
are rejected. Mod ZIP support does not add support for running archived games.

User packs live in `files/user_mods/<KEY>`, separately from bundled assets and their extraction cache.
They survive APK updates. Matching imported mod folders are replaced; other imported mods are kept.
An imported cheat list replaces the previous user list. Bundled mods load before user mods; user
replacements therefore take priority. A user cheat of the same name overrides its bundled entry.
Imports disable user content until explicitly selected. Import/removal requires the game to be stopped;
changes take effect on the next launch. Removing imported content keeps bundled packs and saved data.

### Validation before release

Host gates: `compileReleaseKotlin`, `testReleaseUnitTest`, `lintVitalRelease`. New tests cover UDATA/TDATA
round-trip and title filtering, a corrupt archive, staging failure, actual second-area installation
failure/rollback, expanded-data limits on skipped titles, mod replacement files, wrong disc revisions,
traversal, case-colliding paths, cheat-list replacement and preservation of unrelated user packs.

On RG477M, verify both card sections and controller navigation, individual-setting isolation between
two titles, RU/EN layout and scrolling, and all cover/state/conversion entries. With the game stopped,
export Fable's existing UDATA/TDATA to a user folder, verify the ZIP contents, then test import only
after preserving an independent backup. Also verify cancellation, foreign-title rejection and revoked
folder permission. Import a harmless valid mod/cheat fixture, confirm it starts disabled, and verify
its selection reaches the existing native manifest on the next launch. No device result is claimed
by the host tests.

## Русский

Долгое нажатие на карточку во **всех играх**, результатах поиска и разделе **«Недавно играли»**
открывает отдельную страницу игры. На геймпаде используются **Y / Menu**. Обычное нажатие сохраняет
диалог выбора нового запуска или последнего состояния.

На странице доступны индивидуальные настройки, состояния эмулятора, моды и читы, перенос игровых
данных, обложка и преобразование полного образа в XISO. Раздел модов доступен и без встроенного пакета.
Ключи существующих индивидуальных настроек не меняются.

**Папка резервных копий** запоминает выбранное пользователем место для конкретной игры. Кнопка
экспорта создаёт там ZIP с датой; без постоянной папки место выбирается при каждом экспорте.
Это ручное резервное копирование UDATA и TDATA: рабочие сохранения игры остаются на эмулируемом HDD,
а состояния эмулятора переносятся отдельно. Лимит данных одной игры — 2 ГиБ.

Перед импортом показываются число файлов и папок сохранений для замены. Данные других Title ID
пропускаются. Сохранения вне архива остаются; совпадающие папки сохранений и файлы TDATA заменяются.
Проверка и подготовка происходят до изменения текущих данных. Ошибка установки второй папки
откатывает первую. Если сам откат не удался, оригиналы сохраняются по указанному в сообщении пути.
Перенос заблокирован при работающей игре. Для сторонних сохранений используется существующий
механизм переподписи, а отсутствие необходимых ключей объясняется перед подтверждением.

Моды импортируются как ZIP в формате hakuX, читы — также отдельным UTF-8 `cheats.txt`.
Структура и ограничения приведены выше. Пакеты с указанными чужими ID или версией диска отклоняются.
Для пакета без каталога с ключом пользователь сам проверяет совместимость с выбранной версией игры.
Импортированные файлы хранятся отдельно от встроенных пакетов и переживают обновление APK.
После импорта пользовательский контент выключен до явного выбора. Для импорта/удаления нужно
завершить игру; для применения — запустить её заново. Удаление импортированного контента не затрагивает
встроенные пакеты и сохранения.

Перед релизом нужна проверка интерфейса и переноса на RG477M с независимой резервной копией исходных
данных. Проверки на компьютере подтверждают обработку архивов и сборку кода, но не заменяют эту проверку.

## Research and frame generation / Исследование и генерация кадров

Reviewed Eden at commit `d85bad89248fcae68f812d7e1c937436261404cb`:
[GameAdapter](https://git.eden-emu.dev/eden-emu/eden/src/commit/d85bad89248fcae68f812d7e1c937436261404cb/src/android/app/src/main/java/org/yuzu/yuzu_emu/adapters/GameAdapter.kt)
routes long press to the game-properties screen;
[GamePropertiesFragment](https://git.eden-emu.dev/eden-emu/eden/src/commit/d85bad89248fcae68f812d7e1c937436261404cb/src/android/app/src/main/java/org/yuzu/yuzu_emu/fragments/GamePropertiesFragment.kt)
groups individual preferences, add-ons and save import/export. This change follows that interaction
pattern using HakuX-Mali's own Compose components and existing Xbox data paths; Eden source is not copied.
Upstream hakuX already supplies native mod/cheat execution, documented in its
[release history](https://github.com/rfandango/hakuX/releases) and format guide above.

Frame generation is **not implemented by this UI change**. `native/app/present.c:nc_present_frame`
and `hw/xbox/hle/vk/swapchain.c:hle_present_frame` consume a final display frame; this presentation
interface does not supply game motion vectors, depth or a game-HUD mask. The `average` option is
spatial downsampling, not temporal frame interpolation.

[AMD's FSR documentation](https://gpuopen.com/fidelityfx-super-resolution-3/) requires motion vectors,
depth and UI composition for its integrated frame-generation path. Adding an FSR toggle alone would
not provide working frame generation. A separate experimental Vulkan optical-flow implementation
using consecutive final images is feasible in principle, but requires retained frame history,
scene-cut detection, interpolation shaders, synchronization and presentation pacing. It must handle
game HUD artifacts and keep the emulator overlay out of interpolation.

The first prototype should be opt-in 2× output from a stable, full-speed 30 FPS scene, with automatic
bypass for duplicates, scene cuts and insufficient GPU headroom. Measure original game FPS,
presented FPS, emulation speed, input latency, memory, GPU cost and temperature separately on RG477M
and RG Vita Pro. A higher presentation count does not make a slowed CPU/GPU emulation run at full
speed. No performance or quality gain on Mali is established until those tests pass.

Генерация кадров возможна как отдельный экспериментальный Vulkan-режим по двум готовым изображениям.
Она потребует расчёта движения, обработки смены сцен, синхронизации и отдельного учёта реальных и
сгенерированных кадров. Сейчас в выводе нет необходимых для прямой интеграции FSR векторов движения,
глубины и маски игрового HUD. Интерполяция может повысить плавность вывода, но не ускоряет саму
эмуляцию; влияние на задержку управления, нагрев и производительность Mali нужно измерить.

### FSR 3 integration checkpoints / Этапы проверки FSR 3

1. Pin an SDK revision and audit its Vulkan shader, subgroup, precision, memory and queue requirements
   against the actual loaded Mali driver on each target. Compile and run a standalone interpolation
   fixture before changing the emulator. Vulkan support in the SDK does not establish Android/Mali compatibility.
2. Define separate inputs for color, depth, camera parameters, jitter and motion vectors. Audit which
   Xbox draw paths can provide reliable vectors; camera-only reprojection will not describe moving
   characters, particles or changes in visibility. Define handling for missing/invalid inputs.
3. Implement history/reset rules and game-UI composition, then integrate the Vulkan presentation
   scheduler. Keep temporal upscaling and frame generation independently selectable and off by default.
4. Gate any release on measured image quality, latency, real/presented FPS, emulation speed and sustained
   thermals. If the SDK feature audit or reliable motion-vector capture fails, report the limitation;
   a custom final-image optical-flow experiment must not be presented as working FSR 3.

Для FSR 3 сначала проверяются требования конкретной версии SDK на загруженном Mali-драйвере и отдельном
тестовом примере. Затем нужно сформировать корректные входные данные в Xbox-рендерере, подключить историю
кадров и вывод с равномерными интервалами. Масштабирование и генерация проверяются отдельно. Пока эти
этапы и измерения на устройствах не пройдены, поддержка FSR 3 в HakuX-Mali не заявляется.
