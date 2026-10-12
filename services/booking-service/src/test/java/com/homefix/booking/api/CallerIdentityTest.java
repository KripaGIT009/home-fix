package com.homefix.booking.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.homefix.booking.service.Actor;

/**
 * {@link CallerIdentity#actorOf}: the role a {@code /bookings/{key}} command acts under is decided on
 * every authority in the token, not on whichever the issuer happened to list first.
 */
class CallerIdentityTest {

    private static final UUID USER = UUID.fromString("55555555-5555-5555-5555-555555555555");

    private static Authentication withRoles(String... roles) {
        return new UsernamePasswordAuthenticationToken(USER.toString(), "n/a",
                Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList());
    }

    @Test
    void aStaffRoleWinsWhereverItIsListed() {
        Actor actor = CallerIdentity.actorOf(withRoles("ROLE_CUSTOMER", "ROLE_SUPPORT_AGENT"), "CUSTOMER");

        assertThat(actor.role()).isEqualTo("SUPPORT_AGENT");
        assertThat(actor.id()).isEqualTo(USER);
    }

    @Test
    void withoutStaffTheEndpointsOwnRoleIsPreferredOverTheFirst() {
        Actor actor = CallerIdentity.actorOf(withRoles("ROLE_CUSTOMER", "ROLE_SERVICE_PROVIDER"),
                "SERVICE_PROVIDER");

        assertThat(actor.role()).isEqualTo("SERVICE_PROVIDER");
    }

    @Test
    void otherwiseTheFirstAuthorityAndFinallyTheFallback() {
        assertThat(CallerIdentity.actorOf(withRoles("ROLE_TENANT_ADMIN"), "CUSTOMER").role())
                .isEqualTo("TENANT_ADMIN");
        assertThat(CallerIdentity.actorOf(new UsernamePasswordAuthenticationToken(
                USER.toString(), "n/a", List.of()), "CUSTOMER").role()).isEqualTo("CUSTOMER");
    }
}
