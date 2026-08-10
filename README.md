# Это Игра для Андроид, По типу игры Flappy Bird

![Build APK](https://github.com/Domix322/FlappyBirdsGame/actions/workflows/build-apk.yml/badge.svg)

## 📥 Скачать APK

**Готовый файл для установки на телефон:**

1. Открой вкладку [**Releases**](https://github.com/Domix322/FlappyBirdsGame/releases) в репозитории.
2. В последнем релизе скачай файл `FlappyBird.apk`.
3. Перекинь его на Android-телефон и установи (нужно разрешить «Установку из неизвестных источников»).

> Также свежий APK всегда есть во вкладке [**Actions**](https://github.com/Domix322/FlappyBirdsGame/actions) → выбери последнюю сборку → раздел **Artifacts** → `FlappyBird-apk`.

## 🚀 Как выпустить новую версию (для разработчика)

APK собирается автоматически через GitHub Actions.

- При каждом `push` в `main` собирается APK и кладётся в **Artifacts** (хранится 90 дней).
- Чтобы создать постоянный релиз со ссылкой для скачивания — поставь git-тег вида `vX.Y.Z`:

```bash
git tag v1.0.0
git push origin v1.0.0
```

После этого workflow автоматически создаст Release и прикрепит к нему `FlappyBird.apk`.
