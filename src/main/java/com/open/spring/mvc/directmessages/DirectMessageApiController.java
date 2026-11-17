package com.open.spring.mvc.directmessages;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

/**
 * REST API for direct messages between 2 or more people. Every endpoint below
 * resolves "who am I" from the JWT-authenticated principal (never a
 * client-supplied name), and every conversation-scoped endpoint checks that
 * the caller is actually a participant before returning anything -- that
 * check is the whole point of this being separate from Groups' chat.
 */
@RestController
@RequestMapping("/api/dm")
@RequiredArgsConstructor
public class DirectMessageApiController {

    private final DirectMessageService directMessageService;
    private final DirectMessageConversationJpaRepository conversationRepository;
    private final DirectMessageJpaRepository messageRepository;
    private final PersonJpaRepository personRepository;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StartConversationRequest {
        private List<Long> otherPersonIds;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SendMessageRequest {
        private String body;
        /** Optional data URL; a message may be an image with no text. */
        private String image;
    }

    @GetMapping("/conversations")
    @Transactional(readOnly = true)
    public ResponseEntity<Object> getConversations(@AuthenticationPrincipal UserDetails userDetails) {
        Person person = currentPerson(userDetails);
        if (person == null) {
            return new ResponseEntity<>(Map.of("error", "User not authenticated"), HttpStatus.UNAUTHORIZED);
        }

        Map<Long, Long> unreadByConversation = directMessageService.getUnreadSummary(person).unreadByConversation();
        List<Map<String, Object>> conversations = conversationRepository.findByParticipantsContaining(person)
                .stream()
                .map(conversation -> conversationSummary(conversation, person, unreadByConversation))
                .toList();

        return new ResponseEntity<>(conversations, HttpStatus.OK);
    }

    /**
     * Drives the navbar notification bell: {@code count} is how many different people
     * have sent the caller messages they haven't read yet (people, not messages).
     */
    @GetMapping("/unread")
    @Transactional(readOnly = true)
    public ResponseEntity<Object> getUnread(@AuthenticationPrincipal UserDetails userDetails) {
        Person person = currentPerson(userDetails);
        if (person == null) {
            return new ResponseEntity<>(Map.of("error", "User not authenticated"), HttpStatus.UNAUTHORIZED);
        }

        DirectMessageService.UnreadSummary summary = directMessageService.getUnreadSummary(person);
        return new ResponseEntity<>(Map.of(
                "count", summary.people().size(),
                "people", summary.people(),
                "conversations", summary.unreadByConversation()), HttpStatus.OK);
    }

    /**
     * Searches the caller's own conversations: {@code GET /api/dm/search?q=term}. Returns the
     * matching messages newest first, each with its conversation id and a plain-text excerpt.
     * Only conversations the caller is a participant of are searched; add
     * {@code &conversationId=<id>} to search just one of them.
     */
    @GetMapping("/search")
    @Transactional(readOnly = true)
    public ResponseEntity<Object> searchMessages(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "conversationId", required = false) Long conversationId) {
        Person person = currentPerson(userDetails);
        if (person == null) {
            return new ResponseEntity<>(Map.of("error", "User not authenticated"), HttpStatus.UNAUTHORIZED);
        }

        try {
            return new ResponseEntity<>(
                    directMessageService.searchMessages(person, query, conversationId), HttpStatus.OK);
        } catch (IllegalArgumentException ex) {
            return new ResponseEntity<>(Map.of("error", ex.getMessage()), HttpStatus.BAD_REQUEST);
        }
    }

    @PostMapping("/conversations")
    @Transactional
    public ResponseEntity<Object> startConversation(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody StartConversationRequest request) {
        Person person = currentPerson(userDetails);
        if (person == null) {
            return new ResponseEntity<>(Map.of("error", "User not authenticated"), HttpStatus.UNAUTHORIZED);
        }

        if (request == null || request.getOtherPersonIds() == null || request.getOtherPersonIds().isEmpty()) {
            return new ResponseEntity<>(Map.of("error", "otherPersonIds is required"), HttpStatus.BAD_REQUEST);
        }

        Set<Long> seenIds = new LinkedHashSet<>();
        List<Person> others = request.getOtherPersonIds().stream()
                .filter(id -> id != null && seenIds.add(id))
                .map(id -> personRepository.findById(id).orElse(null))
                .filter(other -> other != null && !other.getId().equals(person.getId()))
                .toList();

        if (others.isEmpty()) {
            return new ResponseEntity<>(Map.of("error", "No valid recipients found"), HttpStatus.BAD_REQUEST);
        }

        // Must run before a new conversation is persisted: a query afterwards auto-flushes it, and the
        // commit-time flush then compares its participants by Person.hashCode(), which recurses forever
        // through Person <-> Bank (both Lombok @Data).
        Map<Long, Long> unreadByConversation = directMessageService.getUnreadSummary(person).unreadByConversation();
        DirectMessageConversation conversation = directMessageService.getOrCreateConversation(person, others);
        return new ResponseEntity<>(conversationSummary(conversation, person, unreadByConversation), HttpStatus.OK);
    }

    @GetMapping("/conversations/{id}/messages")
    @Transactional(readOnly = true)
    public ResponseEntity<Object> getMessages(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable("id") Long id) {
        Person person = currentPerson(userDetails);
        if (person == null) {
            return new ResponseEntity<>(Map.of("error", "User not authenticated"), HttpStatus.UNAUTHORIZED);
        }

        Optional<DirectMessageConversation> conversationOpt = conversationRepository.findById(id);
        if (conversationOpt.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        DirectMessageConversation conversation = conversationOpt.get();
        if (!directMessageService.isParticipant(conversation, person)) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }

        List<Map<String, Object>> messages = directMessageService.getMessages(conversation).stream()
                .map(this::messageSummary)
                .toList();

        return new ResponseEntity<>(messages, HttpStatus.OK);
    }

    @PostMapping("/conversations/{id}/messages")
    @Transactional
    public ResponseEntity<Object> postMessage(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable("id") Long id,
            @RequestBody SendMessageRequest request) {
        Person person = currentPerson(userDetails);
        if (person == null) {
            return new ResponseEntity<>(Map.of("error", "User not authenticated"), HttpStatus.UNAUTHORIZED);
        }

        Optional<DirectMessageConversation> conversationOpt = conversationRepository.findById(id);
        if (conversationOpt.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        DirectMessageConversation conversation = conversationOpt.get();
        if (!directMessageService.isParticipant(conversation, person)) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }

        if (request == null) {
            return new ResponseEntity<>(Map.of("error", "body is required"), HttpStatus.BAD_REQUEST);
        }

        String image = request.getImage();
        boolean hasImage = image != null && !image.isBlank();
        String body = request.getBody() == null ? "" : request.getBody().trim();
        // An image on its own is a message; text is only required without one.
        if (body.isBlank() && !hasImage) {
            return new ResponseEntity<>(Map.of("error", "body is required"), HttpStatus.BAD_REQUEST);
        }
        if (body.length() > DirectMessageService.MAX_BODY_LENGTH) {
            return new ResponseEntity<>(Map.of("error", "body is too long"), HttpStatus.BAD_REQUEST);
        }

        DirectMessageEvent event = directMessageService.postMessage(
                conversation, person, body, hasImage ? image : null);
        return new ResponseEntity<>(event, HttpStatus.OK);
    }

    @PatchMapping("/conversations/{id}/messages/{messageId}")
    @Transactional
    public ResponseEntity<Object> editMessage(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable("id") Long id,
            @PathVariable("messageId") Long messageId,
            @RequestBody SendMessageRequest request) {
        Person person = currentPerson(userDetails);
        if (person == null) {
            return new ResponseEntity<>(Map.of("error", "User not authenticated"), HttpStatus.UNAUTHORIZED);
        }

        Optional<DirectMessageConversation> conversationOpt = conversationRepository.findById(id);
        if (conversationOpt.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        DirectMessageConversation conversation = conversationOpt.get();
        if (!directMessageService.isParticipant(conversation, person)) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }

        if (request == null || request.getBody() == null || request.getBody().isBlank()) {
            return new ResponseEntity<>(Map.of("error", "body is required"), HttpStatus.BAD_REQUEST);
        }

        try {
            DirectMessage updated = directMessageService.editMessage(
                    conversation, person, messageId, request.getBody().trim());
            return new ResponseEntity<>(messageSummary(updated), HttpStatus.OK);
        } catch (NoSuchElementException ex) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        } catch (SecurityException ex) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        } catch (IllegalStateException ex) {
            return new ResponseEntity<>(Map.of("error", ex.getMessage()), HttpStatus.CONFLICT);
        }
    }

    @DeleteMapping("/conversations/{id}/messages/{messageId}")
    @Transactional
    public ResponseEntity<Object> deleteMessage(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable("id") Long id,
            @PathVariable("messageId") Long messageId) {
        Person person = currentPerson(userDetails);
        if (person == null) {
            return new ResponseEntity<>(Map.of("error", "User not authenticated"), HttpStatus.UNAUTHORIZED);
        }

        Optional<DirectMessageConversation> conversationOpt = conversationRepository.findById(id);
        if (conversationOpt.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        DirectMessageConversation conversation = conversationOpt.get();
        if (!directMessageService.isParticipant(conversation, person)) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }

        try {
            directMessageService.deleteMessage(conversation, person, messageId);
            return new ResponseEntity<>(HttpStatus.NO_CONTENT);
        } catch (NoSuchElementException ex) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        } catch (SecurityException ex) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }
    }

    @PostMapping("/conversations/{id}/read")
    @Transactional
    public ResponseEntity<Object> markRead(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable("id") Long id) {
        Person person = currentPerson(userDetails);
        if (person == null) {
            return new ResponseEntity<>(Map.of("error", "User not authenticated"), HttpStatus.UNAUTHORIZED);
        }

        Optional<DirectMessageConversation> conversationOpt = conversationRepository.findById(id);
        if (conversationOpt.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        DirectMessageConversation conversation = conversationOpt.get();
        if (!directMessageService.isParticipant(conversation, person)) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }

        directMessageService.markRead(conversation, person);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    private Person currentPerson(UserDetails userDetails) {
        if (userDetails == null) {
            return null;
        }
        return personRepository.findByUid(userDetails.getUsername());
    }

    private Map<String, Object> conversationSummary(
            DirectMessageConversation conversation, Person self, Map<Long, Long> unreadByConversation) {
        List<Map<String, Object>> otherParticipants = conversation.getParticipants().stream()
                .filter(participant -> !participant.getId().equals(self.getId()))
                .map(participant -> Map.<String, Object>of(
                        "id", participant.getId(),
                        "uid", participant.getUid(),
                        "name", participant.getName()))
                .toList();

        return Map.of(
                "id", conversation.getId(),
                "participants", otherParticipants,
                "unreadCount", unreadByConversation.getOrDefault(conversation.getId(), 0L));
    }

    private Map<String, Object> messageSummary(DirectMessage message) {
        Person sender = message.getSender();
        // A deleted message's image goes with its text: the tombstone shows neither.
        String image = message.isDeleted() || message.getImage() == null ? "" : message.getImage();
        return Map.of(
                "id", message.getId(),
                "senderUid", sender.getUid(),
                "senderName", sender.getName(),
                "senderPfp", sender.getPfp() == null ? "" : sender.getPfp(),
                "body", message.getBody() == null ? "" : message.getBody(),
                "image", image,
                "sentAt", message.getSentAt().toString(),
                "deleted", message.isDeleted(),
                "edited", message.isEdited());
    }
}
