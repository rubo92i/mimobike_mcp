package com.mimobike.knowledge.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Static bearer tokens from the environment. Developer tokens are
 * {@code name:token} pairs (the name is the audit identity); the reload token
 * is separate and grants access only to {@code /internal/*}. All comparisons
 * are constant-time. Token values are never logged.
 */
@Component
public class AuthTokens {

    private static final Logger log = LoggerFactory.getLogger(AuthTokens.class);

    private final Map<String, byte[]> developerTokens = new LinkedHashMap<>();
    private final byte[] reloadToken;

    public AuthTokens(
            @Value("${mimo.security.auth-tokens:}") String authTokens,
            @Value("${mimo.security.reload-token:}") String reloadToken) {

        if (authTokens != null && !authTokens.isBlank()) {
            for (String pair : authTokens.split(",")) {
                String entry = pair.strip();
                if (entry.isEmpty()) {
                    continue;
                }
                int colon = entry.indexOf(':');
                if (colon <= 0 || colon == entry.length() - 1) {
                    log.warn("Ignoring malformed MCP_AUTH_TOKENS entry (expected name:token)");
                    continue;
                }
                String name = entry.substring(0, colon).strip();
                String token = entry.substring(colon + 1).strip();
                developerTokens.put(name, token.getBytes(StandardCharsets.UTF_8));
            }
        }
        this.reloadToken = reloadToken == null || reloadToken.isBlank()
                ? null
                : reloadToken.strip().getBytes(StandardCharsets.UTF_8);

        if (developerTokens.isEmpty()) {
            log.warn("MCP_AUTH_TOKENS is empty — every /mcp request will be rejected (401)");
        }
        if (this.reloadToken == null) {
            log.warn("MCP_RELOAD_TOKEN is empty — every /internal request will be rejected (401)");
        } else {
            log.info("Auth configured: {} developer token(s), reload token present",
                    developerTokens.size());
        }
    }

    /** Developer name for a presented bearer token, or {@code null} if invalid. */
    public String developerFor(String presented) {
        if (presented == null || presented.isEmpty()) {
            return null;
        }
        byte[] candidate = presented.getBytes(StandardCharsets.UTF_8);
        String matched = null;
        for (Map.Entry<String, byte[]> entry : developerTokens.entrySet()) {
            if (MessageDigest.isEqual(entry.getValue(), candidate) && matched == null) {
                matched = entry.getKey();
            }
        }
        return matched;
    }

    public boolean isReloadToken(String presented) {
        if (presented == null || presented.isEmpty() || reloadToken == null) {
            return false;
        }
        return MessageDigest.isEqual(reloadToken, presented.getBytes(StandardCharsets.UTF_8));
    }
}
