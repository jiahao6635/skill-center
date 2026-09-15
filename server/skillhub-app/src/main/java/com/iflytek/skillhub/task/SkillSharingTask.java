package com.iflytek.skillhub.task;

import com.iflytek.skillhub.domain.skill.SkillShareRequestRepository;
import com.iflytek.skillhub.domain.skill.SkillShareStatus;
import com.iflytek.skillhub.domain.skill.service.SkillSharingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Resumes durable sharing requests after scan completion or process restarts. Each advance is atomic. */
@Component
public class SkillSharingTask {
    private static final Logger log = LoggerFactory.getLogger(SkillSharingTask.class);
    private final SkillShareRequestRepository requests;
    private final SkillSharingService sharing;
    public SkillSharingTask(SkillShareRequestRepository requests, SkillSharingService sharing) {
        this.requests = requests; this.sharing = sharing;
    }
    @Scheduled(fixedDelayString = "${skillhub.sharing.poll-delay-ms:5000}")
    public void advanceRequests() {
        for (SkillShareStatus status : new SkillShareStatus[]{SkillShareStatus.SCANNING, SkillShareStatus.PENDING_REVIEW}) {
            Long afterId = 0L;
            while (true) {
                var batch = requests.findTop100ByStatusAndIdGreaterThanOrderByIdAsc(status, afterId);
                if (batch.isEmpty()) break;
                for (var request : batch) {
                    try { sharing.advance(request.getId()); }
                    catch (RuntimeException ex) { log.warn("Could not advance sharing request id={}", request.getId(), ex); }
                }
                afterId = batch.getLast().getId();
            }
        }
    }
}
