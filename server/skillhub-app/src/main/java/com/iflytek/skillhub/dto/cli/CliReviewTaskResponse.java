package com.iflytek.skillhub.dto.cli;

import com.iflytek.skillhub.dto.ReviewTaskResponse;
import java.time.Instant;

public record CliReviewTaskResponse(
        Long id,
        Long skillVersionId,
        String namespace,
        String skillSlug,
        String skillVersion,
        Integer taskVersion,
        String status,
        String submittedBy,
        String submittedByName,
        String reviewedBy,
        String reviewedByName,
        String reviewComment,
        Instant submittedAt,
        Instant reviewedAt) {

    public static CliReviewTaskResponse from(ReviewTaskResponse response, Integer taskVersion) {
        return new CliReviewTaskResponse(
                response.id(), response.skillVersionId(), response.namespace(), response.skillSlug(),
                response.version(), taskVersion, response.status(), response.submittedBy(),
                response.submittedByName(), response.reviewedBy(), response.reviewedByName(),
                response.reviewComment(), response.submittedAt(), response.reviewedAt());
    }
}
