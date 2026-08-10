# Подпись release-APK и сборка на GitHub

## Как это устроено

- Сборка APK автоматизирована через GitHub Actions: `.github/workflows/build-apk.yml`.
- `app/build.gradle.kts` содержит `signingConfigs.release`, который читает данные
  ключа из переменных окружения. Если переменных нет — release собирается без
  подписи (локальная разработка не ломается).
- Сам keystore **не хранится в репозитории** (см. `.gitignore`). В CI он приходит
  из секрета `KEYSTORE_BASE64` и декодируется во временный файл.

## GitHub Secrets (Settings → Secrets and variables → Actions)

Нужно 4 секрета:

| Секрет | Назначение |
|--------|-----------|
| `KEYSTORE_BASE64` | keystore (`.jks`), закодированный в base64 |
| `KEYSTORE_PASSWORD` | пароль хранилища |
| `KEY_ALIAS` | алиас ключа (`flappybird`) |
| `KEY_PASSWORD` | пароль ключа |

## Как выпустить релиз

```bash
git tag v1.0.1
git push origin v1.0.1
```

Workflow соберёт подписанный `FlappyBird.apk` и приложит его к GitHub Release.

## Важно про keystore

- Файл ключа хранится вне репозитория (резервная копия у владельца проекта).
- **Терять ключ нельзя**: при потере невозможно выпускать обновления того же
  приложения в Google Play (другой ключ = другое приложение). Для установки APK
  напрямую с GitHub это не критично, но ключ всё равно стоит беречь.
