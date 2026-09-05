package io.github.laipan009.nplusone.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "nplusone")
public class NPlusOneGuardProperties {

    /**
     * Register the detector in Hibernate and fail tests on violations.
     */
    private boolean enabled = true;

    /**
     * How many statements the same association may cost in one Hibernate session, and how many times the same
     * explicit select may run in one transaction or request. One more is a violation.
     */
    private int maxRepeats = 2;

    /**
     * What to do with explicit repeats such as findById in a loop: log a hint (default), fail the test, or ignore.
     * Implicit N+1 from lazy loading always fails the test.
     */
    private ExplicitQueriesMode explicitQueries = ExplicitQueriesMode.LOG;

    /**
     * Regular expressions matched with find() against the SQL text; matching statements are never counted.
     */
    private List<String> allowlist = new ArrayList<>();

    /**
     * Throw after the test method when violations were recorded. When false, everything is only logged at WARN;
     * useful while adopting the guard in a service with known N+1 queries.
     */
    private boolean failTest = true;

    /**
     * Count explicit repeats per servlet request as well as per transaction, so repository calls in a loop outside
     * a transaction are reported too.
     */
    private boolean requestScope = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxRepeats() {
        return maxRepeats;
    }

    public void setMaxRepeats(int maxRepeats) {
        this.maxRepeats = maxRepeats;
    }

    public ExplicitQueriesMode getExplicitQueries() {
        return explicitQueries;
    }

    public void setExplicitQueries(ExplicitQueriesMode explicitQueries) {
        this.explicitQueries = explicitQueries;
    }

    public List<String> getAllowlist() {
        return allowlist;
    }

    public void setAllowlist(List<String> allowlist) {
        this.allowlist = allowlist;
    }

    public boolean isFailTest() {
        return failTest;
    }

    public void setFailTest(boolean failTest) {
        this.failTest = failTest;
    }

    public boolean isRequestScope() {
        return requestScope;
    }

    public void setRequestScope(boolean requestScope) {
        this.requestScope = requestScope;
    }
}
