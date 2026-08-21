package com.example.vatica.artifact;

/** 产物中心对前端公开的脱敏视图。 */
public record ArtifactView(String id, String subjectType, String subjectId, String type, String name,
        String locator, String status, String summary, String sourceActionId, String idempotencyKey,
        String createdAt, String updatedAt) {

    static ArtifactView from(ArtifactRecord record) {
        return new ArtifactView(record.getId(), record.getSubjectType(), record.getSubjectId(), record.getType(),
                record.getName(), record.getLocator(), record.getStatus().name(), record.getSummary(),
                record.getSourceActionId(), record.getIdempotencyKey(), instant(record.getCreatedAt()),
                instant(record.getUpdatedAt()));
    }

    private static String instant(java.time.Instant value) {
        return value == null ? null : value.toString();
    }
}
