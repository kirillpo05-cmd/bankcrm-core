---
name: new-migration
description: Write a Flyway migration for Client360 together with the Testcontainers test that tries to violate every constraint it adds. Use when adding or changing a table, column, constraint, index or partition in db/migrations/. Covers the naming conventions, the ON DELETE decision, safe changes to populated tables, and why forward-only means there is no down script.
---

# Новая миграция

Схема здесь — граница корректности, а не слой удобства. Миграция не считается готовой, пока
на каждое её ограничение нет теста, который **пытается это ограничение нарушить**.

## 1. Сначала прочитай спеку, потом пиши DDL

`SPEC.md` §5.2 (clients), §6.2 (interactions), §7.2 (tasks), §8.2 (audit), §9.2 (RBAC)
содержат задуманный DDL, включая имена ограничений и определения индексов. Это контракт.

Если твоё изменение ему противоречит — либо следуй спеке, либо правь `SPEC.md` в том же
изменении и прямо скажи, какое правило меняешь. Молча разойтись со спекой нельзя.

Затем прочитай `.claude/rules/db-migrations.md` и посмотри, что уже применено:

```bash
ls db/migrations/client-service/ db/migrations/interaction-service/
```

Номер `V<n>` сквозной **внутри сервиса** — у каждого своя история Flyway в своей схеме.

## 2. Имя файла и шапка

`db/migrations/<service>/V<n>__<snake_case_description>.sql`, одно логическое изменение на
файл. В шапке — что делает миграция, на какие разделы SPEC опирается, и любое неочевидное
решение. Если миграция переписывает таблицу — ожидаемая длительность и поведение блокировок.

```sql
-- Client360 / client-service — V6: <что это>.
--
-- SPEC.md §5.2.2. <Почему именно так, если это неочевидно.>

SET LOCAL search_path = client, public;
```

## 3. Конвенции, от которых нельзя отступать

| Что | Шаблон |
|---|---|
| Check | `ck_<table>_<rule>` |
| Foreign key | `fk_<table>_<target>` |
| Unique constraint | `uq_<table>_<cols>` |
| Unique index | `ux_<table>_<cols>` |
| Index | `ix_<table>_<cols>` |

Анонимное ограничение даёт в проде сообщение, по которому невозможно понять, что случилось.
Имя есть у всего.

**Колонки:**

- Всегда `TIMESTAMPTZ`. `TIMESTAMP` без зоны — баг.
- Деньги — `BIGINT` в минорных единицах + `CHAR(3)` валюта с `CHECK` на формат. Никогда
  `NUMERIC` и никогда `FLOAT`.
- Чувствительные данные — пара `<field>_enc BYTEA` + `<field>_hash BYTEA` плюс
  `key_version SMALLINT` на таблице. Plaintext-колонок с клиентским PII не бывает.
  Исключение: `users.email` — plaintext намеренно, это корпоративный справочник.
- UUID v7 в первичных ключах, `DEFAULT gen_random_uuid()` там, где генерирует БД.
- Мутабельный агрегат несёт `version INTEGER NOT NULL DEFAULT 0`.

**`ON DELETE` — это решение, а не умолчание.** `CASCADE` только там, где ребёнок — чистая
проекция без собственной авторитетности (`client_products`, `interaction_attachments`,
`task_reminders`, `team_members`). `RESTRICT` там, где ребёнок фиксирует произошедшее
(`interactions`, `tasks`, любая ссылка `*_by`). Если выбор неочевиден — обоснуй в комментарии.

**Индексы:** предпочитай частичные там, где предикат всегда есть в запросе
(`WHERE deleted_at IS NULL`). Над каждым — комментарий с идентификатором SPEC, называющий
обслуживаемый запрос. `now()` не `IMMUTABLE` и не может стоять в предикате индекса.

**Каждый инвариант, выразимый как `CHECK`, — это `CHECK`.** Если правило выразить нельзя
(счётчик на родителя), напиши комментарий, называющий, что именно его обеспечивает, чтобы
отсутствие ограничения читалось как решение, а не как недосмотр.

## 4. Заполненные таблицы

- `NOT NULL` колонка — это три миграции: добавить nullable, забэкфиллить батчами, выставить
  `NOT NULL`.
- `CHECK` на существующие данные — `ADD CONSTRAINT … NOT VALID`, затем `VALIDATE CONSTRAINT`
  во второй миграции, чтобы первая не держала `ACCESS EXCLUSIVE` во время скана.
- Индекс — `CREATE INDEX CONCURRENTLY` в отдельной миграции с
  `-- flyway:executeInTransaction=false`.

## 5. Обратимость: down-скрипта не будет

Миграции forward-only и неизменяемы после мерджа. Никогда не правь миграцию, которая
где-либо отработала, включая локальный стенд коллеги, — чини следующей миграцией.

Обратимость обеспечивается не down-скриптом (Flyway Community его не исполняет, а откат
схемы на живых данных теряет данные), а тем, что изменение безопасно для предыдущей версии
кода: сначала аддитивная миграция, деплой, только потом удаление. Если откатить деплоем
старой версии сервиса нельзя — скажи это прямо и опиши процедуру отката в шапке файла.

## 6. Тест, который пытается сломать

На каждое новое ограничение — тест, проверяющий **отказ**. Без него ограничение не
протестировано.

```java
@Test
void rejectsStatusOutsideTheHttpRange_ck_idempotency_status_range() {
    assertThatThrownBy(() -> complete(UUID.randomUUID(), 600))
            .isInstanceOf(DataIntegrityViolationException.class);
}
```

Образец для подражания — `src/client-service/src/test/java/com/client360/client/IdempotencyKeysSchemaTest.java`:
он атакует каждое ограничение V5, проверяет наличие индекса и проверяет **отсутствие** FK,
которого там быть не должно.

Базовый класс `AbstractIntegrationTest` уже поднимает PostgreSQL 16, применяет
`db/init/01_bootstrap.sql` одним стейтментом (наивный сплит по `;` разрезал бы
dollar-quoted `DO`-блок) и прогоняет Flyway.

## 7. Прогнать

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-24"   # системный JAVA_HOME указывает на JRE 1.8
./mvnw -pl src/client-service -am test -Dtest=<ИмяТеста> -Dsurefire.failIfNoSpecifiedTests=false
```

Нужен запущенный демон Docker. Если Testcontainers падает с
`InvalidPathException: Illegal char <?>` — это не про код: демон недоступен, а перебор
запасных стратегий подключения спотыкается о битую запись `?C` в `PATH`.

## Чеклист

- [ ] DDL сверен с разделом SPEC, либо SPEC обновлён в этом же изменении.
- [ ] Все ограничения именованы по конвенции.
- [ ] Для каждого FK осознанно выбран `ON DELETE`, неочевидный выбор обоснован.
- [ ] Над каждым индексом — обслуживаемый запрос с идентификатором SPEC.
- [ ] Чувствительные колонки идут парой `_enc`/`_hash`, на таблице есть `key_version`.
- [ ] На каждое новое ограничение есть тест, который пытается его нарушить.
- [ ] Тест прогнан и проходит.
