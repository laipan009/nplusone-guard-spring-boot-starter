# Roadmap and open questions

English · [Русский](roadmap.ru.md)

Ordered by how often the limitation is expected to bite. Items stay within the project's scope:
Hibernate's own behaviour and the two automatic units of work (transaction, servlet request);
anything else is wrapped from test code with `detector.inScope(...)`.

1. **Validate the zero-false-positive claim on real suites.** Run the guard with
   `nplusone.fail-test=false` on several existing integration suites and count how many implicit
   violations were real N+1 versus deliberate repeats. The default threshold and the split between
   failing and logging stand or fall on that ratio.
2. **Per-test override.** `@NPlusOneGuard(maxRepeats = 5)` or `@NPlusOneGuard(enabled = false)` on
   a test method or class, for the rare deliberate repeat that should not go into a global allowlist.
3. **Optional JPlusOne report on failure.** When `com.adgadev.jplusone` is on the classpath, append
   the call tree of the session to the failure message (`nplusone.report=true`).
4. **Call site for explicit repeats.** Capture the first application frame that issued a repeated
   explicit query, so the hint names the loop instead of only the SQL.
