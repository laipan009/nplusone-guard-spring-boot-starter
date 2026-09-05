# Changelog

## Unreleased

First public version. Experimental; not yet validated on a production service's test suite.

- Implicit-load detection on Hibernate events: proxy initialization, lazy collection
  initialization and EAGER associations fetched by a separate select are attributed to the session
  and the association; more than `nplusone.max-repeats` statements for one association in one
  session fails the test.
- Implicit loads are also counted across sessions inside the transaction, HTTP request or
  `inScope` call around them, catching a loop of small transactions that each lazily load one row.
- `detector.inScope(description, work)` for counting any call from a test as one unit of work.
- Explicit repeats (the same statement text in one transaction or HTTP request) are reported
  according to `nplusone.explicit-queries`: logged with a hint by default, optionally failing.
- Loads of entities and collections the mapping declares cacheable (`@Cacheable`, `@Cache`) are
  logged instead of failing the test.
- Sequence value selects are never counted; `nplusone.allowlist` excludes more.
- Auto-configuration through `HibernatePropertiesCustomizer` (statement inspector, interceptor,
  integrator provider), chained behind any inspector, interceptor or integrator provider the
  application configured; `TestExecutionListener` registered through `spring.factories`.
- Unit tests, H2 integration tests through all scenarios, and a JUnit engine test proving an
  unaware `@SpringBootTest` fails on N+1, passes on join fetch and passes on an explicit loop.
- English and Russian README and roadmap, MIT license, CI.
