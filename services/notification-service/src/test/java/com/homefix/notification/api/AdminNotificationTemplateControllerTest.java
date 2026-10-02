package com.homefix.notification.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.homefix.notification.domain.BuiltInTemplates;
import com.homefix.notification.template.NotificationTemplateEntity;
import com.homefix.notification.template.NotificationTemplateRepository;
import com.homefix.notification.template.NotificationTemplateService;

/**
 * Web-layer test for {@code /admin/notification-templates} (Requirement 19.2): the response is
 * exactly the Admin Portal's {@code NotificationTemplate} shape, and invalid edits come back in
 * the shared error envelope. Role gating is {@code NotificationRbacConfig}'s and is tested there.
 */
class AdminNotificationTemplateControllerTest {

    private static final String ADMIN_ID = "22222222-2222-2222-2222-222222222222";

    private final Map<String, NotificationTemplateEntity> table = new LinkedHashMap<>();
    private MockMvc mockMvc;

    private static Authentication admin() {
        return new UsernamePasswordAuthenticationToken(ADMIN_ID, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    @BeforeEach
    void setUp() {
        NotificationTemplateRepository repository = mock(NotificationTemplateRepository.class);
        when(repository.findAll()).thenAnswer(invocation -> new ArrayList<>(table.values()));
        when(repository.findById(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(table.get(invocation.<String>getArgument(0))));
        when(repository.save(any(NotificationTemplateEntity.class))).thenAnswer(invocation -> {
            NotificationTemplateEntity row = invocation.getArgument(0);
            table.put(row.getId(), row);
            return row;
        });
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AdminNotificationTemplateController(new NotificationTemplateService(repository)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void listReturnsEveryTemplateInThePortalShape() throws Exception {
        mockMvc.perform(get("/admin/notification-templates").principal(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(BuiltInTemplates.allChannelTemplates().size()))
                .andExpect(jsonPath("$[0].id").value("BOOKING_CREATED.CUSTOMER.PUSH"))
                .andExpect(jsonPath("$[0].key").value("BOOKING_CREATED.CUSTOMER"))
                .andExpect(jsonPath("$[0].name").value("Booking created (customer) — Push"))
                .andExpect(jsonPath("$[0].channel").value("PUSH"))
                .andExpect(jsonPath("$[0].subject").value("Booking confirmed"))
                .andExpect(jsonPath("$[0].body")
                        .value("We received {{bookingReference}} and are finding a professional for you."))
                // SMS has no subject: the field is omitted, as the portal's optional type expects.
                .andExpect(jsonPath("$[1].channel").value("SMS"))
                .andExpect(jsonPath("$[1].subject").doesNotExist());
    }

    @Test
    void putUpdatesTheTemplateAndReturnsIt() throws Exception {
        mockMvc.perform(put("/admin/notification-templates/BOOKING_CREATED.CUSTOMER.EMAIL")
                        .principal(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"Booking {{bookingReference}} received\","
                                + "\"body\":\"Thanks! We are finding a professional for {{bookingReference}}.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("BOOKING_CREATED.CUSTOMER.EMAIL"))
                .andExpect(jsonPath("$.channel").value("EMAIL"))
                .andExpect(jsonPath("$.subject").value("Booking {{bookingReference}} received"))
                .andExpect(jsonPath("$.body").value("Thanks! We are finding a professional for {{bookingReference}}."));

        assertThat(table.get("BOOKING_CREATED.CUSTOMER.EMAIL").getUpdatedBy()).isEqualTo(UUID.fromString(ADMIN_ID));
    }

    @Test
    void putWithAnUnknownPlaceholderIs400WithTheProblem() throws Exception {
        mockMvc.perform(put("/admin/notification-templates/JOB_STARTED.CUSTOMER.PUSH")
                        .principal(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Hi {{customerName}}, work on {{bookingReference}} started\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_TEMPLATE"))
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.containsString("{{customerName}}")));

        assertThat(table).isEmpty();
    }

    @Test
    void putOnAnUnknownTemplateIs404() throws Exception {
        mockMvc.perform(put("/admin/notification-templates/NOPE.CUSTOMER.PUSH")
                        .principal(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TEMPLATE_NOT_FOUND"));
    }

    @Test
    void putWithoutABodyIs400() throws Exception {
        mockMvc.perform(put("/admin/notification-templates/JOB_STARTED.CUSTOMER.PUSH")
                        .principal(admin())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }
}
