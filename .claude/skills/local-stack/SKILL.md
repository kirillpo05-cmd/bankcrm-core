---
name: local-stack
description: Bring up, verify, reset and troubleshoot the Client360 local environment — Postgres, Kafka, MinIO, the Flyway runs, the seed profile and the application services. Use when starting work, when tests need a database, when migrations must be inspected, or when Docker, Testcontainers or the Maven wrapper misbehave on this machine.
---

# Локальный стенд

## Первый запуск

```bash
cp .env.example .env          # .env git-ignored, значения только локальные
docker compose up -d          # Postgres + Kafka + MinIO + обе миграции Flyway
```

Дождись, пока Flyway отработает, и посмотри его вывод:

```bash
docker compose logs flyway-client
docker compose logs flyway-interaction
```

`flyway-interaction` ждёт `flyway-client`: таблицы interaction несут FK в `client.clients` и
`client.users` в общей dev-базе.

Сид (2 команды, 9 пользователей):

```bash
docker compose --profile seed up seed
```

Клиентов в SQL-сиде нет намеренно: их PII-колонки хранят AES-256-GCM шифротекст плюс HMAC, а
pgcrypto не умеет сделать GCM, который приложение потом расшифрует. Клиентские данные
генерирует dev-профиль приложения, у которого есть ключ.

## Проверить, что всё поднялось

```bash
docker compose ps
docker compose exec postgres psql -U client360 -d client360 -c '\dt client.*'
docker compose exec postgres psql -U client360 -d client360 -c '\dt interaction.*'
```

## Сброс

```bash
docker compose down -v        # сносит и данные тоже
```

## Сервисы приложения

```bash
docker compose --profile app up -d      # client 8080, interaction 8081, audit 8082
curl localhost:8082/api/v1/audit/health # лаг ингестии по партициям
```

**Сейчас это не работает**: `docker-compose.yml` собирает сервисы через `build: ./src/<service>`,
а `Dockerfile` ни в одном из них нет. Пока их не добавили, приложение запускается из IDE или
`./mvnw spring-boot:run`, а compose используется только для инфраструктуры.

## Сборка и тесты

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-24"
./mvnw -pl src/client-service -am test    # тесты одного сервиса (-am обязателен: нужен common)
./mvnw test                               # весь реактор
./mvnw verify                             # полная сборка с интеграционными тестами
./mvnw spotless:apply                     # формат перед коммитом
```

Тесты поднимают собственный PostgreSQL через Testcontainers и compose-стенд **не используют** —
им нужен только запущенный демон Docker.

## Грабли этой машины

**`JAVA_HOME` указывает на JRE 1.8.** `./mvnw` без экспорта выше не запустится: Maven нужен
JDK. Установленного JDK 21 нет, сборка идёт на JDK 24 с `release 21` — это нормально.

**Демон Docker тихо выключается.** Проверка:

```bash
docker info --format '{{.ServerVersion}}'
```

Если не отвечает — запусти Docker Desktop и дождись демона, а не пересобирай:

```bash
powershell -Command 'Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"'
for i in $(seq 1 60); do docker info >/dev/null 2>&1 && { echo up; break; }; sleep 5; done
```

**`InvalidPathException: Illegal char <?> at index 0: ?C` из Testcontainers — это не про код.**
Так выглядит недоступный демон: Testcontainers перебирает запасные стратегии подключения и
спотыкается о битую запись `?C` в `PATH`, на которой Windows роняет `Paths.get()`. Подними
Docker, ошибка уйдёт.

**Git Bash переписывает пути внутрь контейнера.** Префиксуй:

```bash
MSYS_NO_PATHCONV=1 docker exec client360-postgres psql -U client360 -d client360 -c '\dt client.*'
```

**`spotless:apply` форматирует весь реактор.** Он трогает и уже закоммиченный `src/common`,
который форматировали руками. Перед коммитом проверь `git status` и откати чужие файлы:
`git checkout -- src/common/`.

**Surefire может оказаться неполным в локальном `.m2`.** Симптом — `Unable to load the mojo
'test'` с отсутствующим классом. Лечится одним прогоном без `-o`, чтобы Maven дотянул
недостающие артефакты.

**Флаг «запустить только эти тесты»** называется `-Dsurefire.failIfNoSpecifiedTests=false`,
а не `-DfailIfNoSpecifiedTests` — иначе сборка падает на модуле, где такого теста нет.
