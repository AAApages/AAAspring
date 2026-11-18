package com.open.spring.mvc.directmessages;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.push.PushNotificationService;

import lombok.RequiredArgsConstructor;

/**
 * Business logic for direct messages: finding/creating conversations, checking
 * who's allowed to see one, and saving + broadcasting new messages. Kept out of
 * the controller so DirectMessageApiController only has to handle HTTP concerns
 * (this mirrors GroupChatApiController -> GroupChatService/GroupChatRealtimeService).
 */
@Service
@RequiredArgsConstructor
public class DirectMessageService {

    public static final String DM_TOPIC_PREFIX = "/topic/dm/";
    public static final int MAX_BODY_LENGTH = 2000; // matches @Column(length = 2000) on DirectMessage.body
    public static final int MIN_SEARCH_LENGTH = 2;
    public static final int MAX_SEARCH_LENGTH = 100;
    public static final int MAX_SEARCH_RESULTS = 50;
    /** How many database matches one search reads before checking their plain text. */
    private static final int SEARCH_CANDIDATE_LIMIT = 200;

    private final DirectMessageConversationJpaRepository conversationRepository;
    private final DirectMessageJpaRepository messageRepository;
    private final DirectMessageReadStateJpaRepository readStateRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final PushNotificationService pushNotificationService;

    public record UnreadSender(String uid, String name) {
    }

    /**
     * {@code people} is every distinct person with at least one unread message for
     * the viewer (the notification badge counts people, not messages), most recent
     * first; {@code unreadByConversation} is the unread message count per conversation.
     */
    public record UnreadSummary(List<UnreadSender> people, Map<Long, Long> unreadByConversation) {
    }

    /**
     * One message a search found. {@code excerpt} is plain text around the match, never
     * HTML, so the page can show it without sanitising it.
     */
    public record SearchHit(Long id, Long conversationId, String senderUid, String senderName,
            String sentAt, String excerpt) {
    }

    /**
     * Finds or creates the conversation between {@code requester} and
     * {@code others}. A 1:1 conversation (exactly one other person) is reused
     * the next time either person starts a chat with the other; a group of 3+
     * people always gets a brand-new conversation, since there's no single
     * "the" conversation for an arbitrary group of participants -- this
     * matches how Groups already has no dedup for its member lists.
     */
    @Transactional
    public DirectMessageConversation getOrCreateConversation(Person requester, List<Person> others) {
        if (requester == null) {
            throw new IllegalArgumentException("requester is required");
        }
        if (others == null || others.isEmpty()) {
            throw new IllegalArgumentException("at least one other participant is required");
        }

        List<Person> dedupedOthers = dedupeParticipants(others, requester);
        if (dedupedOthers.size() == 1) {
            Person other = dedupedOthers.get(0);
            List<DirectMessageConversation> existing = conversationRepository.findByParticipantsContaining(requester);
            for (DirectMessageConversation conversation : existing) {
                List<Person> participants = conversation.getParticipants();
                if (participants.size() == 2 && containsPerson(participants, other)) {
                    return conversation;
                }
            }
        }

        List<Person> participants = new ArrayList<>();
        participants.add(requester);
        participants.addAll(dedupedOthers);

        return conversationRepository.save(new DirectMessageConversation(participants));
    }

    /**
     * The security boundary: only participants may read or post in a
     * conversation. Compared by id rather than Person.equals(), since equals()
     * on JPA entities can be unreliable across separately-loaded instances.
     */
    public boolean isParticipant(DirectMessageConversation conversation, Person person) {
        return containsPerson(conversation.getParticipants(), person);
    }

    @Transactional(readOnly = true)
    public List<DirectMessage> getMessages(DirectMessageConversation conversation) {
        return messageRepository.findByConversationOrderBySentAtAsc(conversation);
    }

    @Transactional
    public DirectMessageEvent postMessage(DirectMessageConversation conversation, Person sender, String body) {
        return postMessage(conversation, sender, body, null);
    }

    /** {@code image} is an optional data URL; a message may be an image with no text. */
    @Transactional
    public DirectMessageEvent postMessage(DirectMessageConversation conversation, Person sender,
            String body, String image) {
        String text = body == null ? "" : body.trim();
        // Only a text-only message has to have text in it.
        if (image == null || image.isBlank()) {
            validateMessageBody(body);
        } else if (text.length() > MAX_BODY_LENGTH) {
            throw new IllegalArgumentException("body is too long");
        }
        DirectMessage message = messageRepository.save(
                new DirectMessage(conversation, sender, text, image));

        DirectMessageEvent event = DirectMessageEvent.builder()
                .id(message.getId())
                .conversationId(conversation.getId())
                .type("message")
                .senderUid(sender.getUid())
                .senderName(sender.getName())
                .senderPfp(sender.getPfp())
                .body(text)
                .image(image)
                .sentAt(message.getSentAt().toString())
                .build();

        // Posting in a conversation means the sender has caught up on it.
        advanceReadMarker(conversation, sender, message.getId());

        messagingTemplate.convertAndSend(DM_TOPIC_PREFIX + conversation.getId(), event);

        // Everyone else in the conversation gets a phone notification once this commits.
        // The socket topic only reaches tabs that are open; push reaches a locked phone.
        // Tapping it opens the conversation; one tag per conversation so a burst of
        // messages collapses into the newest instead of stacking up.
        String url = "/dm?dm=" + conversation.getId();
        String tag = "dm-" + conversation.getId();
        for (Person participant : conversation.getParticipants()) {
            if (!participant.getId().equals(sender.getId())) {
                pushNotificationService.notifyPerson(participant, sender.getName(), body, url, tag);
            }
        }
        return event;
    }

    /**
     * Only the original sender may edit, and only while the message hasn't been deleted.
     * Throws {@link NoSuchElementException} if {@code messageId} isn't in this conversation,
     * {@link SecurityException} if {@code requester} didn't send it.
     *
     * {@code noRollbackFor}: these are expected, caller-handled outcomes (404/403/409), not
     * failures -- without it, the exception crossing this method's transactional boundary
     * marks the (shared, REQUIRED-propagation) transaction rollback-only, and the controller's
     * own @Transactional then fails to commit with UnexpectedRollbackException even though it
     * catches the exception and returns a normal response.
     */
    @Transactional(noRollbackFor = {NoSuchElementException.class, SecurityException.class, IllegalStateException.class})
    public DirectMessage editMessage(
            DirectMessageConversation conversation, Person requester, Long messageId, String newBody) {
        DirectMessage message = messageRepository.findByIdAndConversation(messageId, conversation)
                .orElseThrow(() -> new NoSuchElementException("Message not found"));
        if (!message.getSender().getId().equals(requester.getId())) {
            throw new SecurityException("Not the message sender");
        }
        if (message.isDeleted()) {
            throw new IllegalStateException("Message was deleted");
        }

        message.setBody(newBody);
        message.setEdited(true);
        messageRepository.save(message);

        DirectMessageEvent event = DirectMessageEvent.builder()
                .id(message.getId())
                .conversationId(conversation.getId())
                .senderUid(requester.getUid())
                .senderName(requester.getName())
                .body(newBody)
                .sentAt(message.getSentAt().toString())
                .type("edited")
                .edited(true)
                .build();
        messagingTemplate.convertAndSend(DM_TOPIC_PREFIX + conversation.getId(), event);
        return message;
    }

    /**
     * Soft-delete: the row stays (so the conversation's order and read-state markers aren't
     * disturbed) but its body is cleared and {@code deleted} is set, so history shows a
     * tombstone instead of just dropping the message. Same ownership rule as {@link #editMessage},
     * and the same {@code noRollbackFor} reasoning -- see the comment there.
     */
    @Transactional(noRollbackFor = {NoSuchElementException.class, SecurityException.class})
    public void deleteMessage(DirectMessageConversation conversation, Person requester, Long messageId) {
        DirectMessage message = messageRepository.findByIdAndConversation(messageId, conversation)
                .orElseThrow(() -> new NoSuchElementException("Message not found"));
        if (!message.getSender().getId().equals(requester.getId())) {
            throw new SecurityException("Not the message sender");
        }
        if (message.isDeleted()) {
            return;
        }

        message.setDeleted(true);
        message.setBody("");
        messageRepository.save(message);

        DirectMessageEvent event = DirectMessageEvent.builder()
                .id(message.getId())
                .conversationId(conversation.getId())
                .senderUid(requester.getUid())
                .senderName(requester.getName())
                .sentAt(message.getSentAt().toString())
                .type("deleted")
                .deleted(true)
                .build();
        messagingTemplate.convertAndSend(DM_TOPIC_PREFIX + conversation.getId(), event);
    }

    @Transactional(readOnly = true)
    public UnreadSummary getUnreadSummary(Person person) {
        Map<String, UnreadSender> people = new LinkedHashMap<>();
        Map<Long, Long> unreadByConversation = new LinkedHashMap<>();
        for (UnreadMessageCount row : messageRepository.countUnreadBySender(person)) {
            people.putIfAbsent(row.senderUid(), new UnreadSender(row.senderUid(), row.senderName()));
            unreadByConversation.merge(row.conversationId(), row.unreadCount(), Long::sum);
        }
        return new UnreadSummary(List.copyOf(people.values()), unreadByConversation);
    }

    /**
     * Messages in {@code person}'s own conversations whose text contains {@code query},
     * newest first, at most {@link #MAX_SEARCH_RESULTS}. Case is ignored.
     *
     * The database filters on the stored HTML, which also matches tag and class names
     * (a search for "strong" finds every bold message), so each candidate's plain text
     * is checked again here. Throws {@link IllegalArgumentException} for a query that is
     * too short or too long; {@code noRollbackFor} for the reason given on
     * {@link #editMessage}.
     */
    @Transactional(readOnly = true, noRollbackFor = IllegalArgumentException.class)
    public List<SearchHit> searchMessages(Person person, String query) {
        return searchMessages(person, query, null);
    }

    /**
     * Same as {@link #searchMessages(Person, String)}, limited to one conversation when
     * {@code conversationId} is given. A conversation the person isn't in finds nothing.
     */
    @Transactional(readOnly = true, noRollbackFor = IllegalArgumentException.class)
    public List<SearchHit> searchMessages(Person person, String query, Long conversationId) {
        String term = query == null ? "" : query.trim();
        if (term.length() < MIN_SEARCH_LENGTH) {
            throw new IllegalArgumentException("query needs at least " + MIN_SEARCH_LENGTH + " characters");
        }
        if (term.length() > MAX_SEARCH_LENGTH) {
            throw new IllegalArgumentException("query is too long");
        }

        String pattern = DirectMessageSearchText.likePattern(term);
        PageRequest page = PageRequest.of(0, SEARCH_CANDIDATE_LIMIT);
        List<DirectMessage> candidates = conversationId == null
                ? messageRepository.findSearchCandidates(person, pattern, page)
                : messageRepository.findSearchCandidatesInConversation(person, conversationId, pattern, page);

        List<SearchHit> hits = new ArrayList<>();
        for (DirectMessage message : candidates) {
            String excerpt = DirectMessageSearchText.excerpt(
                    DirectMessageSearchText.plainText(message.getBody()), term);
            if (excerpt.isEmpty()) {
                continue; // matched only inside the HTML markup
            }
            hits.add(new SearchHit(
                    message.getId(),
                    message.getConversation().getId(),
                    message.getSender().getUid(),
                    message.getSender().getName(),
                    message.getSentAt().toString(),
                    excerpt));
            if (hits.size() == MAX_SEARCH_RESULTS) {
                break;
            }
        }
        return hits;
    }

    /** Marks everything currently in the conversation as read for {@code person}. */
    @Transactional
    public void markRead(DirectMessageConversation conversation, Person person) {
        messageRepository.findTopByConversationOrderByIdDesc(conversation)
                .ifPresent(latest -> advanceReadMarker(conversation, person, latest.getId()));
    }

    private void advanceReadMarker(DirectMessageConversation conversation, Person person, Long messageId) {
        DirectMessageReadState state = readStateRepository.findByConversationAndPerson(conversation, person)
                .orElseGet(() -> new DirectMessageReadState(conversation, person));
        if (state.getLastReadMessageId() == null || state.getLastReadMessageId() < messageId) {
            state.setLastReadMessageId(messageId);
            readStateRepository.save(state);
        }
    }

    private boolean containsPerson(List<Person> people, Person target) {
        return people.stream().anyMatch(person -> person.getId().equals(target.getId()));
    }

    private void validateMessageBody(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("body is required");
        }
        if (body.trim().length() > MAX_BODY_LENGTH) {
            throw new IllegalArgumentException("body is too long");
        }
    }

    private List<Person> dedupeParticipants(List<Person> others, Person requester) {
        Set<Long> seenIds = new LinkedHashSet<>();
        List<Person> deduped = new ArrayList<>();
        for (Person person : others) {
            if (person == null || person.getId() == null || person.getId().equals(requester.getId())) {
                continue;
            }
            if (seenIds.add(person.getId())) {
                deduped.add(person);
            }
        }
        return deduped;
    }
}
