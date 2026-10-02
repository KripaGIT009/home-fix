package com.homefix.notification.template;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.homefix.notification.domain.NotificationChannel;

/**
 * The stored, admin-editable text of one built-in template on one channel (Requirement 19.2).
 *
 * <p>Rows are seeded by the V2 migration with exactly the built-in texts, so nothing changes
 * until an admin edits one. The id, key, name and channel come from the built-in catalogue and
 * are never edited; only {@code subject} and {@code body} are.
 */
@Entity
@Table(name = "notification_template")
public class NotificationTemplateEntity {

    /** Per-channel template id, {@code EVENT.AUDIENCE[.VARIANT].CHANNEL}. */
    @Id
    @Column(name = "id", nullable = false, length = 120)
    private String id;

    /** The template the row belongs to, {@code EVENT.AUDIENCE[.VARIANT]}. */
    @Column(name = "template_key", nullable = false, length = 100)
    private String templateKey;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private NotificationChannel channel;

    /** Heading / email subject; always null for SMS. */
    @Column(name = "subject", length = NotificationTemplateService.MAX_SUBJECT_LENGTH)
    private String subject;

    @Column(name = "body", nullable = false, length = NotificationTemplateService.MAX_BODY_LENGTH)
    private String body;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** The admin who last edited the text; null for the seeded built-in text. */
    @Column(name = "updated_by")
    private UUID updatedBy;

    protected NotificationTemplateEntity() {
        // JPA
    }

    public NotificationTemplateEntity(String id, String templateKey, String name, NotificationChannel channel,
                                      String subject, String body, Instant updatedAt, UUID updatedBy) {
        this.id = id;
        this.templateKey = templateKey;
        this.name = name;
        this.channel = channel;
        this.subject = subject;
        this.body = body;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    /** Replaces the editable text and stamps who changed it. */
    public void edit(String subject, String body, Instant updatedAt, UUID updatedBy) {
        this.subject = subject;
        this.body = body;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    public String getId() {
        return id;
    }

    public String getTemplateKey() {
        return templateKey;
    }

    public String getName() {
        return name;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }
}
