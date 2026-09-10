package com.homefix.shared.security;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RbacPropertiesTest {

    @Test
    void defaultsToEmptyMap() {
        assertThat(new RbacProperties().getEndpointRoles()).isEmpty();
    }

    @Test
    void setterReplacesMap_andPreservesInsertionOrder() {
        RbacProperties props = new RbacProperties();
        Map<String, List<String>> roles = new LinkedHashMap<>();
        roles.put("GET /admin/**", List.of("ADMIN", "SUPER_ADMIN"));
        roles.put("POST /bookings", List.of("CUSTOMER"));

        props.setEndpointRoles(roles);

        assertThat(props.getEndpointRoles()).hasSize(2);
        assertThat(props.getEndpointRoles().keySet())
                .containsExactly("GET /admin/**", "POST /bookings");
    }
}
