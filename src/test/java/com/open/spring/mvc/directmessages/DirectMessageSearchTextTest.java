package com.open.spring.mvc.directmessages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DirectMessageSearchTextTest {

    @Test
    void plainTextDropsTagsAndDecodesEntities() {
        assertEquals("Hello world", DirectMessageSearchText.plainText("<p>Hello <em>world</em></p>"));
        assertEquals("Tom & Jerry", DirectMessageSearchText.plainText("Tom &amp; Jerry"));
        assertEquals("", DirectMessageSearchText.plainText(null));
    }

    @Test
    void likePatternIsLowerCasedAndWrappedInWildcards() {
        assertEquals("%sprint review%", DirectMessageSearchText.likePattern("Sprint Review"));
    }

    @Test
    void likePatternEscapesLikeWildcardsSoTheyMatchLiterally() {
        assertEquals("%50!%!_off!!%", DirectMessageSearchText.likePattern("50%_Off!"));
    }

    @Test
    void likePatternWritesHtmlCharactersTheWayABodyStoresThem() {
        assertEquals("%a&amp;b &lt;c&gt;%", DirectMessageSearchText.likePattern("a&b <c>"));
    }

    @Test
    void excerptOfAShortMessageIsTheWholeMessage() {
        assertEquals("see you at lunch", DirectMessageSearchText.excerpt("see you at lunch", "LUNCH"));
    }

    @Test
    void excerptOfALongMessageKeepsTheMatchAndMarksWhatWasCut() {
        String text = "a".repeat(200) + " needle " + "b".repeat(200);

        String excerpt = DirectMessageSearchText.excerpt(text, "needle");

        assertTrue(excerpt.startsWith("..."));
        assertTrue(excerpt.endsWith("..."));
        assertTrue(excerpt.contains("needle"));
        assertTrue(excerpt.length() < text.length());
    }

    @Test
    void excerptIsEmptyWhenTheTextDoesNotContainTheQuery() {
        assertEquals("", DirectMessageSearchText.excerpt("nothing here", "needle"));
        assertEquals(-1, DirectMessageSearchText.indexOfIgnoreCase("ab", "abc"));
    }
}
