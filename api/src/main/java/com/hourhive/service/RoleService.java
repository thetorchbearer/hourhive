package com.hourhive.service;

import com.hourhive.domain.Role;
import com.hourhive.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Role-based access control. Roles live in the database so changes take effect immediately. */
@Service
public class RoleService {

    private final JdbcClient jdbc;
    private final String bootstrapSecret;

    public RoleService(JdbcClient jdbc, @Value("${ADMIN_BOOTSTRAP_SECRET:}") String bootstrapSecret) {
        this.jdbc = jdbc;
        this.bootstrapSecret = bootstrapSecret == null ? "" : bootstrapSecret;
    }

    public Role roleOf(long userId) {
        String r = jdbc.sql("select role from users where id = :id and disabled = false")
                .param("id", userId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "Account is suspended or missing",
                        "ACCOUNT_SUSPENDED"));
        return Role.valueOf(r);
    }

    public void require(long userId, Role minimum) {
        if (!roleOf(userId).atLeast(minimum)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You don't have permission to do that", "FORBIDDEN");
        }
    }

    public void setRole(long userId, Role role) {
        int n = jdbc.sql("update users set role = :r where id = :id")
                .param("r", role.name()).param("id", userId).update();
        if (n == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Member not found");
        }
    }

    /** One-time promotion of the first admin using the ADMIN_BOOTSTRAP_SECRET environment variable. */
    public boolean bootstrap(long userId, String secret) {
        if (bootstrapSecret.isEmpty()) {
            return false;
        }
        Long admins = jdbc.sql("select count(*) from users where role = 'ADMIN'").query(Long.class).single();
        if (admins > 0) {
            return false;
        }
        boolean match = MessageDigest.isEqual(
                bootstrapSecret.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8));
        if (!match) {
            return false;
        }
        setRole(userId, Role.ADMIN);
        return true;
    }
}
