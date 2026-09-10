package com.homefix.invoice.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Tunable invoice-domain limits and thresholds (Requirement 13).
 *
 * <p>Defaults match the acceptance criteria; all values are overridable via configuration so
 * operations can tune limits without a code change.
 */
@ConfigurationProperties(prefix = "homefix.invoice")
public class InvoiceProperties {

    /** Number of PDF-generation attempts before the operations team is alerted (Requirement 13.7). */
    private int maxPdfRetries = 3;

    /** Base backoff between PDF-generation retries (doubled on each attempt). */
    private Duration pdfRetryBackoff = Duration.ofMillis(500);

    /** Validity window of the signed URL delivered to the customer (Requirement 13.3). */
    private Duration signedUrlTtl = Duration.ofHours(72);

    /** Minimum retention window for customer-accessible invoices (Requirement 13.5). */
    private Duration retention = Duration.ofDays(730);

    /** Tax identifier printed on every generated invoice (Requirement 13.1). */
    private String taxIdentifier = "GSTIN-HOMEFIX-0000";

    @NestedConfigurationProperty
    private Topics topics = new Topics();

    @NestedConfigurationProperty
    private S3 s3 = new S3();

    @NestedConfigurationProperty
    private Clients clients = new Clients();

    public int getMaxPdfRetries() {
        return maxPdfRetries;
    }

    public void setMaxPdfRetries(int maxPdfRetries) {
        this.maxPdfRetries = maxPdfRetries;
    }

    public Duration getPdfRetryBackoff() {
        return pdfRetryBackoff;
    }

    public void setPdfRetryBackoff(Duration pdfRetryBackoff) {
        this.pdfRetryBackoff = pdfRetryBackoff;
    }

    public Duration getSignedUrlTtl() {
        return signedUrlTtl;
    }

    public void setSignedUrlTtl(Duration signedUrlTtl) {
        this.signedUrlTtl = signedUrlTtl;
    }

    public Duration getRetention() {
        return retention;
    }

    public void setRetention(Duration retention) {
        this.retention = retention;
    }

    public String getTaxIdentifier() {
        return taxIdentifier;
    }

    public void setTaxIdentifier(String taxIdentifier) {
        this.taxIdentifier = taxIdentifier;
    }

    public Topics getTopics() {
        return topics;
    }

    public void setTopics(Topics topics) {
        this.topics = topics;
    }

    public S3 getS3() {
        return s3;
    }

    public void setS3(S3 s3) {
        this.s3 = s3;
    }

    public Clients getClients() {
        return clients;
    }

    public void setClients(Clients clients) {
        this.clients = clients;
    }

    /** Kafka topic names this service consumes. */
    public static class Topics {
        private String paymentCompleted = "PaymentCompleted";

        public String getPaymentCompleted() {
            return paymentCompleted;
        }

        public void setPaymentCompleted(String paymentCompleted) {
            this.paymentCompleted = paymentCompleted;
        }
    }

    /** S3 object-storage settings for invoice PDFs (Requirement 13.2). */
    public static class S3 {
        private String bucket = "homefix-invoices";
        private String region = "ap-south-1";
        /** Storage backend: 'aws' (production) or 'memory' (dev/test). */
        private String backend = "memory";

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public String getBackend() {
            return backend;
        }

        public void setBackend(String backend) {
            this.backend = backend;
        }
    }

    /** Downstream service base URLs. */
    public static class Clients {
        private String notificationServiceBaseUrl = "http://notification-service";

        public String getNotificationServiceBaseUrl() {
            return notificationServiceBaseUrl;
        }

        public void setNotificationServiceBaseUrl(String notificationServiceBaseUrl) {
            this.notificationServiceBaseUrl = notificationServiceBaseUrl;
        }
    }
}
