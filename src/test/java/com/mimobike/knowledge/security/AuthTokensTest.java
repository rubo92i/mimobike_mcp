package com.mimobike.knowledge.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuthTokensTest {

    @Test
    void parsesNamedDeveloperTokens() {
        AuthTokens tokens = new AuthTokens("alice:tok-a, bob:tok-b", "reload-tok");

        assertThat(tokens.developerFor("tok-a")).isEqualTo("alice");
        assertThat(tokens.developerFor("tok-b")).isEqualTo("bob");
        assertThat(tokens.developerFor("tok-c")).isNull();
        assertThat(tokens.developerFor("")).isNull();
        assertThat(tokens.developerFor(null)).isNull();
    }

    @Test
    void reloadTokenIsSeparateFromDeveloperTokens() {
        AuthTokens tokens = new AuthTokens("alice:tok-a", "reload-tok");

        assertThat(tokens.isReloadToken("reload-tok")).isTrue();
        // Developer token must not grant reload access and vice versa:
        assertThat(tokens.isReloadToken("tok-a")).isFalse();
        assertThat(tokens.developerFor("reload-tok")).isNull();
    }

    @Test
    void emptyConfigurationRejectsEverything() {
        AuthTokens tokens = new AuthTokens("", "");
        assertThat(tokens.developerFor("anything")).isNull();
        assertThat(tokens.isReloadToken("anything")).isFalse();
    }

    @Test
    void malformedEntriesAreIgnored() {
        AuthTokens tokens = new AuthTokens("justatoken,alice:tok-a,:oops,name:", "r");
        assertThat(tokens.developerFor("tok-a")).isEqualTo("alice");
        assertThat(tokens.developerFor("justatoken")).isNull();
    }

    @Test
    void nearMissTokensDoNotMatch() {
        AuthTokens tokens = new AuthTokens("alice:supersecrettoken", "reload");
        assertThat(tokens.developerFor("supersecrettoke")).isNull();
        assertThat(tokens.developerFor("supersecrettokenX")).isNull();
        assertThat(tokens.developerFor("Supersecrettoken")).isNull();
    }
}
