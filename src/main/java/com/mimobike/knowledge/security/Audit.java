package com.mimobike.knowledge.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Audit trail of MCP tool usage: who called which tool for which service and
 * document. Document content and tokens are never written here.
 */
public final class Audit {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    private Audit() {
    }

    public static void tool(String tool, String service, String path, String detail) {
        AUDIT.info("mcp_tool tool={} developer={} service={} path={} {}",
                tool, DeveloperContext.get(),
                service == null ? "-" : service,
                path == null ? "-" : path,
                detail == null ? "" : detail);
    }
}
