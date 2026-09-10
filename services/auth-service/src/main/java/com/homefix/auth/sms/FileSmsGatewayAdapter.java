package com.homefix.auth.sms;

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
 * LOCAL DEVELOPMENT ONLY {@link SmsGatewayPort} adapter that appends each message to a file.
 *
 * <p>The default {@code log} adapter puts the OTP in the container's log, which is only
 * reachable through the container runtime's API. When that API is slow or unavailable there is
 * no way to complete a login, because the code exists nowhere else. Writing to a file the host
 * can mount makes the dev OTP readable with ordinary file access.
 *
 * <p>This changes nothing about how OTPs are generated or stored — the raw code is still never
 * persisted by the OTP store, only hashed. It is the same dev-only "pretend to send" behaviour
 * as the logging adapter, pointed at a durable sink.
 *
 * <p>Activated with {@code homefix.sms.provider=file}. Never enable it outside local development:
 * the file contains live verification codes in plain text.
 */
@Component
@ConditionalOnProperty(prefix = "homefix.sms", name = "provider", havingValue = "file")
public class FileSmsGatewayAdapter implements SmsGatewayPort {

    private static final Logger log = LoggerFactory.getLogger(FileSmsGatewayAdapter.class);
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final Path outputFile;

    public FileSmsGatewayAdapter(
            @Value("${homefix.sms.file-path:/var/log/homefix/dev-sms.log}") String filePath) {
        this.outputFile = Path.of(filePath);
        log.warn("DEV SMS gateway is writing verification codes in plain text to {}. "
                + "This must never be enabled outside local development.", outputFile);
    }

    @Override
    public void send(String mobileNumber, String message) throws SmsDeliveryException {
        String line = "%s  %s  %s%n".formatted(TIMESTAMP.format(Instant.now()), mobileNumber, message);
        try {
            Path parent = outputFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(outputFile, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            throw new SmsDeliveryException(
                    "Could not write the dev SMS to " + outputFile + ": " + ex.getMessage(), ex);
        }
    }
}
