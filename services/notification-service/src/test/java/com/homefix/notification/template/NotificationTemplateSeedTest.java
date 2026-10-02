package com.homefix.notification.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.homefix.notification.domain.BuiltInTemplates;
import com.homefix.notification.domain.BuiltInTemplates.ChannelTemplate;
import com.homefix.notification.domain.EventTemplateResolver;
import com.homefix.notification.domain.NotificationAudience;
import com.homefix.notification.domain.NotificationChannel;
import com.homefix.notification.domain.NotificationContact;
import com.homefix.notification.domain.NotificationEvent;
import com.homefix.notification.domain.NotificationEventType;
import com.homefix.notification.domain.RecipientPolicy;
import com.homefix.notification.domain.RenderedMessage;
import com.homefix.notification.domain.TemplateDefinition;
import com.homefix.notification.domain.TemplateText;

/**
 * Keeps the V2 seed of {@code notification_template} and the built-in catalogue in step: the
 * migration must contain exactly one row per built-in template and channel, with exactly the
 * built-in text, so that seeding the table changes no notification. (The migration itself is
 * PostgreSQL DDL and is not run by the H2 tests; this reads it as text.)
 */
class NotificationTemplateSeedTest {

    private static final String MIGRATION = "db/migration/V2__notification_templates.sql";
    private static final Pattern ROW = Pattern.compile("^\\s*\\((.*), now\\(\\)\\)[,;]\\s*$", Pattern.MULTILINE);
    private static final Pattern VALUE = Pattern.compile("'((?:[^']|'')*)'|NULL");

    private static Map<String, List<String>> seededRows;

    @BeforeAll
    static void readMigration() throws IOException {
        String sql;
        try (InputStream in = NotificationTemplateSeedTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(in).as(MIGRATION).isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        seededRows = new LinkedHashMap<>();
        Matcher row = ROW.matcher(sql);
        while (row.find()) {
            List<String> values = new ArrayList<>();
            Matcher value = VALUE.matcher(row.group(1));
            while (value.find()) {
                values.add(value.group(1) == null ? null : value.group(1).replace("''", "'"));
            }
            assertThat(values).as(row.group()).hasSize(6);
            assertThat(seededRows.put(values.get(0), values)).as("duplicate " + values.get(0)).isNull();
        }
    }

    @Test
    void seedHasExactlyOneRowPerBuiltInTemplateAndChannelWithTheBuiltInText() {
        List<ChannelTemplate> catalogue = BuiltInTemplates.allChannelTemplates();
        assertThat(seededRows.keySet()).containsExactlyElementsOf(catalogue.stream().map(ChannelTemplate::id).toList());

        for (ChannelTemplate template : catalogue) {
            TemplateDefinition definition = template.definition();
            NotificationChannel channel = template.channel();
            assertThat(seededRows.get(template.id())).as(template.id()).containsExactly(
                    template.id(),
                    definition.key(),
                    definition.nameFor(channel),
                    channel.name(),
                    TemplateDefinition.hasSubject(channel) ? definition.title() : null,
                    definition.body());
        }
    }

    @Test
    void renderingFromTheSeededTableIsIdenticalToTheBuiltInText() {
        EventTemplateResolver builtIn = new EventTemplateResolver();
        EventTemplateResolver seeded = new EventTemplateResolver(id -> Optional.ofNullable(seededRows.get(id))
                .map(row -> new TemplateText(row.get(4), row.get(5))));
        RecipientPolicy policy = new RecipientPolicy();

        for (NotificationEventType type : NotificationEventType.values()) {
            for (NotificationAudience audience : policy.audiencesFor(type)) {
                for (Map<String, String> attributes : List.of(Map.<String, String>of(),
                        Map.of("bookingReference", "HFX-42", "bookingStatus", "SEARCHING_FAILED",
                                "complaintStatus", "IN_PROGRESS"))) {
                    NotificationEvent event = new NotificationEvent(type, UUID.randomUUID(), UUID.randomUUID(),
                            audience, NotificationContact.empty(), attributes);
                    RenderedMessage expected = builtIn.resolve(event);
                    RenderedMessage actual = seeded.resolve(event);

                    assertThat(actual.channels()).isEqualTo(expected.channels());
                    for (NotificationChannel channel : expected.channels()) {
                        assertThat(actual.titleFor(channel)).as("%s/%s/%s", type, audience, channel)
                                .isEqualTo(expected.titleFor(channel));
                        assertThat(actual.bodyFor(channel)).as("%s/%s/%s", type, audience, channel)
                                .isEqualTo(expected.bodyFor(channel));
                    }
                }
            }
        }
    }
}
