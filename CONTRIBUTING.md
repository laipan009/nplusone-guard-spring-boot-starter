# Contributing

The project is experimental and has not yet been validated beyond its own test suite.

Use JDK 21. Run `./mvnw clean verify` before opening a pull request; this includes unit tests and
the H2 integration tests. No Docker is required.

The project is kept free of SonarQube issues under the default "Sonar way" profile, the same rules
the SonarQube for IDE plugin applies.

For a bug report, include a minimal reproducer (entity mapping, repository call, test), the
Java / Spring Boot / Hibernate versions, the `nplusone.*` configuration and the failure message
or the missing failure. Use synthetic entity and table names; remove internal addresses and data.

Proposals for new detection rules, for example non-select statements or a per-test override, are
welcome with tests and an entry in [docs/roadmap.md](docs/roadmap.md).

User-facing documentation (README, roadmap, FAQ) is kept in English and Russian; update both
versions in the same pull request. Code, Javadoc, comments and error messages stay in English.

External contributions use fork pull requests. The maintainer reviews all changes; code-owner
approval and successful CI are required.

Contributions are made under the project's [MIT License](LICENSE).
