package com.iflytek.skillhub.integration.feishu;

import com.iflytek.skillhub.domain.event.ReviewApprovedEvent;
import com.iflytek.skillhub.domain.event.ReviewRejectedEvent;
import com.iflytek.skillhub.domain.event.ReviewSubmittedEvent;
import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceType;
import com.iflytek.skillhub.listener.RecipientResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FeishuReviewListenerTest {

    @Mock FeishuReviewMessenger messenger;
    @Mock RecipientResolver recipientResolver;
    @Mock NamespaceRepository namespaceRepository;

    @InjectMocks FeishuReviewListener listener;

    private ReviewCardContext ctx() {
        return new ReviewCardContext(7L, "S", "team", "1.0.0", "alice", null);
    }

    @Test
    void teamNamespace_sendsToNamespaceAdminsAndPlatformAdmins() {
        Namespace ns = mock(Namespace.class);
        when(ns.getType()).thenReturn(NamespaceType.TEAM);
        when(namespaceRepository.findById(5L)).thenReturn(Optional.of(ns));
        when(messenger.buildContext(7L)).thenReturn(ctx());
        when(recipientResolver.resolveNamespaceAdmins(5L)).thenReturn(List.of("nsAdmin"));
        when(recipientResolver.resolvePlatformSkillAdmins()).thenReturn(List.of("platformAdmin"));

        listener.onReviewSubmitted(new ReviewSubmittedEvent(7L, 1L, 2L, "alice", 5L));

        verify(messenger).sendActionCard(eq("nsAdmin"), any());
        verify(messenger).sendActionCard(eq("platformAdmin"), any());
    }

    @Test
    void globalNamespace_sendsOnlyToPlatformAdmins() {
        Namespace ns = mock(Namespace.class);
        when(ns.getType()).thenReturn(NamespaceType.GLOBAL);
        when(namespaceRepository.findById(5L)).thenReturn(Optional.of(ns));
        when(messenger.buildContext(7L)).thenReturn(ctx());
        when(recipientResolver.resolvePlatformSkillAdmins()).thenReturn(List.of("platformAdmin"));

        listener.onReviewSubmitted(new ReviewSubmittedEvent(7L, 1L, 2L, "alice", 5L));

        verify(recipientResolver, never()).resolveNamespaceAdmins(any());
        verify(messenger).sendActionCard(eq("platformAdmin"), any());
    }

    @Test
    void approvedEvent_resolvesCardsAsApproved() {
        listener.onReviewApproved(new ReviewApprovedEvent(7L, 1L, 2L, "reviewer", "alice"));
        verify(messenger).resolveCards(7L, true, "reviewer", null, false);
    }

    @Test
    void rejectedEvent_resolvesCardsWithReason() {
        listener.onReviewRejected(new ReviewRejectedEvent(7L, 1L, 2L, "reviewer", "alice", "bad"));
        verify(messenger).resolveCards(7L, false, "reviewer", "bad", false);
    }
}
