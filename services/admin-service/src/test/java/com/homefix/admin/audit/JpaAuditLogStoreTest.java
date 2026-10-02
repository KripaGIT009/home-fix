package com.homefix.admin.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

/**
 * Runs the Audit_Log keyset query against a real (H2) database: filters are optional and
 * combinable, the entity-type match is a case-insensitive substring with LIKE wildcards taken
 * literally, and a cursor continues strictly after its position, ties broken by id.
 */
@DataJpaTest(properties = {
        // H2 with a Hibernate-generated schema: the migrations are PostgreSQL DDL in schema "admin".
        "spring.jpa.properties.hibernate.default_schema=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@Import(JpaAuditLogStore.class)
class JpaAuditLogStoreTest {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final Instant BASE = Instant.parse("2026-10-01T09:00:00Z");

    @Autowired
    private JpaAuditLogStore store;

    private AuditLogEntry append(int minute, AdminAction action, String entityType) {
        return store.append(new AuditLogEntry(UUID.randomUUID(), ACTOR, action, entityType, "e-" + minute,
                null, "{}", BASE.plusSeconds(60L * minute)));
    }

    @Test
    void returnsNewestFirstWithOptionalFilters() {
        append(1, AdminAction.CREATE, "COUPON");
        append(2, AdminAction.UPDATE, "Coupon");
        append(3, AdminAction.UPDATE, "SYSTEM_CONFIG");
        append(4, AdminAction.DELETE, "USER");

        assertThat(store.findPage(null, null, null, 10)).extracting(AuditLogEntry::getEntityId)
                .containsExactly("e-4", "e-3", "e-2", "e-1");
        assertThat(store.findPage(AdminAction.UPDATE, null, null, 10)).extracting(AuditLogEntry::getEntityId)
                .containsExactly("e-3", "e-2");
        assertThat(store.findPage(null, "coup", null, 10)).extracting(AuditLogEntry::getEntityId)
                .containsExactly("e-2", "e-1");
        assertThat(store.findPage(AdminAction.CREATE, "COUPON", null, 10)).extracting(AuditLogEntry::getEntityId)
                .containsExactly("e-1");
        // "_" is a literal underscore, not a single-character wildcard (which would match "oup").
        assertThat(store.findPage(null, "o_p", null, 10)).isEmpty();
        assertThat(store.findPage(null, "%", null, 10)).isEmpty();
        assertThat(store.findPage(null, "_config", null, 10)).extracting(AuditLogEntry::getEntityId)
                .containsExactly("e-3");
        assertThat(store.findPage(null, null, null, 2)).hasSize(2);
    }

    @Test
    void cursorContinuesStrictlyAfterItsPositionIncludingTies() {
        append(1, AdminAction.UPDATE, "USER");
        append(2, AdminAction.UPDATE, "USER");
        append(2, AdminAction.UPDATE, "USER");
        append(3, AdminAction.UPDATE, "USER");

        List<AuditLogEntry> first = store.findPage(null, null, null, 2);
        List<AuditLogEntry> second = store.findPage(null, null, AuditCursor.after(first.get(1)), 2);

        assertThat(first).hasSize(2);
        assertThat(second).hasSize(2);
        assertThat(first).extracting(AuditLogEntry::getId)
                .doesNotContainAnyElementsOf(second.stream().map(AuditLogEntry::getId).toList());
        assertThat(second.get(1).getEntityId()).isEqualTo("e-1");
    }
}
