package com.hourhive.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the pool from a Neon-style URL (postgresql://user:pass@host/db?sslmode=require)
 * so you can paste the connection string straight from the Neon dashboard.
 * The pool is deliberately tiny and lets connections die when idle so Neon can auto-suspend.
 */
@Configuration
public class DatabaseConfig {

    @Bean
    public DataSource dataSource(AppProperties props) {
        HikariConfig cfg = new HikariConfig();
        String raw = props.databaseUrl() == null ? "" : props.databaseUrl().trim();

        if (raw.isEmpty()) {
            cfg.setJdbcUrl("jdbc:postgresql://localhost:5432/hourhive");
            cfg.setUsername("postgres");
            cfg.setPassword("postgres");
        } else if (raw.startsWith("jdbc:")) {
            cfg.setJdbcUrl(raw);
        } else {
            applyUri(cfg, raw);
        }

        cfg.setPoolName("hourhive");
        cfg.setMaximumPoolSize(5);
        cfg.setMinimumIdle(0);
        cfg.setIdleTimeout(60_000);
        cfg.setMaxLifetime(300_000);
        cfg.setConnectionTimeout(30_000);      // Neon cold start can take a few seconds
        cfg.setInitializationFailTimeout(60_000);
        return new HikariDataSource(cfg);
    }

    private static void applyUri(HikariConfig cfg, String raw) {
        URI uri = URI.create(raw.replaceFirst("^postgres(ql)?://", "postgresql://"));

        String userInfo = uri.getRawUserInfo();
        if (userInfo != null) {
            int colon = userInfo.indexOf(':');
            String user = colon >= 0 ? userInfo.substring(0, colon) : userInfo;
            String pass = colon >= 0 ? userInfo.substring(colon + 1) : "";
            cfg.setUsername(URLDecoder.decode(user, StandardCharsets.UTF_8));
            cfg.setPassword(URLDecoder.decode(pass, StandardCharsets.UTF_8));
        }

        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        List<String> params = new ArrayList<>();
        String query = uri.getRawQuery();
        if (query != null) {
            for (String p : query.split("&")) {
                // pgjdbc does not need Neon's channel_binding hint
                if (!p.isBlank() && !p.startsWith("channel_binding")) {
                    params.add(p);
                }
            }
        }
        if (params.stream().noneMatch(p -> p.startsWith("sslmode="))) {
            params.add("sslmode=require");
        }
        cfg.setJdbcUrl("jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getRawPath()
                + "?" + String.join("&", params));
    }
}
