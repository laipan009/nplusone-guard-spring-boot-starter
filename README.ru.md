# nplusone-guard-spring-boot-starter

[English](README.md) · Русский

[![CI](https://github.com/laipan009/nplusone-guard-spring-boot-starter/actions/workflows/ci.yml/badge.svg)](https://github.com/laipan009/nplusone-guard-spring-boot-starter/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![Java 21](https://img.shields.io/badge/Java-21-orange)
![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F)

**Валит тесты Spring Boot, когда Hibernate выполняет N+1 запрос.** Одна зависимость в test scope,
без аннотаций, базового класса и assert'ов: каждый `@SpringBootTest` под защитой.

```text
io.github.laipan009.nplusone.core.NPlusOneViolationsError:
N+1 detected: Hibernate loaded the same association by separate selects more than 2 times in one session (nplusone.max-repeats=2)
  5 x lazy load of Author (proxy)  [session d1d3a87b]
      select a1_0.id,a1_0.name from author a1_0 where a1_0.id=?
Fix: join fetch, @EntityGraph or @BatchSize on the association
```

> **Статус: экспериментальная версия, готовится первая публичная alpha.** Идея вынесена из одного
> production-сервиса, где более простой вариант защищает интеграционные тесты. Детектор на
> событиях Hibernate и упаковка в стартер проверены только собственными тестами проекта. API и
> значения по умолчанию могут меняться.

## Проблема

Вы загружаете список книг, а Hibernate тихо выполняет ещё по одному `select` на книгу, чтобы
подтянуть автора. Так делают ленивые `*ToOne`, ленивые коллекции и EAGER-связи. Ничего не падает:
unit-тесты зелёные, интеграционные зелёные, на code review виден чистый цикл. Сервис, из которого
вынесен этот проект, выполнял 49 запросов на одно обращение, и никто не знал об этом, пока кто-то
не прочитал SQL-лог руками.

Инструменты, которые показывают N+1, есть. Не хватало гейта, который валит сборку, не требует
кода в каждом тесте и не поднимает ложную тревогу.

## Что вы получаете

- **Неявный N+1 валит тест.** Инициализация proxy, инициализация ленивой коллекции и EAGER-связь,
  подгруженная отдельным select, выполняются самим Hibernate по одной на строку-владельца. Больше
  N запросов на одну связь в одной сессии это N+1 по построению, а не по эвристике, так что
  ложных срабатываний, которые пришлось бы вносить в allowlist, нет.
- **Явные повторы логируются с подсказкой.** `findById` в цикле, пагинация, retry: приложение
  само повторило один и тот же запрос. Чаще всего это расточительно, но знает об этом только
  автор. Стартер печатает запросы и подсказку (`findAllById`, `in (...)`), а тест проходит. Одно
  свойство переключает это на падение.
- **Кэшируемые данные логируются, а не валят.** Для сущностей и коллекций с `@Cacheable` или
  `@Cache` прогретый second-level cache отдаёт строки в проде без запросов, поэтому цена в тесте
  сообщается, а тест проходит.
- **В тестах писать нечего.** `TestExecutionListener`, зарегистрированный через
  `spring.factories`, подключается к каждому `@SpringBootTest`. Ваши `StatementInspector`,
  `Interceptor` и `Integrator` Hibernate продолжают работать: стартер встаёт в цепочку перед ними.
- **Любая база данных.** Стартер слушает Hibernate, а не JDBC-драйвер.

Нужны JDK 21, Spring Boot 3.5.x и Hibernate ORM 6.6. Другие версии не проверялись.

## Быстрый старт

Артефакта в Maven Central пока нет. Соберите и установите локально:

```sh
./mvnw clean verify
./mvnw install -DskipTests
```

Подключите в проект, который хотите защитить:

```xml
<dependency>
    <groupId>io.github.laipan009.nplusone</groupId>
    <artifactId>nplusone-guard-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

Запустите тесты. Тест, в котором случился N+1, падает с сообщением, показанным в начале. Явный
цикл запросов попадает в лог, а тест проходит:

```text
WARN NPlusOneTestExecutionListener -- BookServiceIT.titlesOneByOne: Repeated query: the application ran the same select more than 2 times in one transaction or request; one query with in (...) or findAllById would do (nplusone.explicit-queries)
  5 x select b1_0.id,b1_0.author_id,b1_0.title from book b1_0 where b1_0.id=?  [transaction]
Fix the loop, raise nplusone.max-repeats or add the statement to nplusone.allowlist
```

Внедряете в набор тестов, где N+1 уже есть? Начните с `nplusone.fail-test=false`, см.
[Внедрение в существующий набор тестов](#внедрение-в-существующий-набор-тестов).

## Что ловится

| Ситуация | Результат |
|---|---|
| Ленивый `@ManyToOne` / `@OneToOne` proxy инициализируется на каждую строку | тест падает |
| Ленивая коллекция инициализируется на каждую строку | тест падает |
| EAGER-связь подгружается отдельным select на каждую строку | тест падает |
| Цикл маленьких транзакций, каждая лениво грузит одну строку | тест падает, считается по запросу или по вызову `inScope` |
| Любое из перечисленного на сущности или коллекции, объявленной кэшируемой | WARN, тест проходит |
| `findById` или один и тот же запрос повторяется в цикле | WARN с подсказкой, тест проходит (`nplusone.explicit-queries=fail`, чтобы падал) |
| Связь загружена через `join fetch`, `@EntityGraph` или `@BatchSize` | проходит |
| Две загрузки одной связи вне цикла, например оба счёта при переводе | проходит, ниже порога |
| Запросы значений sequence, запросы из `nplusone.allowlist` | игнорируются |

## Где считается

Из коробки стартер считает внутри двух единиц работы: транзакции Hibernate и servlet-запроса.
Этого хватает типовому сервису на Spring Boot, Spring Data JPA и REST.

Код вне обеих, например job или listener сообщений, вызванный из теста напрямую без транзакции
вокруг всего вызова, не считается. Тест может обернуть вызов:

```java
@Autowired
private NPlusOneDetector detector;

@Test
void exportsEveryDepartment() {
    var report = detector.inScope("nightly export", () -> exportJob.run());

    assertThat(report.departments()).hasSize(3);
}
```

Всё, что вызов делает в текущем потоке, считается как одна единица работы, и тест упадёт после
метода, если внутри неё был N+1.

## Как это работает

```text
@SpringBootTest ──(spring.factories)──> NPlusOneTestExecutionListener
                                            │ beforeTestMethod: сбросить старые результаты
                                            │ afterTestMethod:  оценить сессии, бросить / залогировать
                                            ▼
   События Hibernate ─ LoadEvent IMMEDIATE_LOAD / INTERNAL_LOAD_EAGER ─┐
                     ─ InitializeCollectionEvent ──────────────────────┤ метка "неявная загрузка X в сессии S"
   Hibernate ─ StatementInspector.inspect(sql) ────────────────────────┘ отнести select к (S, X)
                                                                        или, без метки, к открытой
   Hibernate ─ Interceptor.afterTransactionBegin/Completion ───────────> области транзакции
   Servlet   ─ NPlusOneRequestScopeFilter ─────────────────────────────> области запроса
```

- Автоконфигурация регистрирует один бин `NPlusOneDetector` как `StatementInspector` и `Interceptor`
  Hibernate, а также `Integrator`, который добавляет два слушателя событий вокруг собственных
  слушателей Hibernate на загрузку сущности и инициализацию коллекции. Inspector, interceptor или
  integrator provider, которые приложение уже настроило через `spring.jpa.properties` или другой
  `HibernatePropertiesCustomizer`, сохраняются и вызываются в цепочке после детектора.
- Слушатель, который выполняется до Hibernate, помечает поток сессией и предметом загрузки:
  `lazy load of Author (proxy)`, `lazy load of collection Author.books`, `eager select of Publisher`.
  Каждый `select`, прошедший через inspector, пока метка стоит, засчитывается этой сессии и этому
  предмету. Слушатель после Hibernate метку снимает. Явные `find`, `getReference` и запросы метки не
  несут.
- После каждого тестового метода листенер оценивает все сессии, встреченные за тест: больше
  `max-repeats` запросов на один предмет в одной сессии значит неявное нарушение, и тест падает.
  Если маппинг объявляет этот предмет кэшируемым, вместо падения будет WARN о загрузке кэшируемых
  данных.
- Неявные загрузки считаются и поверх сессий: внутри транзакции, HTTP-запроса или вызова `inScope`
  вокруг них. Цикл маленьких транзакций, каждая из которых лениво грузит одну строку, ни в одной
  сессии порог не превысит, но превысит его в единице работы, которая этот цикл выполняет.
  Предмет, уже помеченный для сессии, второй раз для области вокруг неё не сообщается.
  Ещё открытые сессии, например собственная сессия `@Transactional`-теста, тоже оцениваются; сессия,
  продолжившая работу после теста, второй раз не считается.
- `select` без метки считаются по тексту внутри тех же единиц работы. Больше `max-repeats`
  одинаковых запросов значит явное нарушение, судьба которого определяется
  `nplusone.explicit-queries`.
- Запросы значений sequence (`next value for`, `nextval`) и запросы из allowlist не считаются никогда.

## Настройка

| Property | По умолчанию | Смысл |
|---|---|---|
| `nplusone.enabled` | `true` | Регистрировать детектор вообще |
| `nplusone.max-repeats` | `2` | Сколько запросов может стоить одна связь в сессии и сколько раз один явный `select` может выполниться в транзакции или запросе; следующий раз считается нарушением |
| `nplusone.explicit-queries` | `log` | `log` печатает явные повторы на уровне WARN, `fail` валит тест как неявный N+1, `off` игнорирует |
| `nplusone.allowlist` | пусто | Регулярные выражения, проверяются через `find()` по тексту SQL; совпавшие запросы не считаются ни в одном из видов |
| `nplusone.fail-test` | `true` | `false` превращает любое падение в WARN; удобно при внедрении в сервис с известными N+1 |
| `nplusone.request-scope` | `true` | Считать явные повторы не только по транзакции, но и по servlet-запросу |

Свойства кладутся в `src/test/resources/application.properties` или `application-<profile>.yml`
тестового профиля.

```yaml
nplusone:
  max-repeats: 3
  explicit-queries: fail
  allowlist:
    - "from audit_log"
```

Можно объявить свой бин `NPlusOneDetector`, автоконфигурация отступит.

## Внедрение в существующий набор тестов

1. Подключите зависимость с `nplusone.fail-test=false` и прогоните тесты. Каждое нарушение попадёт в лог
   с именем теста, связью и SQL.
2. Исправьте связи (`join fetch`, `@EntityGraph`, `@BatchSize`). Заодно посмотрите подсказки по явным
   повторам.
3. Уберите `fail-test=false`.

На фикстуры тоже стоит взглянуть. Spring Data `deleteAll()` загружает все строки перед удалением, и
EAGER-связь на этих строках превращается в N+1 внутри вашего `@BeforeEach`; `deleteAllInBatch()` так не
делает. Фикстура этого проекта была поймана именно так.

Чтобы увидеть, какие Java-фреймы вызвали загрузки, добавьте в test scope
[JPlusOne](https://github.com/adgadev/jplusone) рядом со стартером; они не мешают друг другу.

## Ограничения

- **Кэшируемое по маппингу, некэшируемое на деле.** Загрузки сущностей, объявленных кэшируемыми,
  никогда не валят тест в предположении, что в проде second-level cache включён. Если это не так,
  такой N+1 настоящий, и виден он только в логе.
- **Порог.** Две ленивые загрузки одной связи вне цикла, например владельцы обоих счетов при переводе,
  это не N+1. Порог по умолчанию их пропускает, а циклы от трёх строк ловит. В тестах, которые должны
  поймать цикл, сейте не меньше `max-repeats + 1` строк.
- **Транзакционные тесты прячут ленивую загрузку.** Строки, созданные внутри собственной сессии
  `@Transactional`-теста, лежат в first-level cache и никогда не загружаются лениво, детектировать
  нечего. Сейте данные в отдельной транзакции (`REQUIRES_NEW`, `@Sql`) или держите тесты чёрным ящиком.
- **Параллельные тесты в одной JVM.** Сессии на серверных потоках нельзя привязать к тесту; детектор
  один на Spring-контекст. Форки surefire работают, `junit.jupiter.execution.parallel` нет.
- **Асинхронная работа.** Сессия, начатая в фоновом потоке и пережившая тестовый метод, оценивается
  по тому, что успела сделать, и никогда не приписывается следующему тесту.
- **Порядок в цепочке.** Customizer стартера выполняется последним и оборачивает найденные inspector,
  interceptor и integrator provider. Customizer, который сознательно ставит себя после
  `Ordered.LOWEST_PRECEDENCE`, всё равно заменит детектор.
- **Вызовы вне транзакции и запроса.** Job или listener без транзакции вокруг всего вызова лежит
  вне обеих автоматических единиц работы. Неявные загрузки внутри одной из его транзакций всё равно
  ловятся по сессии, а цикл маленьких транзакций и явные повторы нет. Как обернуть вызов из теста,
  см. раздел [Где считается](#где-считается).
- Только servlet для области запроса. Реактивный стек получает всё остальное.

Планы по этим пунктам в [docs/roadmap.ru.md](docs/roadmap.ru.md).

## Сравнение с другими инструментами

| Инструмент | Hibernate 6 / Boot 3 | Валит тест | Код в каждом тесте |
|---|---|---|---|
| `spring-hibernate-query-utils` | нет, остановился на Hibernate 5 | да | не нужен |
| QuickPerf | нет, остановился на Hibernate 5 | да | аннотация на тест |
| JPlusOne | да | нет, показывает дерево вызовов | не нужен |
| datasource-proxy, `SQLStatementCountValidator` | да | да | assert в каждом тесте |
| **этот стартер** | да | да, только неявный N+1 | не нужен |

## Разработка

```sh
./mvnw clean verify
```

Unit-тесты покрывают детектор, слушатели событий, автоконфигурацию и тестовый листенер.
Интеграционные тесты гоняют образцовое JPA-приложение на H2 через proxy, ленивые коллекции с
`@BatchSize` и без, EAGER-связь, кэшируемый справочник, явный цикл, sequence с allocation size 1 и
open-in-view, плюс вложенный движок JUnit, который доказывает, что ничего не знающий о стартере
`@SpringBootTest` падает с ожидаемым сообщением. Docker не нужен.

Перед pull request прочитайте [CONTRIBUTING.md](CONTRIBUTING.md); планы в
[docs/roadmap.ru.md](docs/roadmap.ru.md).

## Лицензия

[MIT](LICENSE).
