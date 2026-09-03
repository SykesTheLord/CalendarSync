package com.sykessec.calendarsync.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "calendarsync")
@Validated
public class AppProperties {

    /**
     * Externally-visible base URL, embedded in generated /feed/{token}.ics
     * links. Must never default to localhost in the prod profile - see
     * application-prod.yml, which has no default and fails fast if unset.
     */
    private String baseUrl = "http://localhost:8080";

    private final Db db = new Db();
    private final Retention retention = new Retention();
    @Valid
    private final Logging logging = new Logging();
    private final Google google = new Google();
    private final Microsoft microsoft = new Microsoft();

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Db getDb() {
        return db;
    }

    public Retention getRetention() {
        return retention;
    }

    public Logging getLogging() {
        return logging;
    }

    public Google getGoogle() {
        return google;
    }

    public Microsoft getMicrosoft() {
        return microsoft;
    }

    public static class Db {
        /** Toggles which SQLite JDBC driver DataSourceConfig builds a connection for. */
        private boolean encrypted = false;

        private String path = "./data/calendarsync-dev.db";

        public boolean isEncrypted() {
            return encrypted;
        }

        public void setEncrypted(boolean encrypted) {
            this.encrypted = encrypted;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }
    }

    public static class Retention {
        /** Stage 4: only registers TrashRetentionPurgeJob when true. */
        private boolean enabled = false;

        private int days = 90;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getDays() {
            return days;
        }

        public void setDays(int days) {
            this.days = days;
        }
    }

    /**
     * Nothing reads these at runtime - logback-spring.xml resolves the same keys
     * itself via {@code <springProperty/>}, long before this class is bound. They
     * exist so that the values are validated and documented like every other
     * setting: a bad destination or port shows up as a startup binding failure
     * naming the property, instead of only as a "Could not find resource" line
     * from logback's status manager on a root logger that has silently ended up
     * with no appenders. Keep the defaults here identical to the
     * {@code defaultValue} attributes in logback-spring.xml.
     */
    public static class Logging {
        /**
         * Which single appender the root logger gets. Matched exactly rather than
         * bound to an enum on purpose: this string is substituted into an
         * {@code <include>} resource path, and relaxed enum binding would happily
         * accept {@code ELASTIC} here and then leave logback looking for a
         * destination-ELASTIC.xml that does not exist.
         */
        @Pattern(regexp = "console|file|elastic",
                message = "must be one of console, file, elastic (lower case)")
        private String destination = "console";

        @Valid
        private final Elastic elastic = new Elastic();

        public String getDestination() {
            return destination;
        }

        public void setDestination(String destination) {
            this.destination = destination;
        }

        public Elastic getElastic() {
            return elastic;
        }

        /** Only consulted when destination is elastic. */
        public static class Elastic {
            /** Host of the Logstash/Elastic Agent TCP input, not of Elasticsearch itself. */
            private String host = "localhost";

            @Min(1)
            @Max(65535)
            private int port = 5044;

            /** TLS to that collector; the certificate must chain to the JVM trust store. */
            private boolean tls = false;

            public String getHost() {
                return host;
            }

            public void setHost(String host) {
                this.host = host;
            }

            public int getPort() {
                return port;
            }

            public void setPort(int port) {
                this.port = port;
            }

            public boolean isTls() {
                return tls;
            }

            public void setTls(boolean tls) {
                this.tls = tls;
            }
        }
    }

    /** OAuth app registration - never hardcoded, always from env vars/config excluded from version control. */
    public static class Google {
        private String clientId;
        private String clientSecret;

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }
    }

    public static class Microsoft {
        private String clientId;
        private String clientSecret;
        private String tenantId = "common";

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getTenantId() {
            return tenantId;
        }

        public void setTenantId(String tenantId) {
            this.tenantId = tenantId;
        }
    }
}
