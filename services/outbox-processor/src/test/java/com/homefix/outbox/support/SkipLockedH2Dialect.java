package com.homefix.outbox.support;

import org.hibernate.dialect.H2Dialect;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;

/**
 * Stock Hibernate {@link H2Dialect} with skip-locked support switched on.
 *
 * <p>H2 2.x executes {@code SELECT ... FOR UPDATE SKIP LOCKED}, but Hibernate 6.4's
 * {@code H2Dialect} reports no support for it and silently drops the clause, so claim queries on
 * H2 would block on each other's row locks where PostgreSQL skips them. This flag is the only
 * difference; how the repository's lock-timeout hint becomes the clause is stock Hibernate, and the
 * stock {@code PostgreSQLDialect} already reports the support.
 */
public class SkipLockedH2Dialect extends H2Dialect {

    public SkipLockedH2Dialect(DialectResolutionInfo info) {
        super(info);
    }

    public SkipLockedH2Dialect() {
        super();
    }

    @Override
    public boolean supportsSkipLocked() {
        return true;
    }
}
