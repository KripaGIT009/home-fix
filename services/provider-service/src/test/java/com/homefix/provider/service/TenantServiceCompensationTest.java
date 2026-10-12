package com.homefix.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

import com.homefix.provider.catalog.CatalogClientPort;
import com.homefix.provider.domain.ProviderProfileRepository;
import com.homefix.provider.domain.Tenant;
import com.homefix.provider.domain.Tenant.TenantDetails;
import com.homefix.provider.domain.TenantAdmin;
import com.homefix.provider.domain.TenantAdminRepository;
import com.homefix.provider.domain.TenantRepository;
import com.homefix.provider.support.FakeAuthUserClient;

/**
 * The cross-service compensation of {@link TenantService} (Requirements MT-2.2, MT-2.4) when the
 * membership write itself fails — something a real database will not do on demand, so the
 * administrator repository is a mock here: a grant whose membership could not be recorded is
 * revoked again, and a removal revokes before it deletes, so neither of its failures needs a
 * compensating write.
 */
class TenantServiceCompensationTest {

    private final UUID actor = UUID.randomUUID();

    private TenantAdminRepository admins;
    private FakeAuthUserClient auth;
    private TenantService service;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        TenantRepository tenants = mock(TenantRepository.class);
        admins = mock(TenantAdminRepository.class);
        auth = new FakeAuthUserClient();
        tenant = Tenant.create(new TenantDetails("Ara Home Services", null, null, 25.556, 84.6603,
                new BigDecimal("20.0"), Set.of(UUID.randomUUID())), actor);
        when(tenants.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        service = new TenantService(tenants, admins, mock(ProviderProfileRepository.class),
                mock(CatalogClientPort.class), auth);
    }

    @Test
    void addAdmin_whenRecordingFails_revokesTheGrantAndReportsTheFailure() {
        UUID user = auth.register("+919000000031");
        when(admins.findById(user)).thenReturn(Optional.empty());
        when(admins.saveAndFlush(any(TenantAdmin.class))).thenThrow(new QueryTimeoutException("db down"));

        assertThatThrownBy(() -> service.addAdmin(tenant.getId(), "+919000000031", actor))
                .isInstanceOf(QueryTimeoutException.class);

        assertThat(auth.calls).containsExactly("grant " + user, "revoke " + user);
    }

    @Test
    void addAdmin_compensationFailureIsLoggedNotMasked() {
        UUID user = auth.register("+919000000031");
        when(admins.findById(user)).thenReturn(Optional.empty());
        when(admins.saveAndFlush(any(TenantAdmin.class))).thenThrow(new QueryTimeoutException("db down"));
        auth.revokeFailure = new ProviderException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "AUTH_UNAVAILABLE", "down");

        // The original failure is what the Admin sees; the stranded grant is logged at ERROR.
        assertThatThrownBy(() -> service.addAdmin(tenant.getId(), "+919000000031", actor))
                .isInstanceOf(QueryTimeoutException.class);
        assertThat(auth.calls).containsExactly("grant " + user);
    }

    /**
     * Review finding: the old order deleted the membership, then restored it in a separate write
     * when the revoke failed; a failed restore left the role without a membership. The revoke now
     * comes first, so a failed revoke changes nothing and needs no compensating write.
     */
    @Test
    void removeAdmin_whenTheRevokeFails_touchesNoMembership() {
        UUID user = UUID.randomUUID();
        when(admins.findById(user)).thenReturn(Optional.of(new TenantAdmin(tenant.getId(), user)));
        auth.revokeFailure = new ProviderException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "AUTH_UNAVAILABLE", "down");

        assertThatThrownBy(() -> service.removeAdmin(tenant.getId(), user, actor))
                .isInstanceOfSatisfying(ProviderException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("AUTH_UNAVAILABLE"));

        verify(admins, never()).deleteMembership(any(), any());
        verify(admins, never()).saveAndFlush(any(TenantAdmin.class));
        assertThat(List.copyOf(auth.calls)).isEmpty();
    }

    /**
     * The one remaining partial failure: the role is revoked but the membership delete fails. The
     * user then holds no role (no portal access), and a retry finishes the removal.
     */
    @Test
    void removeAdmin_revokesBeforeDeleting_andARetryFinishesAFailedDelete() {
        UUID user = UUID.randomUUID();
        when(admins.findById(user)).thenReturn(Optional.of(new TenantAdmin(tenant.getId(), user)));
        when(admins.deleteMembership(tenant.getId(), user))
                .thenThrow(new QueryTimeoutException("db down"))
                .thenReturn(1);

        assertThatThrownBy(() -> service.removeAdmin(tenant.getId(), user, actor))
                .isInstanceOf(QueryTimeoutException.class);
        assertThat(auth.calls).containsExactly("revoke " + user);

        service.removeAdmin(tenant.getId(), user, actor);

        assertThat(auth.calls).containsExactly("revoke " + user, "revoke " + user);
        verify(admins, times(2)).deleteMembership(tenant.getId(), user);
        verify(admins, never()).saveAndFlush(any(TenantAdmin.class));
    }
}
