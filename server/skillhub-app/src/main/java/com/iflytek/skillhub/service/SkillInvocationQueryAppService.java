package com.iflytek.skillhub.service;

import com.iflytek.skillhub.dto.*;
import com.iflytek.skillhub.repository.SkillInvocationQueryRepository;
import org.springframework.stereotype.Service;
import java.time.Instant;

@Service
public class SkillInvocationQueryAppService {
    private final SkillInvocationQueryRepository repository;
    public SkillInvocationQueryAppService(SkillInvocationQueryRepository repository) { this.repository=repository; }
    public PageResponse<SkillInvocationItem> list(int page,int size,String email,String userId,String skillName,
            Long skillId,String product,String sessionId,Instant from,Instant to) {
        if (page<0 || size<1 || size>200 || from!=null && to!=null && !from.isBefore(to))
            throw new IllegalArgumentException("Invalid pagination or time range");
        return repository.list(page,size,email,userId,skillName,skillId,product,sessionId,from,to);
    }
}
