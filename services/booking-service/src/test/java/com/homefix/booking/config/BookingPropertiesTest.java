package com.homefix.booking.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BookingProperties} defaults and setters (Requirements 7.2, 7.7, 7.8,
 * 8.1, 9.9, 9.18). The defaults must match the acceptance criteria and remain overridable.
 */
class BookingPropertiesTest {

    @Test
    void defaultsMatchAcceptanceCriteria() {
        BookingProperties props = new BookingProperties();

        assertThat(props.getMinLeadTime()).isEqualTo(Duration.ofHours(2));
        assertThat(props.getMaxHorizon()).isEqualTo(Duration.ofDays(90));
        assertThat(props.getEmergencyCreateWithin()).isEqualTo(Duration.ofSeconds(2));
        assertThat(props.getEmergencySearchingWithin()).isEqualTo(Duration.ofSeconds(3));
        assertThat(props.getAdditionalQuoteTimeout()).isEqualTo(Duration.ofMinutes(60));
        assertThat(props.getDefaultCancellationFee()).isEqualByComparingTo("0.00");

        BookingProperties.Media media = props.getMedia();
        assertThat(media.getMaxFiles()).isEqualTo(10);
        assertThat(media.getMaxFileSize()).isEqualTo(52_428_800L);
        assertThat(media.getAllowedContentTypes())
                .contains("image/jpeg", "image/png", "video/mp4", "video/quicktime");
    }

    @Test
    void settersOverrideDefaults() {
        BookingProperties props = new BookingProperties();

        props.setMinLeadTime(Duration.ofHours(1));
        props.setMaxHorizon(Duration.ofDays(30));
        props.setEmergencyCreateWithin(Duration.ofSeconds(5));
        props.setEmergencySearchingWithin(Duration.ofSeconds(6));
        props.setAdditionalQuoteTimeout(Duration.ofMinutes(30));
        props.setDefaultCancellationFee(new BigDecimal("15.00"));

        assertThat(props.getMinLeadTime()).isEqualTo(Duration.ofHours(1));
        assertThat(props.getMaxHorizon()).isEqualTo(Duration.ofDays(30));
        assertThat(props.getEmergencyCreateWithin()).isEqualTo(Duration.ofSeconds(5));
        assertThat(props.getEmergencySearchingWithin()).isEqualTo(Duration.ofSeconds(6));
        assertThat(props.getAdditionalQuoteTimeout()).isEqualTo(Duration.ofMinutes(30));
        assertThat(props.getDefaultCancellationFee()).isEqualByComparingTo("15.00");
    }

    @Test
    void mediaSettersOverrideDefaults() {
        BookingProperties.Media media = new BookingProperties().getMedia();

        media.setMaxFiles(5);
        media.setMaxFileSize(1024L);
        media.setAllowedContentTypes(List.of("image/png"));

        assertThat(media.getMaxFiles()).isEqualTo(5);
        assertThat(media.getMaxFileSize()).isEqualTo(1024L);
        assertThat(media.getAllowedContentTypes()).containsExactly("image/png");
    }

    @Test
    void clockConfigProvidesSystemClock() {
        assertThat(new ClockConfig().clock()).isNotNull();
    }

    @Test
    void domainConfigProvidesStateMachine() {
        assertThat(new DomainConfig().bookingStateMachine()).isNotNull();
    }
}
