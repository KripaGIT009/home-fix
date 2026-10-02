package com.homefix.shared.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives {@link RbacEnforcementFilter} in front of a real {@code DispatcherServlet}, so each
 * bypass is shown against the handler mapping that actually resolves it rather than against the
 * filter's own idea of the path.
 *
 * <p>The control case ({@link #unfilteredHandlerResolvesEveryVariant}) proves that Spring MVC does
 * dispatch {@code /%61dmin/users} and {@code HEAD /admin/users} to the protected handler; the
 * remaining cases prove the filter now governs those same requests with the {@code GET /admin/**}
 * rule. Before the fix each of them reached the handler as a {@code CUSTOMER}.
 */
class RbacEnforcementFilterMvcTest {

    private MockMvc filtered;
    private MockMvc unfiltered;

    @RestController
    static class AdminController {
        @GetMapping("/admin/users")
        String users() {
            return "admin-users";
        }
    }

    @BeforeEach
    void setUp() {
        RbacProperties props = new RbacProperties();
        props.getEndpointRoles().put("GET /admin/**", List.of("ADMIN"));
        filtered = MockMvcBuilders.standaloneSetup(new AdminController())
                .addFilters(new RbacEnforcementFilter(props))
                .build();
        unfiltered = MockMvcBuilders.standaloneSetup(new AdminController()).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "customer-1", null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void unfilteredHandlerResolvesEveryVariant() throws Exception {
        unfiltered.perform(get(URI.create("/%61dmin/users")))
                .andExpect(status().isOk())
                .andExpect(content().string("admin-users"));
        unfiltered.perform(head("/admin/users")).andExpect(status().isOk());
    }

    @Test
    void encodedLetterNoLongerReachesTheHandler() throws Exception {
        filtered.perform(get(URI.create("/%61dmin/users"))).andExpect(status().isForbidden());
    }

    @Test
    void headNoLongerReachesTheGetHandler() throws Exception {
        filtered.perform(head("/admin/users")).andExpect(status().isForbidden());
    }

    @Test
    void pathParameterIsRefused() throws Exception {
        filtered.perform(get(URI.create("/admin;x/users"))).andExpect(status().isBadRequest());
    }

    @Test
    void contextPathIsStripped() throws Exception {
        filtered.perform(get("/api/admin/users").contextPath("/api")).andExpect(status().isForbidden());
    }

    @Test
    void adminStillReachesTheHandler() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin-1", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        filtered.perform(get(URI.create("/%61dmin/users")))
                .andExpect(status().isOk())
                .andExpect(content().string("admin-users"));
    }
}
