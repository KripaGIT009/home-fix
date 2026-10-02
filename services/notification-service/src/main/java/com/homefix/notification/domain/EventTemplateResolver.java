package com.homefix.notification.domain;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Maps each event and recipient audience to its default delivery channels and renders
 * channel-safe message content (Requirements 16.2, 16.3, 17.5).
 *
 * <p>The template is picked by {@link BuiltInTemplates#select}; its text on each channel comes
 * from the {@link TemplateTextSource} (the admin-editable {@code notification_template} table,
 * Requirement 19.2) and falls back to the built-in text when no row exists or the store cannot be
 * read, so a notification is never lost to a template lookup. The channel set is always the
 * built-in one. Placeholders are filled by {@link TemplatePlaceholders} from the non-PII
 * attributes only.
 *
 * <p>Apart from the text lookup this is a pure function of the event type, the audience and the
 * attributes, so it is directly unit- and property-testable. Preference filtering happens later;
 * this resolver returns the <em>candidate</em> channels for the recipient.
 */
@Component
public class EventTemplateResolver {

    private static final Logger log = LoggerFactory.getLogger(EventTemplateResolver.class);

    private final TemplateTextSource textSource;

    /** A resolver that always renders the built-in text. */
    public EventTemplateResolver() {
        this(TemplateTextSource.builtInOnly());
    }

    // Two constructors: without @Autowired Spring would pick the no-arg one and ignore admin edits.
    @Autowired
    public EventTemplateResolver(TemplateTextSource textSource) {
        this.textSource = textSource;
    }

    /**
     * Resolves the candidate channels and rendered content for an event and its recipient.
     *
     * @throws IllegalStateException if the event type has no template for the recipient's
     *                               audience (guards against a policy change without a template)
     */
    public RenderedMessage resolve(NotificationEvent event) {
        TemplateDefinition template = BuiltInTemplates.select(event);
        Map<String, String> values = TemplatePlaceholders.values(event.eventType(), event.attributes());
        String builtInTitle = TemplatePlaceholders.render(template.title(), values);
        String builtInBody = TemplatePlaceholders.render(template.body(), values);

        Map<NotificationChannel, RenderedMessage.ChannelContent> content = new EnumMap<>(NotificationChannel.class);
        for (NotificationChannel channel : template.channels()) {
            Optional<TemplateText> stored = storedText(template.idFor(channel));
            String title = stored.map(TemplateText::subject)
                    .map(subject -> TemplatePlaceholders.render(subject, values))
                    .orElse(builtInTitle);
            String body = stored.map(text -> TemplatePlaceholders.render(text.body(), values))
                    .orElse(builtInBody);
            content.put(channel, new RenderedMessage.ChannelContent(title, body));
        }
        return new RenderedMessage(template.channels(), builtInTitle, builtInBody, content);
    }

    private Optional<TemplateText> storedText(String templateId) {
        try {
            return textSource.find(templateId);
        } catch (RuntimeException lookupFailed) {
            // The built-in text is always a correct message; a template-store outage must not
            // dead-letter notifications.
            log.warn("Template lookup for {} failed; using the built-in text: {}",
                    templateId, lookupFailed.getMessage());
            return Optional.empty();
        }
    }
}
