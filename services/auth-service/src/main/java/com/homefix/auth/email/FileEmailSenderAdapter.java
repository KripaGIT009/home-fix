package com.homefix.auth.email;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * LOCAL DEVELOPMENT ONLY {@link EmailSenderPort} adapter: appends each email, whole, to the
 * Dev_Mail_Log file, the counterpart of the dev SMS file. Compose mounts it on the host as
 * {@code docker/dev-mail/dev-mail.log}, so sign-up codes, reset codes and invitation links can be
 * read without a mail server.
 *
 * <p>Activated with {@code homefix.email.provider=file}. Never enable it outside local development:
 * the file holds live codes and invitation links in plain text.
 */
@Component
@ConditionalOnProperty(prefix = "homefix.email", name = "provider", havingValue = "file")
public class FileEmailSenderAdapter implements EmailSenderPort {

    private static final Logger log = LoggerFactory.getLogger(FileEmailSenderAdapter.class);
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final Path outputFile;

    public FileEmailSenderAdapter(
            @Value("${homefix.email.file-path:/var/log/homefix/dev-mail.log}") String filePath) {
        this.outputFile = Path.of(filePath);
        log.warn("DEV MAIL is writing emails, verification codes and invitation links included, in plain "
                + "text to {}. This must never be enabled outside local development.", outputFile);
    }

    @Override
    public void send(String to, EmailMessage message) {
        String entry = """
                ==== %s UTC
                To: %s
                Subject: %s

                %s

                """.formatted(TIMESTAMP.format(Instant.now()), to, message.subject(), message.body().strip());
        try {
            Path parent = outputFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(outputFile, entry, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            throw new EmailDeliveryException("Could not write the dev mail to " + outputFile, ex);
        }
    }
}
