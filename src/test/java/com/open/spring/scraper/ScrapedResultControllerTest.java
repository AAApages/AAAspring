package com.open.spring.scraper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;
import com.open.spring.scraper.ScrapedResultController.SaveScrapeResultRequest;
import com.open.spring.scraper.ScrapedResultController.ScrapedResultDetailDto;
import com.open.spring.scraper.ScrapedResultController.ScrapedResultSummaryDto;
import com.open.spring.scraper.dto.ScrapeField;
import com.open.spring.scraper.dto.ScrapeRequest;
import com.open.spring.scraper.dto.ScrapeResponse;

/**
 * Covers POST/GET/DELETE /api/scraper/results: saving is a separate action from running a
 * scrape, a student only ever sees their own saved results, and a saved result round-trips
 * back to the same request/response shape that was saved.
 */
class ScrapedResultControllerTest {

    @Mock
    private ScrapedResultJpaRepository resultRepo;

    @Mock
    private PersonJpaRepository personRepo;

    private ScrapedResultController controller;

    private Person owner;
    private Person otherStudent;
    private Person teacher;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        controller = new ScrapedResultController();
        ReflectionTestUtils.setField(controller, "resultRepo", resultRepo);
        ReflectionTestUtils.setField(controller, "personRepo", personRepo);
        ReflectionTestUtils.setField(controller, "objectMapper", new ObjectMapper());

        owner = person(1L, "music-scraper");
        otherStudent = person(2L, "someone-else");
        teacher = person(3L, "the-teacher");
        teacher.setRoles(new java.util.ArrayList<>(List.of("ROLE_TEACHER")));
        when(personRepo.findByUid("music-scraper")).thenReturn(owner);
        when(personRepo.findByUid("someone-else")).thenReturn(otherStudent);
        when(personRepo.findByUid("the-teacher")).thenReturn(teacher);
        when(resultRepo.save(any(ScrapedResult.class))).thenAnswer(invocation -> {
            ScrapedResult toSave = invocation.getArgument(0);
            ReflectionTestUtils.setField(toSave, "id", 100L);
            return toSave;
        });
    }

    private Person person(Long id, String uid) {
        Person p = new Person();
        ReflectionTestUtils.setField(p, "id", id);
        p.setUid(uid);
        p.setRoles(List.of());
        return p;
    }

    private UserDetails caller(String uid) {
        return User.withUsername(uid).password("ignored").authorities(List.of()).build();
    }

    private SaveScrapeResultRequest sampleBody(String label) {
        ScrapeRequest request = new ScrapeRequest();
        request.setUrl("https://books.toscrape.com/catalogue/category/books/music_14/index.html");
        request.setItemsSelector("article.product_pod");
        ScrapeField titleField = new ScrapeField();
        titleField.setSelector("h3 a");
        titleField.setMode("attr");
        titleField.setAttr("title");
        request.setFields(Map.of("title", titleField));

        ScrapeResponse response = new ScrapeResponse(
            List.of(Map.of("title", "A Mind for Numbers")),
            Map.of("durationMs", 120, "pagesVisited", 1));

        SaveScrapeResultRequest body = new SaveScrapeResultRequest();
        body.setLabel(label);
        body.setRequest(request);
        body.setResponse(response);
        return body;
    }

    @Test
    void savingWithoutLoginIsRejected() {
        ResponseEntity<?> response = controller.save(sampleBody("my run"), null);

        assertEquals(401, response.getStatusCode().value());
    }

    @Test
    void savingWithoutALabelIsRejected() {
        ResponseEntity<?> response = controller.save(sampleBody(""), caller("music-scraper"));

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void savingPersistsOwnershipAndDenormalizedFields() {
        ResponseEntity<?> response = controller.save(sampleBody("Music run 1"), caller("music-scraper"));

        assertEquals(201, response.getStatusCode().value());
        ScrapedResultSummaryDto dto = (ScrapedResultSummaryDto) response.getBody();
        assertEquals("Music run 1", dto.getLabel());
        assertEquals("https://books.toscrape.com/catalogue/category/books/music_14/index.html", dto.getSourceUrl());
        assertEquals(1, dto.getItemCount());
    }

    @Test
    void listingOnlyReturnsTheCallersOwnResults() {
        when(resultRepo.findByPersonIdOrderByCreatedAtDesc(1L))
            .thenReturn(List.of(savedResult(100L, 1L, "music-scraper", "Music run 1")));

        ResponseEntity<?> response = controller.listMine(caller("music-scraper"));

        assertEquals(200, response.getStatusCode().value());
        verify(resultRepo).findByPersonIdOrderByCreatedAtDesc(1L);
    }

    @Test
    void gettingAnotherStudentsResultReturnsNotFoundNotForbidden() {
        // Same denial for "doesn't exist" and "exists but isn't yours" - 404 either way, so a
        // student can't tell which ids belong to someone else by the status code alone.
        ScrapedResult othersResult = savedResult(100L, 2L, "someone-else", "Their run");
        when(resultRepo.findById(100L)).thenReturn(Optional.of(othersResult));

        ResponseEntity<?> response = controller.getOne(100L, caller("music-scraper"));

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void deletingAnotherStudentsResultIsRejectedAndNothingIsDeleted() {
        ScrapedResult othersResult = savedResult(100L, 2L, "someone-else", "Their run");
        when(resultRepo.findById(100L)).thenReturn(Optional.of(othersResult));

        ResponseEntity<?> response = controller.delete(100L, caller("music-scraper"));

        assertEquals(404, response.getStatusCode().value());
        verify(resultRepo, org.mockito.Mockito.never()).delete(any(ScrapedResult.class));
    }

    @Test
    void aTeacherCanDeleteAnotherStudentsResult() {
        ScrapedResult othersResult = savedResult(100L, 2L, "someone-else", "Their run");
        when(resultRepo.findById(100L)).thenReturn(Optional.of(othersResult));

        ResponseEntity<?> response = controller.delete(100L, caller("the-teacher"));

        assertEquals(200, response.getStatusCode().value());
        verify(resultRepo).delete(othersResult);
    }

    @Test
    void savedResultRoundTripsBackToTheSameRequestAndResponseShape() {
        ResponseEntity<?> saveResponse = controller.save(sampleBody("Round trip"), caller("music-scraper"));
        ScrapedResultSummaryDto saved = (ScrapedResultSummaryDto) saveResponse.getBody();

        // The mocked save() above only sets the id; fetch what the controller actually built
        // and hand it back through findById, as the real repository would.
        ScrapedResult captured = captureLastSaved();
        when(resultRepo.findById(saved.getId())).thenReturn(Optional.of(captured));

        ResponseEntity<?> getResponse = controller.getOne(saved.getId(), caller("music-scraper"));

        assertEquals(200, getResponse.getStatusCode().value());
        ScrapedResultDetailDto detail = (ScrapedResultDetailDto) getResponse.getBody();
        assertEquals(
            "https://books.toscrape.com/catalogue/category/books/music_14/index.html",
            detail.getRequest().getUrl());
        assertEquals("A Mind for Numbers", detail.getResponse().getItems().get(0).get("title"));
    }

    @Test
    void aTeacherCanOpenAnotherStudentsResult() {
        ScrapedResult othersResult = savedResult(100L, 2L, "someone-else", "Their run");
        when(resultRepo.findById(100L)).thenReturn(Optional.of(othersResult));

        ResponseEntity<?> response = controller.getOne(100L, caller("the-teacher"));

        assertEquals(200, response.getStatusCode().value());
        ScrapedResultDetailDto detail = (ScrapedResultDetailDto) response.getBody();
        assertEquals("someone-else", detail.getPersonUid());
    }

    @Test
    void listingAllWithoutLoginIsRejected() {
        ResponseEntity<?> response = controller.listAll(null);

        assertEquals(401, response.getStatusCode().value());
    }

    @Test
    void aPlainStudentCannotListEveryonesResults() {
        ResponseEntity<?> response = controller.listAll(caller("music-scraper"));

        assertEquals(403, response.getStatusCode().value());
    }

    @Test
    void aTeacherListsEveryStudentsResults() {
        when(resultRepo.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(
            savedResult(100L, 1L, "music-scraper", "Music run 1"),
            savedResult(101L, 2L, "someone-else", "Their run")));

        ResponseEntity<?> response = controller.listAll(caller("the-teacher"));

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        List<ScrapedResultSummaryDto> dtos = (List<ScrapedResultSummaryDto>) response.getBody();
        assertEquals(2, dtos.size());
        assertEquals("music-scraper", dtos.get(0).getPersonUid());
        assertEquals("someone-else", dtos.get(1).getPersonUid());
    }

    @Test
    void malformedStoredJsonFailsClosedWithNullRatherThanAnException() {
        ScrapedResult corrupted = savedResult(100L, 1L, "music-scraper", "Corrupted");
        corrupted.setRequestJson("{ not valid json");
        corrupted.setResultJson(null);
        when(resultRepo.findById(100L)).thenReturn(Optional.of(corrupted));

        ResponseEntity<?> response = controller.getOne(100L, caller("music-scraper"));

        assertEquals(200, response.getStatusCode().value());
        ScrapedResultDetailDto detail = (ScrapedResultDetailDto) response.getBody();
        assertNull(detail.getRequest());
        assertNull(detail.getResponse());
    }

    private ScrapedResult savedResult(Long id, Long personId, String personUid, String label) {
        ScrapedResult result = new ScrapedResult();
        ReflectionTestUtils.setField(result, "id", id);
        result.setPersonId(personId);
        result.setPersonUid(personUid);
        result.setLabel(label);
        result.setSourceUrl("https://example.com");
        result.setItemCount(1);
        result.setRequestJson("{\"url\":\"https://example.com\",\"itemsSelector\":\"a\",\"fields\":{}}");
        result.setResultJson("{\"items\":[],\"meta\":{}}");
        return result;
    }

    private ScrapedResult captureLastSaved() {
        org.mockito.ArgumentCaptor<ScrapedResult> captor = org.mockito.ArgumentCaptor.forClass(ScrapedResult.class);
        verify(resultRepo, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }
}
