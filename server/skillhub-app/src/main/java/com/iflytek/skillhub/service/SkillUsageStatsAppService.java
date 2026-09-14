package com.iflytek.skillhub.service;

import com.iflytek.skillhub.dto.PageResponse;
import com.iflytek.skillhub.dto.SkillUsageStats.*;
import com.iflytek.skillhub.repository.SkillUsageStatsQueryRepository;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

@Service
public class SkillUsageStatsAppService {
    private final SkillUsageStatsQueryRepository repository;
    public SkillUsageStatsAppService(SkillUsageStatsQueryRepository repository) { this.repository = repository; }

    static void validate(Instant from, Instant to, String email, String product, int page, int size) {
        if (from == null || to == null || !from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(366)) > 0
                || page < 0 || size < 1 || size > 200 || email != null && email.length() > 256
                || product != null && !product.isBlank() && !Set.of("qoder", "qoder_ide", "qoderwork", "unknown").contains(product))
            throw new IllegalArgumentException("Invalid time range, filter or pagination");
    }
    private void validateSearch(String search) {
        if (search != null && search.length() > 512) throw new IllegalArgumentException("Search is too long");
    }
    public Summary summary(Instant from, Instant to, String email, String product) {
        validate(from, to, email, product, 0, 20);
        return repository.summary(from, to, email, product);
    }
    public PageResponse<SkillRank> skills(Instant from, Instant to, String email, String product, String search, int page, int size) {
        validate(from, to, email, product, page, size); validateSearch(search);
        return repository.skills(from, to, email, product, search, page, size);
    }
    public PageResponse<UserRank> users(Instant from, Instant to, String email, String product, int page, int size) {
        validate(from, to, email, product, page, size);
        return repository.users(from, to, email, product, page, size);
    }
    public List<UserOption> userOptions(String search) {
        validateSearch(search);
        return repository.userOptions(search);
    }
}
