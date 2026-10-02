package com.homefix.notification.template;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.homefix.notification.domain.BuiltInTemplates;
import com.homefix.notification.domain.BuiltInTemplates.ChannelTemplate;
import com.homefix.notification.domain.NotificationChannel;
import com.homefix.notification.domain.TemplateDefinition;
import com.homefix.notification.domain.TemplatePlaceholders;
import com.homefix.notification.domain.TemplateText;
import com.homefix.notification.domain.TemplateTextSource;

/**
 * Admin management of the notification templates (Requirement 19.2) and the
 * {@link TemplateTextSource} the {@code EventTemplateResolver} renders from.
 *
 * <p><strong>What is editable.</strong> The set of templates, their ids and their channels are the
 * built-in catalogue ({@link BuiltInTemplates}); an admin edits only the subject and body of an
 * existing per-channel template. Text may use only the placeholders its event supplies
 * ({@link TemplatePlaceholders#allowedFor}), so an edit can never send a literal
 * {@code {{customerName}}} to customers.
 *
 * <p><strong>Caching.</strong> Every notification renders through {@link #find}, so the table is
 * read whole into a snapshot and re-read at most every {@link #CACHE_TTL}. An edit evicts this
 * instance's snapshot (again after commit) so it takes effect on the next notification; other
 * replicas pick it up when their snapshot expires.
 */
@Service
public class NotificationTemplateService implements TemplateTextSource {

    /** Column lengths of {@code notification_template.subject} / {@code body}. */
    static final int MAX_SUBJECT_LENGTH = 200;
    static final int MAX_BODY_LENGTH = 1000;

    /** How long a snapshot is served before the table is re-read (bounds cross-replica staleness). */
    static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final NotificationTemplateRepository repository;
    private final Clock clock;
    private volatile Snapshot snapshot;

    private record Snapshot(Map<String, TemplateText> texts, Instant loadedAt) {
    }

    // A second, package-private constructor lets tests pin the clock; @Autowired tells Spring
    // which one to use.
    @Autowired
    public NotificationTemplateService(NotificationTemplateRepository repository) {
        this(repository, Clock.systemUTC());
    }

    NotificationTemplateService(NotificationTemplateRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public Optional<TemplateText> find(String templateId) {
        return Optional.ofNullable(currentSnapshot().texts().get(templateId));
    }

    /** Every per-channel template with its current text, in catalogue order. */
    @Transactional(readOnly = true)
    public List<NotificationTemplateView> list() {
        Map<String, NotificationTemplateEntity> rows = repository.findAll().stream()
                .collect(Collectors.toMap(NotificationTemplateEntity::getId, Function.identity()));
        return BuiltInTemplates.allChannelTemplates().stream()
                .map(template -> view(template, rows.get(template.id())))
                .toList();
    }

    /**
     * Replaces a template's body, and its subject when one is given (a {@code null} subject keeps
     * the current one).
     *
     * @throws TemplateNotFoundException if the id is not a built-in per-channel template
     * @throws InvalidTemplateException  if the text is blank, too long, uses a placeholder its
     *                                   event does not supply, or sets a subject on SMS
     */
    @Transactional
    public NotificationTemplateView update(String templateId, String subject, String body, UUID actorId) {
        ChannelTemplate template = BuiltInTemplates.findById(templateId)
                .orElseThrow(() -> new TemplateNotFoundException(templateId));
        validate(template, subject, body);

        // A missing row (deleted by hand) is recreated rather than refused: the template exists.
        NotificationTemplateEntity row = repository.findById(templateId)
                .orElseGet(() -> seedRow(template));
        String newSubject = subject != null ? subject : currentSubject(template, row);
        row.edit(newSubject, body, clock.instant(), actorId);
        repository.save(row);
        evictAfterCommit();
        return view(template, row);
    }

    private static void validate(ChannelTemplate template, String subject, String body) {
        TemplateDefinition definition = template.definition();
        List<String> problems = new ArrayList<>();
        if (body == null || body.isBlank()) {
            problems.add("body: must not be blank");
        } else {
            if (body.length() > MAX_BODY_LENGTH) {
                problems.add("body: must be at most " + MAX_BODY_LENGTH + " characters");
            }
            TemplatePlaceholders.problems(definition.eventType(), body)
                    .forEach(problem -> problems.add("body: " + problem));
        }
        if (subject != null) {
            if (!TemplateDefinition.hasSubject(template.channel())) {
                problems.add("subject: " + template.channel() + " templates have no subject");
            } else if (subject.isBlank()) {
                problems.add("subject: must not be blank");
            } else {
                if (subject.length() > MAX_SUBJECT_LENGTH) {
                    problems.add("subject: must be at most " + MAX_SUBJECT_LENGTH + " characters");
                }
                TemplatePlaceholders.problems(definition.eventType(), subject)
                        .forEach(problem -> problems.add("subject: " + problem));
            }
        }
        if (!problems.isEmpty()) {
            throw new InvalidTemplateException(template.id(), problems);
        }
    }

    private NotificationTemplateEntity seedRow(ChannelTemplate template) {
        TemplateDefinition definition = template.definition();
        NotificationChannel channel = template.channel();
        return new NotificationTemplateEntity(template.id(), definition.key(), definition.nameFor(channel),
                channel, builtInSubject(template), definition.body(), clock.instant(), null);
    }

    private static NotificationTemplateView view(ChannelTemplate template, NotificationTemplateEntity row) {
        TemplateDefinition definition = template.definition();
        NotificationChannel channel = template.channel();
        String subject = row == null ? builtInSubject(template) : currentSubject(template, row);
        String body = row == null ? definition.body() : row.getBody();
        return new NotificationTemplateView(template.id(), definition.key(), definition.nameFor(channel),
                channel, subject, body);
    }

    /** The row's subject, the built-in heading when the row has none, and always null for SMS. */
    private static String currentSubject(ChannelTemplate template, NotificationTemplateEntity row) {
        if (!TemplateDefinition.hasSubject(template.channel())) {
            return null;
        }
        return row.getSubject() != null ? row.getSubject() : template.definition().title();
    }

    private static String builtInSubject(ChannelTemplate template) {
        return TemplateDefinition.hasSubject(template.channel()) ? template.definition().title() : null;
    }

    private Snapshot currentSnapshot() {
        Snapshot current = snapshot;
        Instant now = clock.instant();
        if (current == null || !now.isBefore(current.loadedAt().plus(CACHE_TTL))) {
            Map<String, TemplateText> texts = repository.findAll().stream()
                    .collect(Collectors.toUnmodifiableMap(NotificationTemplateEntity::getId,
                            row -> new TemplateText(row.getSubject(), row.getBody())));
            current = new Snapshot(texts, now);
            snapshot = current;
        }
        return current;
    }

    /**
     * Drops the snapshot now and again once the edit commits, so a notification rendered between
     * the two cannot re-cache the pre-edit text for a full TTL.
     */
    private void evictAfterCommit() {
        snapshot = null;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    snapshot = null;
                }
            });
        }
    }
}
