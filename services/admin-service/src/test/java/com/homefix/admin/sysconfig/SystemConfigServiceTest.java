package com.homefix.admin.sysconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefix.admin.audit.AuditLogService;
import com.homefix.admin.support.InMemoryAuditLogStore;
import com.homefix.admin.support.InMemorySystemSettingStore;

/**
 * System Configuration rules (Requirement 19.2, 19.8): values are validated and normalised per
 * type, an unchanged value is neither written nor audited, and the typed readers fall back to the
 * default for an absent or no-longer-valid stored value.
 */
class SystemConfigServiceTest {

    private static final UUID ACTOR = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC);

    private InMemorySystemSettingStore store;
    private InMemoryAuditLogStore auditStore;
    private SystemConfigService service;

    @BeforeEach
    void setUp() {
        store = new InMemorySystemSettingStore();
        auditStore = new InMemoryAuditLogStore();
        service = new SystemConfigService(store, new AuditLogService(auditStore, new ObjectMapper(), CLOCK), CLOCK);
    }

    @Test
    void readersReturnDefaultsUntilChanged() {
        assertThat(service.numberValue(SystemSettingRegistry.AUDIT_PAGE_SIZE)).isEqualTo(50);
        assertThat(service.booleanValue(SystemSettingRegistry.AUDIT_SUMMARY_SHOWS_VALUES)).isTrue();

        service.update(Map.of("audit.pageSize", "20", "audit.summaryShowsValues", "false"), ACTOR);

        assertThat(service.numberValue(SystemSettingRegistry.AUDIT_PAGE_SIZE)).isEqualTo(20);
        assertThat(service.booleanValue(SystemSettingRegistry.AUDIT_SUMMARY_SHOWS_VALUES)).isFalse();
    }

    @Test
    void submittingTheCurrentValueChangesAndAuditsNothing() {
        service.update(Map.of("audit.pageSize", "50", "audit.summaryShowsValues", "TRUE"), ACTOR);

        assertThat(store.storedValues()).isEmpty();
        assertThat(auditStore.all()).isEmpty();
    }

    @Test
    void valuesAreCheckedAgainstTheirType() {
        for (String bad : new String[] {"9", "201", "fifty", "1.5", ""}) {
            assertThatThrownBy(() -> service.update(Map.of("audit.pageSize", bad), ACTOR))
                    .as(bad).isInstanceOf(SystemConfigValidationException.class);
        }
        assertThatThrownBy(() -> service.update(Map.of("audit.summaryShowsValues", "yes"), ACTOR))
                .isInstanceOf(SystemConfigValidationException.class)
                .hasMessageContaining("true or false");
        assertThat(store.storedValues()).isEmpty();
    }

    @Test
    void anOutOfRangeStoredValueReadsAsTheDefault() {
        store.with("audit.pageSize", "100000");

        assertThat(service.numberValue(SystemSettingRegistry.AUDIT_PAGE_SIZE)).isEqualTo(50);
        assertThat(service.all().get(0).value()).isEqualTo("50");
    }

    @Test
    void everyRegisteredDefaultIsValid() {
        for (SettingDefinition definition : SystemSettingRegistry.all()) {
            assertThat(definition.normalise(definition.defaultValue())).as(definition.key())
                    .isEqualTo(definition.defaultValue());
        }
    }
}
