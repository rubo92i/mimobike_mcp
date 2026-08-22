package com.mimobike.knowledge.security;

/** Developer identity of the current MCP request, for audit logging only. */
public final class DeveloperContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private DeveloperContext() {
    }

    public static void set(String developer) {
        CURRENT.set(developer);
    }

    public static String get() {
        String developer = CURRENT.get();
        return developer == null ? "unknown" : developer;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
