package com.mimobike.knowledge.model;

public record RefreshResult(
        String service,
        String repository,
        String previousSha,
        String newSha,
        boolean changed,
        String status,
        int documents,
        String error) {

    public static final String STATUS_REFRESHED = "refreshed";
    public static final String STATUS_UNCHANGED = "unchanged";
    public static final String STATUS_FAILED = "failed";

    public static RefreshResult refreshed(String service, String repository,
                                          String previousSha, String newSha, int documents) {
        return new RefreshResult(service, repository, previousSha, newSha, true,
                STATUS_REFRESHED, documents, null);
    }

    public static RefreshResult unchanged(String service, String repository, String sha, int documents) {
        return new RefreshResult(service, repository, sha, sha, false,
                STATUS_UNCHANGED, documents, null);
    }

    public static RefreshResult failed(String service, String repository,
                                       String previousSha, String error) {
        return new RefreshResult(service, repository, previousSha, null, false,
                STATUS_FAILED, 0, error);
    }
}
