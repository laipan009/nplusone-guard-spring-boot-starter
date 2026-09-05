# nplusone-guard-spring-boot-starter

English · [Русский](README.ru.md)

[![CI](https://github.com/laipan009/nplusone-guard-spring-boot-starter/actions/workflows/ci.yml/badge.svg)](https://github.com/laipan009/nplusone-guard-spring-boot-starter/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![Java 21](https://img.shields.io/badge/Java-21-orange)
![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F)

**Makes your Spring Boot tests fail when Hibernate runs an N+1 query.** One dependency in test
scope, no annotations, no base class, no assertions to write: every `@SpringBootTest` is guarded.

```text
io.github.laipan009.nplusone.core.NPlusOneViolationsError:
N+1 detected: Hibernate loaded the same association by separate selects more than 2 times in one session (nplusone.max-repeats=2)
  5 x lazy load of Author (proxy)  [session d1d3a87b]
      select a1_0.id,a1_0.name from author a1_0 where a1_0.id=?
Fix: join fetch, @EntityGraph or @BatchSize on the association
```

> **Status: experimental, first public alpha in preparation.** Extracted from one production
> service where a simpler version guards the integration tests. The event-based detector and the
> starter packaging are validated only by this project's own tests. APIs and defaults may change.

## The problem

You load a list of books and Hibernate quietly runs one more `select` per book to fetch its
author. Lazy `*ToOne`, lazy collections and EAGER associations all do it. Nothing fails: the unit
tests are green, the integration tests are green, the code review sees a clean loop. The service
this project came from ran 49 statements for one request, and nobody knew until somebody read the
SQL log by hand.

Tools that report N+1 exist. What was missing is a gate that fails the build, needs no per-test
code, and does not cry wolf.

## What you get

- **Implicit N+1 fails the test.** Proxy initialization, lazy collection initialization and EAGER
  associations fetched by a separate select are issued by Hibernate on its own, once per owner
  row. More than N statements for the same association in one session is N+1 by construction,
  not by heuristic, so there are no false positives to allowlist.
- **Explicit repeats are logged with a hint.** `findById` in a loop, pagination, retries: the
  application ran the same statement text again. Wasteful more often than not, but only the
  author knows. The guard prints the statements and a hint (`findAllById`, `in (...)`) and lets
  the test pass. One property switches this to failing.
- **Cacheable data is logged, not failed.** For entities and collections marked `@Cacheable` or
  `@Cache`, a warm second-level cache serves the rows without statements in production, so the
  test-time cost is reported and the test passes.
- **Nothing to write in tests.** A `TestExecutionListener` registered through `spring.factories`
  attaches to every `@SpringBootTest`. Your existing Hibernate `StatementInspector`,
  `Interceptor` or `Integrator` keep working; the starter chains behind them.
- **Any database.** The guard listens to Hibernate, not to the JDBC driver.

Requires JDK 21, Spring Boot 3.5.x and Hibernate ORM 6.6. Other versions have not been verified.

## Quick start

The artifact is not yet on Maven Central. Build and install it locally:

```sh
./mvnw clean verify
./mvnw install -DskipTests
```

Add it to the project you want to guard:

```xml
<dependency>
    <groupId>io.github.laipan009.nplusone</groupId>
    <artifactId>nplusone-guard-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

Run the tests. A test that triggers an N+1 fails with the message shown at the top. A loop of
explicit queries is reported in the log while the test passes:

```text
WARN NPlusOneTestExecutionListener -- BookServiceIT.titlesOneByOne: Repeated query: the application ran the same select more than 2 times in one transaction or request; one query with in (...) or findAllById would do (nplusone.explicit-queries)
  5 x select b1_0.id,b1_0.author_id,b1_0.title from book b1_0 where b1_0.id=?  [transaction]
Fix the loop, raise nplusone.max-repeats or add the statement to nplusone.allowlist
```

Adopting in a suite that already has N+1? Start with `nplusone.fail-test=false`, see
[Adopting in an existing suite](#adopting-in-an-existing-suite).

## What is caught

| Situation | Result |
|---|---|
| Lazy `@ManyToOne` / `@OneToOne` proxy initialized for every row | test fails |
| Lazy collection initialized for every row | test fails |
| EAGER association fetched by a separate select for every row | test fails |
| A loop of small transactions that each lazily load one row | test fails, counted per request or per `inScope` call |
| Any of the above on an entity or collection declared cacheable | WARN, test passes |
| `findById` or the same query repeated in a loop | WARN with a hint, test passes (`nplusone.explicit-queries=fail` to fail) |
| Association fetched with `join fetch`, `@EntityGraph` or `@BatchSize` | passes |
| Two loads of the same association in non-loop code, for example both accounts of a transfer | passes, below the threshold |
| Sequence value fetches, statements matching `nplusone.allowlist` | ignored |

## Where it counts

Out of the box the guard counts inside two units of work: a Hibernate transaction and a servlet
request. That covers a typical Spring Boot service with Spring Data JPA and REST.

Code that runs outside both, for example a job or a message listener called directly from a test
without a transaction around the whole call, is not counted. The test can wrap the call:

```java
@Autowired
private NPlusOneDetector detector;

@Test
void exportsEveryDepartment() {
    var report = detector.inScope("nightly export", () -> exportJob.run());

    assertThat(report.departments()).hasSize(3);
}
```

Everything the call does on the current thread is then counted as one unit of work, and the
test fails after the method if there was an N+1 inside it.

## How it works

```text
@SpringBootTest ──(spring.factories)──> NPlusOneTestExecutionListener
                                            │ beforeTestMethod: drop stale results
                                            │ afterTestMethod:  evaluate sessions, throw / log
                                            ▼
   Hibernate events ─ LoadEvent IMMEDIATE_LOAD / INTERNAL_LOAD_EAGER ─┐
                    ─ InitializeCollectionEvent ──────────────────────┤ mark "implicit load of X in session S"
   Hibernate ─ StatementInspector.inspect(sql) ───────────────────────┘ attribute the select to (S, X)
                                                                       or, with no mark, to the open
   Hibernate ─ Interceptor.afterTransactionBegin/Completion ──────────> transaction scope
   Servlet   ─ NPlusOneRequestScopeFilter ────────────────────────────> request scope
```

- The auto-configuration registers one `NPlusOneDetector` bean as Hibernate's
  `StatementInspector` and `Interceptor`, and an `Integrator` that adds two event listeners around
  Hibernate's own load and collection-initialization listeners. An inspector, interceptor or
  integrator provider the application had already configured, through `spring.jpa.properties` or
  another `HibernatePropertiesCustomizer`, is kept and chained behind the detector.
- The listener that runs before Hibernate's marks the thread with the session and the subject
  being loaded: `lazy load of Author (proxy)`, `lazy load of collection Author.books`,
  `eager select of Publisher`. Every select inspected while the mark is on the stack counts for
  that session and subject. The listener that runs after removes the mark. Explicit `find`,
  `getReference` and queries carry no mark.
- After each test method the listener evaluates every session seen during the test: more than
  `max-repeats` statements for one subject in one session is an implicit violation, and the test
  fails. If the mapping declares that subject cacheable, it is a cacheable-load report instead,
  logged at WARN.
- Implicit loads are also counted across sessions inside the transaction, the HTTP request or
  the `inScope` call around them. A loop of small transactions that each lazily load one row
  never exceeds the threshold in any session, but does in the unit of work that runs the loop. A
  subject already reported for a session is not reported again for the scopes around it.
  Sessions still open at that point, such as a `@Transactional` test's own session, are
  evaluated too; a session that continues after the test is not counted again.
- Selects without a mark are counted by text inside the same units of work. More than
  `max-repeats` identical statements is an explicit violation, handled according to
  `nplusone.explicit-queries`.
- Statements that fetch sequence values (`next value for`, `nextval`) and statements matching the
  allowlist are never counted.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `nplusone.enabled` | `true` | Register the detector at all |
| `nplusone.max-repeats` | `2` | Statements one association may cost in a session, and times one explicit select may run in a transaction or request; one more is a violation |
| `nplusone.explicit-queries` | `log` | `log` prints explicit repeats at WARN, `fail` fails the test like an implicit N+1, `off` ignores them |
| `nplusone.allowlist` | empty | Regular expressions, matched with `find()` against the SQL text; matching statements are never counted, in either kind |
| `nplusone.fail-test` | `true` | `false` turns every failure into a WARN; useful while adopting the guard in a service with known N+1 |
| `nplusone.request-scope` | `true` | Count explicit repeats per servlet request as well as per transaction |

Put these in `src/test/resources/application.properties` or `application-<profile>.yml` of the
test profile.

```yaml
nplusone:
  max-repeats: 3
  explicit-queries: fail
  allowlist:
    - "from audit_log"
```

You can also declare your own `NPlusOneDetector` bean; the auto-configuration backs off.

## Adopting in an existing suite

1. Add the dependency with `nplusone.fail-test=false` and run the suite. Every violation is logged
   with the test name, the association and the SQL.
2. Fix the associations (`join fetch`, `@EntityGraph`, `@BatchSize`). Look at the explicit-query
   hints while you are there.
3. Remove `fail-test=false`.

Fixtures deserve a look too. Spring Data's `deleteAll()` loads every row before deleting it, and an
EAGER association on those rows becomes an N+1 inside your `@BeforeEach`; `deleteAllInBatch()`
does not. This project's own sample fixture was caught exactly that way.

For a call tree that shows which Java frames triggered the loads, add
[JPlusOne](https://github.com/adgadev/jplusone) in test scope next to this starter; the two do
not interfere.

## Limitations

- **Cacheable in mapping, uncached in reality.** Loads of entities declared cacheable never fail
  the test, on the assumption that production runs the second-level cache. If it does not, that
  N+1 is real and only shows up in the log.
- **Threshold.** Two lazy loads of the same association in non-loop code, for example the owners
  of both accounts in a transfer, are not N+1. The default threshold lets them through and catches
  loops from three rows up. Seed at least `max-repeats + 1` rows in tests that should catch a loop.
- **Transactional tests hide lazy loading.** Rows seeded inside a `@Transactional` test's own
  session sit in the first-level cache and are never lazily loaded, so there is nothing to detect.
  Seed in a separate transaction (`REQUIRES_NEW`, `@Sql`) or keep tests black-box.
- **Parallel tests in one JVM.** Sessions on server threads cannot be tied to a test; the detector
  is shared per Spring context. Surefire forks are fine; `junit.jupiter.execution.parallel` is not.
- **Async work.** A session that starts on a background thread and outlives the test method is
  evaluated with whatever it has done by then and never blamed on the next test.
- **Chaining order.** The starter's customizer runs last and wraps whatever inspector, interceptor
  or integrator provider it finds. A customizer that deliberately orders itself after
  `Ordered.LOWEST_PRECEDENCE` would still replace the detector.
- **Calls outside a transaction or request.** A job or listener without a transaction around the
  whole call is outside both automatic units of work. Implicit loads inside one of its
  transactions are still caught per session; a loop of small transactions, and explicit repeats,
  are not. See [Where it counts](#where-it-counts) for wrapping the call from a test.
- Servlet only for the request scope. Reactive stacks get everything else.

See [docs/roadmap.md](docs/roadmap.md) for the planned answers.

## Compared with other tools

| Tool | Hibernate 6 / Boot 3 | Fails the test | Per-test code |
|---|---|---|---|
| `spring-hibernate-query-utils` | no, stopped at Hibernate 5 | yes | none |
| QuickPerf | no, stopped at Hibernate 5 | yes | annotation per test |
| JPlusOne | yes | no, reports a call tree | none |
| datasource-proxy, `SQLStatementCountValidator` | yes | yes | assertion per test |
| **this starter** | yes | yes, implicit N+1 only | none |

## Development

```sh
./mvnw clean verify
```

Unit tests cover the detector, the event listeners, the auto-configuration and the test
listener. Integration tests run a sample JPA application on H2 through proxies, lazy collections
with and without `@BatchSize`, an EAGER association, a cacheable reference entity, an explicit
loop, a sequence with allocation size 1 and open-in-view, plus a nested JUnit engine that proves
an unaware `@SpringBootTest` fails with the expected message. No Docker is needed.

See [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request and
[docs/roadmap.md](docs/roadmap.md) for what comes next.

## License

[MIT](LICENSE).
