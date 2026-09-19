package com.hourhive.security;

import com.hourhive.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/**
 * Tiny stateless HMAC-SHA256 bearer token: base64url("userId:expiryEpoch") . base64url(signature).
 * No external JWT library needed, which keeps the free-tier build small and fast.
 */
@Service
public class TokenService {

    private static final String ALGO = "HmacSHA256";
    private final byte[] secret;
    private final long ttlSeconds;

    public TokenService(AppProperties props) {
        this.secret = props.jwtSecret().getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = props.tokenTtlHours() * 3600;
    }

    public String issue(long userId) {
        long exp = Instant.now().getEpochSecond() + ttlSeconds;
        String payload = userId + ":" + exp;
        return enc(payload.getBytes(StandardCharsets.UTF_8)) + "." + enc(hmac(payload));
    }

    public OptionalLong verify(String token) {
        try {
            int dot = token.indexOf('.');
            if (dot < 1) {
                return OptionalLong.empty();
            }
            String payload = new String(dec(token.substring(0, dot)), StandardCharsets.UTF_8);
            byte[] signature = dec(token.substring(dot + 1));
            if (!MessageDigest.isEqual(signature, hmac(payload))) {
                return OptionalLong.empty();
            }
            String[] parts = payload.split(":");
            if (parts.length != 2) {
                return OptionalLong.empty();
            }
            long exp = Long.parseLong(parts[1]);
            if (exp < Instant.now().getEpochSecond()) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(Long.parseLong(parts[0]));
        } catch (RuntimeException e) {
            return OptionalLong.empty();
        }
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGO);
            mac.init(new SecretKeySpec(secret, ALGO));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String enc(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] dec(String s) {
        return Base64.getUrlDecoder().decode(s);
    }
}
