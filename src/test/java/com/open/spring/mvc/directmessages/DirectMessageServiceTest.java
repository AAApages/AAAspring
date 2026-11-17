package com.open.spring.mvc.directmessages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.open.spring.mvc.person.Person;

class DirectMessageServiceTest {

    private DirectMessageService service;
    private List<DirectMessageConversation> saved;
    private List<DirectMessage> savedMessages;
    private List<DirectMessageReadState> readStates;
    private List<UnreadMessageCount> unreadRows;
    private int readStateSaves;
    private Person alice;
    private Person bob;
    private Person carol;

    @BeforeEach
    void setUp() {
        alice = person(1L, "alice");
        bob = person(2L, "bob");
        carol = person(3L, "carol");
        saved = new ArrayList<>();
        savedMessages = new ArrayList<>();
        readStates = new ArrayList<>();
        unreadRows = List.of();
        readStateSaves = 0;

        DirectMessageConversationJpaRepository conversationRepository = repositoryStub(
            DirectMessageConversationJpaRepository.class,
            (methodName, arguments) -> switch (methodName) {
                case "findByParticipantsContaining" -> {
                    Person target = (Person) arguments[0];
                    yield saved.stream()
                        .filter(conversation -> conversation.getParticipants().stream()
                            .anyMatch(p -> p.getId().equals(target.getId())))
                        .toList();
                }
                case "save" -> {
                    DirectMessageConversation conversation = (DirectMessageConversation) arguments[0];
                    if (conversation.getId() == null) {
                        ReflectionTestUtils.setField(conversation, "id", (long) (saved.size() + 1));
                    }
                    saved.add(conversation);
                    yield conversation;
                }
                default -> throw new UnsupportedOperationException(methodName);
            }
        );

        DirectMessageJpaRepository messageRepository = repositoryStub(
            DirectMessageJpaRepository.class,
            (methodName, arguments) -> switch (methodName) {
                case "countUnreadBySender" -> unreadRows;
                // Stands in for the query's access check and ordering; the LIKE itself is
                // left out, so the service's own plain-text check is what these tests see.
                case "findSearchCandidates" -> {
                    Person target = (Person) arguments[0];
                    List<DirectMessage> found = new ArrayList<>(savedMessages.stream()
                        .filter(message -> !message.isDeleted())
                        .filter(message -> message.getConversation().getParticipants().stream()
                            .anyMatch(p -> p.getId().equals(target.getId())))
                        .toList());
                    Collections.reverse(found);
                    yield found;
                }
                case "findSearchCandidatesInConversation" -> {
                    Person target = (Person) arguments[0];
                    Long conversationId = (Long) arguments[1];
                    List<DirectMessage> found = new ArrayList<>(savedMessages.stream()
                        .filter(message -> !message.isDeleted())
                        .filter(message -> conversationId.equals(message.getConversation().getId()))
                        .filter(message -> message.getConversation().getParticipants().stream()
                            .anyMatch(p -> p.getId().equals(target.getId())))
                        .toList());
                    Collections.reverse(found);
                    yield found;
                }
                case "findTopByConversationOrderByIdDesc" -> savedMessages.stream()
                    .filter(message -> message.getConversation() == arguments[0])
                    .reduce((first, second) -> second);
                case "findByIdAndConversation" -> savedMessages.stream()
                    .filter(message -> message.getId().equals(arguments[0])
                        && message.getConversation() == arguments[1])
                    .findFirst();
                case "save" -> {
                    DirectMessage message = (DirectMessage) arguments[0];
                    if (message.getId() == null) {
                        ReflectionTestUtils.setField(message, "id", (long) (savedMessages.size() + 1));
                        savedMessages.add(message);
                    }
                    yield message;
                }
                default -> throw new UnsupportedOperationException(methodName);
            }
        );

        DirectMessageReadStateJpaRepository readStateRepository = repositoryStub(
            DirectMessageReadStateJpaRepository.class,
            (methodName, arguments) -> switch (methodName) {
                case "findByConversationAndPerson" -> readStates.stream()
                    .filter(state -> state.getConversation() == arguments[0]
                        && state.getPerson().getId().equals(((Person) arguments[1]).getId()))
                    .findFirst();
                case "save" -> {
                    DirectMessageReadState state = (DirectMessageReadState) arguments[0];
                    if (!readStates.contains(state)) {
                        readStates.add(state);
                    }
                    readStateSaves++;
                    yield state;
                }
                default -> throw new UnsupportedOperationException(methodName);
            }
        );

        // A real SimpMessagingTemplate over a no-op channel, so postMessage can broadcast.
        service = new DirectMessageService(conversationRepository, messageRepository, readStateRepository,
                new SimpMessagingTemplate((message, timeout) -> true));
    }

    @Test
    void reusesTheSame1to1ConversationRegardlessOfWhoStartsIt() {
        DirectMessageConversation first = service.getOrCreateConversation(alice, List.of(bob));
        DirectMessageConversation second = service.getOrCreateConversation(bob, List.of(alice));

        assertSame(first, second);
        assertEquals(1, saved.size());
    }

    @Test
    void startingAGroupChatAlwaysCreatesANewConversation() {
        DirectMessageConversation oneOnOne = service.getOrCreateConversation(alice, List.of(bob));
        DirectMessageConversation group = service.getOrCreateConversation(alice, List.of(bob, carol));

        assertNotEquals(oneOnOne.getId(), group.getId());
        assertEquals(2, saved.size());
    }

    @Test
    void onlyParticipantsPassTheMembershipCheck() {
        DirectMessageConversation conversation = service.getOrCreateConversation(alice, List.of(bob));

        assertTrue(service.isParticipant(conversation, alice));
        assertTrue(service.isParticipant(conversation, bob));
        assertFalse(service.isParticipant(conversation, carol));
    }

    @Test
    void unreadSummaryCountsPeopleNotMessages() {
        unreadRows = List.of(
            new UnreadMessageCount(1L, "bob", "Bob", 3L),
            new UnreadMessageCount(2L, "carol", "Carol", 2L),
            new UnreadMessageCount(2L, "bob", "Bob", 1L));

        DirectMessageService.UnreadSummary summary = service.getUnreadSummary(alice);

        assertEquals(List.of(
                new DirectMessageService.UnreadSender("bob", "Bob"),
                new DirectMessageService.UnreadSender("carol", "Carol")),
            summary.people());
        assertEquals(Map.of(1L, 3L, 2L, 3L), summary.unreadByConversation());
    }

    @Test
    void markingReadMovesTheMarkerToTheLatestMessage() {
        DirectMessageConversation conversation = service.getOrCreateConversation(alice, List.of(bob));
        service.postMessage(conversation, bob, "one");
        service.postMessage(conversation, bob, "two");

        service.markRead(conversation, alice);

        assertEquals(2L, readStateFor(conversation, alice).getLastReadMessageId());
    }

    @Test
    void theReadMarkerNeverMovesBackwards() {
        DirectMessageConversation conversation = service.getOrCreateConversation(alice, List.of(bob));
        service.postMessage(conversation, bob, "hi");
        DirectMessageReadState state = new DirectMessageReadState(conversation, alice);
        state.setLastReadMessageId(50L);
        readStates.add(state);
        int savesBefore = readStateSaves;

        service.markRead(conversation, alice);

        assertEquals(50L, state.getLastReadMessageId());
        assertEquals(savesBefore, readStateSaves);
    }

    @Test
    void postingAMessageMarksTheConversationReadForTheSender() {
        DirectMessageConversation conversation = service.getOrCreateConversation(alice, List.of(bob));

        DirectMessageEvent event = service.postMessage(conversation, alice, "hello");

        assertEquals(event.getId(), readStateFor(conversation, alice).getLastReadMessageId());
        assertTrue(readStates.stream().noneMatch(state -> state.getPerson() == bob));
    }

    @Test
    void editingOwnMessageUpdatesTheBody() {
        DirectMessageConversation conversation = service.getOrCreateConversation(alice, List.of(bob));
        DirectMessageEvent posted = service.postMessage(conversation, alice, "hello");

        DirectMessage updated = service.editMessage(conversation, alice, posted.getId(), "hello, edited");

        assertEquals("hello, edited", updated.getBody());
        assertFalse(updated.isDeleted());
    }

    @Test
    void editingSomeoneElsesMessageIsRejected() {
        DirectMessageConversation conversation = service.getOrCreateConversation(alice, List.of(bob));
        DirectMessageEvent posted = service.postMessage(conversation, alice, "hello");

        assertThrows(SecurityException.class,
            () -> service.editMessage(conversation, bob, posted.getId(), "not mine to edit"));
    }

    @Test
    void editingAMissingMessageIsNotFound() {
        DirectMessageConversation conversation = service.getOrCreateConversation(alice, List.of(bob));

        assertThrows(NoSuchElementException.class,
            () -> service.editMessage(conversation, alice, 999L, "no such message"));
    }

    @Test
    void deletingOwnMessageClearsTheBodyAndMarksItDeleted() {
        DirectMessageConversation conversation = service.getOrCreateConversation(alice, List.of(bob));
        DirectMessageEvent posted = service.postMessage(conversation, alice, "hello");

        service.deleteMessage(conversation, alice, posted.getId());

        DirectMessage stored = savedMessages.stream()
            .filter(message -> message.getId().equals(posted.getId()))
            .findFirst()
            .orElseThrow();
        assertTrue(stored.isDeleted());
        assertEquals("", stored.getBody());
    }

    @Test
    void deletingSomeoneElsesMessageIsRejected() {
        DirectMessageConversation conversation = service.getOrCreateConversation(alice, List.of(bob));
        DirectMessageEvent posted = service.postMessage(conversation, alice, "hello");

        assertThrows(SecurityException.class,
            () -> service.deleteMessage(conversation, bob, posted.getId()));
    }

    @Test
    void editingADeletedMessageIsRejected() {
        DirectMessageConversation conversation = service.getOrCreateConversation(alice, List.of(bob));
        DirectMessageEvent posted = service.postMessage(conversation, alice, "hello");
        service.deleteMessage(conversation, alice, posted.getId());

        assertThrows(IllegalStateException.class,
            () -> service.editMessage(conversation, alice, posted.getId(), "too late"));
    }

    @Test
    void searchFindsTextInTheCallersConversationsNewestFirst() {
        DirectMessageConversation withBob = service.getOrCreateConversation(alice, List.of(bob));
        DirectMessageConversation withCarol = service.getOrCreateConversation(alice, List.of(carol));
        DirectMessageEvent older = service.postMessage(withBob, bob, "The Sprint review is on Friday");
        service.postMessage(withBob, alice, "ok thanks");
        DirectMessageEvent newer = service.postMessage(withCarol, alice, "did you finish the sprint retro?");

        List<DirectMessageService.SearchHit> hits = service.searchMessages(alice, "  SPRINT ");

        assertEquals(List.of(newer.getId(), older.getId()), hits.stream().map(hit -> hit.id()).toList());
        assertEquals(withCarol.getId(), hits.get(0).conversationId());
        assertEquals("bob", hits.get(1).senderUid());
        assertEquals("The Sprint review is on Friday", hits.get(1).excerpt());
    }

    @Test
    void searchCanBeLimitedToOneConversation() {
        DirectMessageConversation withBob = service.getOrCreateConversation(alice, List.of(bob));
        DirectMessageConversation withCarol = service.getOrCreateConversation(alice, List.of(carol));
        DirectMessageEvent inBob = service.postMessage(withBob, bob, "The Sprint review is on Friday");
        service.postMessage(withCarol, alice, "did you finish the sprint retro?");

        List<DirectMessageService.SearchHit> hits = service.searchMessages(alice, "sprint", withBob.getId());

        assertEquals(List.of(inBob.getId()), hits.stream().map(hit -> hit.id()).toList());
        assertEquals(2, service.searchMessages(alice, "sprint", null).size());
    }

    @Test
    void searchLimitedToAConversationTheCallerIsNotInFindsNothing() {
        DirectMessageConversation conversation = service.getOrCreateConversation(alice, List.of(bob));
        service.postMessage(conversation, alice, "the secret plan");

        assertTrue(service.searchMessages(carol, "secret", conversation.getId()).isEmpty());
    }

    @Test
    void searchMatchesTheTextNotTheHtmlAroundIt() {
        DirectMessageConversation conversation = service.getOrCreateConversation(alice, List.of(bob));
        service.postMessage(conversation, bob, "<strong>Lunch</strong> at noon?");

        assertTrue(service.searchMessages(alice, "strong").isEmpty());

        List<DirectMessageService.SearchHit> hits = service.searchMessages(alice, "lunch at");
        assertEquals(1, hits.size());
        assertEquals("Lunch at noon?", hits.get(0).excerpt());
    }

    @Test
    void searchNeverReturnsAConversationTheCallerIsNotIn() {
        DirectMessageConversation conversation = service.getOrCreateConversation(alice, List.of(bob));
        service.postMessage(conversation, alice, "the secret plan");

        assertTrue(service.searchMessages(carol, "secret").isEmpty());
    }

    @Test
    void searchSkipsDeletedMessages() {
        DirectMessageConversation conversation = service.getOrCreateConversation(alice, List.of(bob));
        DirectMessageEvent posted = service.postMessage(conversation, alice, "delete me later");
        service.deleteMessage(conversation, alice, posted.getId());

        assertTrue(service.searchMessages(bob, "delete me").isEmpty());
    }

    @Test
    void searchRejectsAQueryThatIsTooShortOrTooLong() {
        assertThrows(IllegalArgumentException.class, () -> service.searchMessages(alice, " a "));
        assertThrows(IllegalArgumentException.class, () -> service.searchMessages(alice, null));
        assertThrows(IllegalArgumentException.class,
            () -> service.searchMessages(alice, "x".repeat(DirectMessageService.MAX_SEARCH_LENGTH + 1)));
    }

    private DirectMessageReadState readStateFor(DirectMessageConversation conversation, Person person) {
        return readStates.stream()
            .filter(state -> state.getConversation() == conversation && state.getPerson() == person)
            .findFirst()
            .orElseThrow();
    }

    private Person person(Long id, String uid) {
        Person person = new Person();
        ReflectionTestUtils.setField(person, "id", id);
        person.setUid(uid);
        person.setName(uid);
        return person;
    }

    @SuppressWarnings("unchecked")
    private <T> T repositoryStub(Class<T> repositoryType, RepositoryCall call) {
        return (T) Proxy.newProxyInstance(
            repositoryType.getClassLoader(),
            new Class<?>[] {repositoryType},
            (proxy, method, arguments) -> call.invoke(method.getName(), arguments)
        );
    }

    @FunctionalInterface
    private interface RepositoryCall {
        Object invoke(String methodName, Object[] arguments);
    }
}
