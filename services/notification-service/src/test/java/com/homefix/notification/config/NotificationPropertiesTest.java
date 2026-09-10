package com.homefix.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * Round-trips the tunable {@link NotificationProperties} — the retry limits, vendor selectors, the
 * eleven lifecycle topic names (Requirement 17.5), and the downstream client URL — verifying the
 * defaults match the acceptance criteria and every setter is honoured.
 */
class NotificationPropertiesTest {

    @Test
    void defaultsMatchAcceptanceCriteria() {
        NotificationProperties p = new NotificationProperties();
        assertThat(p.getMaxAttempts()).isEqualTo(3);
        assertThat(p.getRetryInitialBackoff()).isEqualTo(Duration.ofSeconds(1));
        assertThat(p.getSmsProvider()).isEqualTo("log");
        assertThat(p.getEmailProvider()).isEqualTo("log");
        assertThat(p.getPushProvider()).isEqualTo("log");
        assertThat(p.getClients().getPreferenceServiceBaseUrl()).isEqualTo("http://customer-service");

        NotificationProperties.Topics t = p.getTopics();
        assertThat(t.getBookingCreated()).isEqualTo("BookingCreated");
        assertThat(t.getProviderAssigned()).isEqualTo("ProviderAssigned");
        assertThat(t.getProviderAccepted()).isEqualTo("ProviderAccepted");
        assertThat(t.getProviderRejected()).isEqualTo("ProviderRejected");
        assertThat(t.getProviderArriving()).isEqualTo("ProviderArriving");
        assertThat(t.getProviderArrived()).isEqualTo("ProviderArrived");
        assertThat(t.getJobStarted()).isEqualTo("JobStarted");
        assertThat(t.getJobCompleted()).isEqualTo("JobCompleted");
        assertThat(t.getPaymentCompleted()).isEqualTo("PaymentCompleted");
        assertThat(t.getBookingCancelled()).isEqualTo("BookingCancelled");
        assertThat(t.getReviewSubmitted()).isEqualTo("ReviewSubmitted");
    }

    @Test
    void settersRoundTrip() {
        NotificationProperties p = new NotificationProperties();
        p.setMaxAttempts(5);
        p.setRetryInitialBackoff(Duration.ofMillis(250));
        p.setSmsProvider("twilio");
        p.setEmailProvider("ses");
        p.setPushProvider("fcm");

        NotificationProperties.Topics t = new NotificationProperties.Topics();
        t.setBookingCreated("bc");
        t.setProviderAssigned("pa");
        t.setProviderAccepted("pacc");
        t.setProviderRejected("prej");
        t.setProviderArriving("parv");
        t.setProviderArrived("parr");
        t.setJobStarted("js");
        t.setJobCompleted("jc");
        t.setPaymentCompleted("pc");
        t.setBookingCancelled("bcx");
        t.setReviewSubmitted("rs");
        p.setTopics(t);

        NotificationProperties.Clients c = new NotificationProperties.Clients();
        c.setPreferenceServiceBaseUrl("http://prefs");
        p.setClients(c);

        assertThat(p.getMaxAttempts()).isEqualTo(5);
        assertThat(p.getRetryInitialBackoff()).isEqualTo(Duration.ofMillis(250));
        assertThat(p.getSmsProvider()).isEqualTo("twilio");
        assertThat(p.getEmailProvider()).isEqualTo("ses");
        assertThat(p.getPushProvider()).isEqualTo("fcm");
        assertThat(p.getTopics().getBookingCreated()).isEqualTo("bc");
        assertThat(p.getTopics().getProviderAssigned()).isEqualTo("pa");
        assertThat(p.getTopics().getProviderAccepted()).isEqualTo("pacc");
        assertThat(p.getTopics().getProviderRejected()).isEqualTo("prej");
        assertThat(p.getTopics().getProviderArriving()).isEqualTo("parv");
        assertThat(p.getTopics().getProviderArrived()).isEqualTo("parr");
        assertThat(p.getTopics().getJobStarted()).isEqualTo("js");
        assertThat(p.getTopics().getJobCompleted()).isEqualTo("jc");
        assertThat(p.getTopics().getPaymentCompleted()).isEqualTo("pc");
        assertThat(p.getTopics().getBookingCancelled()).isEqualTo("bcx");
        assertThat(p.getTopics().getReviewSubmitted()).isEqualTo("rs");
        assertThat(p.getClients().getPreferenceServiceBaseUrl()).isEqualTo("http://prefs");
    }
}
