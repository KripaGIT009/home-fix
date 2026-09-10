package com.homefix.booking.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

import com.homefix.booking.domain.BookingRepository;

/**
 * Generates a unique, human-facing booking reference number (Requirement 7.1) of the form
 * {@code HFX-YYYYMMDD-XXXXXX} where the suffix is a random alphanumeric token. Uniqueness is
 * guaranteed by checking the repository and retrying on the (astronomically unlikely)
 * collision.
 */
@Component
public class BookingReferenceGenerator {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int SUFFIX_LEN = 6;
    private static final int MAX_ATTEMPTS = 10;

    private final BookingRepository bookingRepository;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public BookingReferenceGenerator(BookingRepository bookingRepository, Clock clock) {
        this.bookingRepository = bookingRepository;
        this.clock = clock;
    }

    public String generate() {
        String datePart = DATE.format(clock.instant().atZone(ZoneOffset.UTC));
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = "HFX-" + datePart + "-" + randomSuffix();
            if (!bookingRepository.existsByReference(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Unable to generate a unique booking reference");
    }

    private String randomSuffix() {
        StringBuilder sb = new StringBuilder(SUFFIX_LEN);
        for (int i = 0; i < SUFFIX_LEN; i++) {
            sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
