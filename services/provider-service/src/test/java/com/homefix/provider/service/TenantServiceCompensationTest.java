package com.homefix.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
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
 * revoked again, and a membership whose role could not be revoked is restored.
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

    @Test
    void removeAdmin_deletesThenRevokes_andRestoresWhenTheRevokeFails() {
        UUID user = UUID.randomUUID();
        when(admins.deleteMembership(tenant.getId(), user)).thenReturn(1);
        auth.revokeFailure = new ProviderException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "AUTH_UNAVAILABLE", "down");

        assertThatThrownBy(() -> service.removeAdmin(tenant.getId(), user, actor))
                .isInstanceOfSatisfying(ProviderException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("AUTH_UNAVAILABLE"));

        InOrder order = inOrder(admins);
        order.verify(admins).deleteMembership(tenant.getId(), user);
        order.verify(admins).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                (TenantAdmin a) -> a.getUserId().equals(user) && a.getTenantId().equals(tenant.getId())));
        assertThat(List.copyOf(auth.calls)).isEmpty();
    }
}
