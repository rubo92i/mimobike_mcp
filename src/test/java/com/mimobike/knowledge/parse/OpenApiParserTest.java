package com.mimobike.knowledge.parse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OpenApiParserTest {

    private static final String SPEC = """
            openapi: 3.0.1
            info:
              title: Wallet API
              version: "1.0"
            paths:
              /api/v1/wallet/topup:
                post:
                  operationId: topUpWallet
                  summary: Top up the wallet
                  responses:
                    "200":
                      description: Success
                    "402":
                      description: Insufficient funds
              /api/v1/wallet:
                get:
                  summary: Wallet balance
                  parameters:
                    - name: currency
                      in: query
                      required: true
                  responses:
                    "200":
                      description: OK
            """;

    @Test
    void extractsOperationsByPathAndMethod() {
        OpenApiParser.Parsed parsed = OpenApiParser.parse(SPEC, "wallet.yaml");

        assertThat(parsed).isNotNull();
        assertThat(parsed.title()).isEqualTo("Wallet API");
        assertThat(parsed.headings()).extracting("text")
                .containsExactly("POST /api/v1/wallet/topup", "GET /api/v1/wallet");

        String topUp = parsed.sections().get(0).content();
        assertThat(topUp).contains("Top up the wallet")
                .contains("topUpWallet")
                .contains("402: Insufficient funds");
        String balance = parsed.sections().get(1).content();
        assertThat(balance).contains("currency (in query, required)");
    }

    @Test
    void rejectsNonOpenApiYaml() {
        assertThat(OpenApiParser.parse("key: value\nother: 1\n", "config.yaml")).isNull();
        assertThat(OpenApiParser.parse("openapi: 3.0.1\ninfo:\n  title: X\n", "empty.yaml")).isNull();
        assertThat(OpenApiParser.parse("- just\n- a list\n", "list.yaml")).isNull();
        assertThat(OpenApiParser.parse(": {{{ not yaml", "broken.yaml")).isNull();
    }
}
