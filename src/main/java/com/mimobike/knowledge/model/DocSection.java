package com.mimobike.knowledge.model;

/**
 * One Markdown section, split by heading (never by token count). The preamble
 * before the first heading has a {@code null} heading and level 0.
 */
public record DocSection(String heading, int level, String anchor, String content) {
}
