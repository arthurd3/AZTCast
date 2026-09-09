package com.azt.streaming;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * There is no application-wide database, and the JDBC autoconfiguration is excluded to keep it that
 * way.
 *
 * <p>The provider log uses SQLite and builds its own {@code DataSource}, gated on its own feature
 * flag. Left to itself, Boot sees a JDBC driver on the classpath and insists on a
 * {@code spring.datasource} for the whole application — which does not exist, so it fails to start
 * with "Failed to determine a suitable driver class" even when the one feature that wants SQL is
 * switched off. Excluding these says the true thing: SQL here belongs to one slice, not to the
 * service.
 */
@SpringBootApplication(
        exclude = {
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class
        })
@ConfigurationPropertiesScan
public class StreamingApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(StreamingApiApplication.class, args);
    }
}
