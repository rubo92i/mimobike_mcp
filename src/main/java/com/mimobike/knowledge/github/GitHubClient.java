package com.mimobike.knowledge.github;

import java.util.List;

/**
 * Read-only view of the GitHub contents API. Only three operations exist —
 * resolving a branch head, listing a tree, and fetching a blob — so the server
 * is structurally unable to write to GitHub or read anything else.
 */
public interface GitHubClient {

    /** Head commit SHA of the given branch. */
    String headCommitSha(String repository, String branch);

    /** Recursive tree listing at the given commit. */
    List<TreeEntry> tree(String repository, String commitSha);

    /** Decoded UTF-8 content of a blob. */
    String blobContent(String repository, String blobSha);

    record TreeEntry(String path, String type, String sha, long size) {
    }
}
