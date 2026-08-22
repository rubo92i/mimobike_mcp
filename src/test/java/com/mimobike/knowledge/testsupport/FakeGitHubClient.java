package com.mimobike.knowledge.testsupport;

import com.mimobike.knowledge.github.GitHubClient;
import com.mimobike.knowledge.github.GitHubException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory GitHub double. Records every blob fetch so tests can assert that
 * disallowed files are never even requested. When no per-repo data is
 * registered, serves {@link #defaultFiles} for any repository (integration
 * tests with the full production repository list).
 */
public class FakeGitHubClient implements GitHubClient {

    public final Map<String, String> headShaByRepo = new HashMap<>();
    public final Map<String, Map<String, String>> filesByRepo = new HashMap<>();
    public Map<String, String> defaultFiles = Map.of(
            "docs/overview.md", "# Overview\n\nFake overview.\n");
    public String defaultSha = "default-sha-1";

    public final List<String> fetchedBlobPaths = new CopyOnWriteArrayList<>();
    public final AtomicInteger headCalls = new AtomicInteger();
    public final AtomicInteger treeCalls = new AtomicInteger();
    public volatile boolean failHead;
    public volatile boolean failTree;

    public void register(String repository, String sha, Map<String, String> files) {
        headShaByRepo.put(repository, sha);
        filesByRepo.put(repository, new HashMap<>(files));
    }

    @Override
    public String headCommitSha(String repository, String branch) {
        headCalls.incrementAndGet();
        if (failHead) {
            throw new GitHubException("simulated GitHub outage (head)");
        }
        return headShaByRepo.getOrDefault(repository, defaultSha);
    }

    @Override
    public List<TreeEntry> tree(String repository, String commitSha) {
        treeCalls.incrementAndGet();
        if (failTree) {
            throw new GitHubException("simulated GitHub outage (tree)");
        }
        Map<String, String> files = filesByRepo.getOrDefault(repository, defaultFiles);
        List<TreeEntry> entries = new ArrayList<>();
        for (Map.Entry<String, String> file : files.entrySet()) {
            entries.add(new TreeEntry(file.getKey(), "blob",
                    blobSha(repository, file.getKey()), file.getValue().length()));
        }
        return entries;
    }

    @Override
    public String blobContent(String repository, String blobSha) {
        Map<String, String> files = filesByRepo.getOrDefault(repository, defaultFiles);
        for (Map.Entry<String, String> file : files.entrySet()) {
            if (blobSha(repository, file.getKey()).equals(blobSha)) {
                fetchedBlobPaths.add(file.getKey());
                return file.getValue();
            }
        }
        throw new GitHubException("unknown blob " + blobSha);
    }

    private static String blobSha(String repository, String path) {
        return "blob-" + Integer.toHexString((repository + "/" + path).hashCode());
    }
}
