package com.iflytek.skillhub.integration.feishu;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data repository for {@link FeishuReviewCard} rows.
 */
@Repository
public interface FeishuReviewCardRepository extends JpaRepository<FeishuReviewCard, Long> {

    List<FeishuReviewCard> findByReviewTaskId(Long reviewTaskId);
}
