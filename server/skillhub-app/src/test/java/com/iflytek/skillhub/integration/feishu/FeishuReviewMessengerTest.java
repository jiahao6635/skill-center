package com.iflytek.skillhub.integration.feishu;

import com.iflytek.skillhub.auth.entity.IdentityBinding;
import com.iflytek.skillhub.auth.repository.IdentityBindingRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.review.ReviewTaskRepository;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import com.iflytek.skillhub.domain.skill.SkillVersionRepository;
import com.iflytek.skillhub.domain.user.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FeishuReviewMessengerTest {

    @Mock FeishuBotClient botClient;
    @Mock FeishuReviewCardRepository cardRepository;
    @Mock IdentityBindingRepository identityBindingRepository;
    @Mock ReviewTaskRepository reviewTaskRepository;
    @Mock SkillVersionRepository skillVersionRepository;
    @Mock SkillRepository skillRepository;
    @Mock NamespaceRepository namespaceRepository;
    @Mock UserAccountRepository userAccountRepository;

    private FeishuReviewMessenger messenger() {
        ReviewCardFactory cardFactory = new ReviewCardFactory(new com.fasterxml.jackson.databind.ObjectMapper());
        return new FeishuReviewMessenger(botClient, cardFactory, cardRepository, identityBindingRepository,
                reviewTaskRepository, skillVersionRepository, skillRepository, namespaceRepository,
                userAccountRepository, "https://hub.example.com");
    }

    private IdentityBinding feishuBinding(String userId, String openId) {
        return new IdentityBinding(userId, "feishu", openId, "Reviewer");
    }

    @Test
    void findOpenId_returnsFeishuSubject() {
        when(identityBindingRepository.findByUserId("u1"))
                .thenReturn(List.of(feishuBinding("u1", "ou_123")));

        assertThat(messenger().findOpenId("u1")).contains("ou_123");
    }

    @Test
    void sendActionCard_sendsAndPersists_whenBound() {
        when(identityBindingRepository.findByUserId("u1"))
                .thenReturn(List.of(feishuBinding("u1", "ou_123")));
        when(botClient.sendInteractiveCard(eq("ou_123"), anyString())).thenReturn("om_msg1");

        ReviewCardContext ctx = new ReviewCardContext(7L, "Skill", "team", "1.0.0", "alice", null);
        messenger().sendActionCard("u1", ctx);

        verify(botClient).sendInteractiveCard(eq("ou_123"), anyString());
        verify(cardRepository).save(any(FeishuReviewCard.class));
    }

    @Test
    void sendActionCard_skips_whenNoBinding() {
        when(identityBindingRepository.findByUserId("u1")).thenReturn(List.of());

        messenger().sendActionCard("u1", new ReviewCardContext(7L, "S", "t", null, null, null));

        verifyNoInteractions(botClient);
        verify(cardRepository, never()).save(any());
    }

    @Test
    void sendActionCard_swallowsSendFailure() {
        when(identityBindingRepository.findByUserId("u1"))
                .thenReturn(List.of(feishuBinding("u1", "ou_123")));
        when(botClient.sendInteractiveCard(anyString(), anyString()))
                .thenThrow(new FeishuBotException("boom"));

        ReviewCardContext ctx = new ReviewCardContext(7L, "S", "t", null, null, null);
        // Must not throw — Feishu delivery is best-effort.
        messenger().sendActionCard("u1", ctx);

        verify(cardRepository, never()).save(any());
    }

    @Test
    void resolveCards_patchesPendingCardsToTerminal() {
        FeishuReviewCard card = spy(new FeishuReviewCard(7L, "u1", "ou_123", "om_msg1"));
        when(cardRepository.findByReviewTaskId(7L)).thenReturn(List.of(card));
        // buildContext: task missing -> null context short-circuits; provide minimal task instead.
        var task = mock(com.iflytek.skillhub.domain.review.ReviewTask.class);
        when(task.getSkillVersionId()).thenReturn(1L);
        when(task.getNamespaceId()).thenReturn(5L);
        when(task.getSubmittedBy()).thenReturn("alice");
        when(reviewTaskRepository.findById(7L)).thenReturn(Optional.of(task));

        messenger().resolveCards(7L, true, "reviewer", null, false);

        verify(botClient).updateCard(eq("om_msg1"), anyString());
        verify(card).markResolved();
        verify(cardRepository).save(card);
    }

    @Test
    void resolveCards_noCards_noop() {
        when(cardRepository.findByReviewTaskId(7L)).thenReturn(List.of());

        messenger().resolveCards(7L, false, "reviewer", "bad", true);

        verifyNoInteractions(botClient);
    }
}
