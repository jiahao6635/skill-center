package com.iflytek.skillhub.integration.feishu;

/**
 * Display data needed to render a review card. Assembled by the outbound
 * listeners from the skill/version/namespace records.
 *
 * @param reviewTaskId  the review task id, echoed back in button values
 * @param skillName     human-readable skill name
 * @param namespaceSlug namespace slug (e.g. {@code my-team})
 * @param version       skill version label (may be null)
 * @param submitter     display name / id of the submitter
 * @param reviewUrl     deep link to the review in the Web UI (may be null)
 */
public record ReviewCardContext(
        Long reviewTaskId,
        String skillName,
        String namespaceSlug,
        String version,
        String submitter,
        String reviewUrl
) {}
