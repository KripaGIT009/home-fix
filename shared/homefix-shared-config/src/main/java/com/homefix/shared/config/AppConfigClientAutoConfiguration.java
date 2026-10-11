package com.homefix.shared.config;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * Registers {@link AppConfigClientController} in the one service that serves the web apps'
 * settings ({@code homefix.app-config.client-endpoint.enabled=true}); the API gateway routes
 * {@code /config/**} there. That service must also permit {@code GET /config/client} without a
 * token.
 */
@AutoConfiguration(afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(DispatcherServlet.class)
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(name = "homefix.app-config.client-endpoint.enabled", havingValue = "true")
public class AppConfigClientAutoConfiguration {

    @Bean
    public AppConfigClientController appConfigClientController(DataSource dataSource) {
        return new AppConfigClientController(new JdbcTemplate(dataSource));
    }
}
