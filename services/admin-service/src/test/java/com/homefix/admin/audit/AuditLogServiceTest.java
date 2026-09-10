package com.homefix.admin.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefix.admin.support.InMemoryAuditLogStore;

/**
 * Unit tests for {@link AuditLogService} (Requirement 19.8): every recorded action carries the
 * actor, action type, entity type/ID, timestamp, and the appropriate before/after values.
 */
class AuditLogServiceTest {

    private static final Instant FIXED = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID ACTOR = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private InMemoryAuditLogStore store;
    private AuditLogService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryAuditLogStore();
        service = new AuditLogService(store, new ObjectMapper(), Clock.fixed(FIXED, ZoneOffset.UTC));
    }

    @Test
    void recordCreateStoresInitialValuesAsAfterAndNoBefore() {
        service.recordCreate(ACTOR, "COUPON", "SAVE10", Map.of("discount", 10));

        assertThat(store.all()).hasSize(1);
        AuditLogEntry entry = store.all().get(0);
        assertThat(entry.getActorId()).isEqualTo(ACTOR);
        assertThat(entry.getActionType()).isEqualTo(AdminAction.CREATE);
        assertThat(entry.getEntityType()).isEqualTo("COUPON");
        assertThat(entry.getEntityId()).isEqualTo("SAVE10");
        assertThat(entry.getLoggedAt()).isEqualTo(FIXED);
        assertThat(entry.getBeforeValues()).isNull();
        assertThat(entry.getAfterValues()).contains("\"discount\":10");
    }

    @Test
    void recordUpdateStoresBothBeforeAndAfterValues() {
        service.recordUpdate(ACTOR, "USER", "u-1",
                Map.of("status", "ACTIVE"), Map.of("status", "SUSPENDED"));

        AuditLogEntry entry = store.all().get(0);
        assertThat(entry.getActionType()).isEqualTo(AdminAction.UPDATE);
        assertThat(entry.getBeforeValues()).contains("ACTIVE");
        assertThat(entry.getAfterValues()).contains("SUSPENDED");
    }

    @Test
    void recordDeleteStoresValuesAtDeletionAsBeforeAndNoAfter() {
        service.recordDelete(ACTOR, "REVIEW", "r-9", Map.of("rating", 1));

        AuditLogEntry entry = store.all().get(0);
        assertThat(entry.getActionType()).isEqualTo(AdminAction.DELETE);
        assertThat(entry.getBeforeValues()).contains("\"rating\":1");
        assertThat(entry.getAfterValues()).isNull();
    }

    @Test
    void recordApproveAndRejectAreCaptured() {
        service.recordApprove(ACTOR, "PROVIDER", "p-1",
                Map.of("state", "PENDING"), Map.of("state", "APPROVED"));
        service.recordReject(ACTOR, "PROVIDER", "p-2",
                Map.of("state", "PENDING"), Map.of("state", "REJECTED"));

        List<AuditLogEntry> byActor = store.findByActor(ACTOR);
        assertThat(byActor).extracting(AuditLogEntry::getActionType)
                .containsExactlyInAnyOrder(AdminAction.APPROVE, AdminAction.REJECT);
    }

    @Test
    void entriesAreQueryableByEntity() {
        service.recordCreate(ACTOR, "COUPON", "SAVE10", Map.of("discount", 10));
        service.recordUpdate(ACTOR, "COUPON", "SAVE10",
                Map.of("discount", 10), Map.of("discount", 15));

        assertThat(store.findByEntity("COUPON", "SAVE10")).hasSize(2);
    }
}
