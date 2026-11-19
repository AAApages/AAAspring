package com.open.spring.mvc.assignments;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

class AssignmentRubricPolicyTest {
    @ParameterizedTest
    @ValueSource(doubles = {1, 10, 100})
    void generatesFiveScaledTiersWithSharedGradingPolicy(double points) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<String> sentPrompt = new AtomicReference<>();
        AtomicReference<String> reply = new AtomicReference<>(rubric(points));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/gemini", exchange -> {
            var request = mapper.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            sentPrompt.set(request.path("contents").get(0).path("parts").get(0).path("text").asText());
            byte[] body = mapper.writeValueAsBytes(Map.of("candidates",
                    List.of(Map.of("content", Map.of("parts", List.of(Map.of("text", reply.get())))))));
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var service = new AssignmentRubricService(mapper, "test-key",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/gemini");
            assertEquals(rubric(points), service.generateRubric("Loops", "Print a sequence", "Print 1 to 3.", points));
            String prompt = sentPrompt.get();
            assertTrue(prompt.contains("exactly five tiers"));
            assertTrue(prompt.contains(AssignmentRubricService.gradingPolicy(points)));
            // Numeric tier headings in the generation request must exactly match the scaled rubric.
            for (String heading : rubric(points).lines().filter(line -> line.startsWith("Score ")).toList()) {
                assertTrue(prompt.contains(heading), heading);
            }
            assertTrue(prompt.contains("ASSIGNMENT PAGE CONTENT:\nPrint 1 to 3."));

            reply.set("Score " + points * 0.9 + " — Strong / Exceptional\nScore 0 — Missing");
            assertNull(service.generateRubric("Loops", "Print a sequence", null, points));
        } finally {
            server.stop(0);
        }
    }

    private static String rubric(double points) {
        StringBuilder rubric = new StringBuilder();
        double[] fractions = {0.93, 0.9, 0.8, 0.7, 0.55};
        String[] labels = {"Exceptional", "Strong", "Adequate", "Limited", "Insufficient"};
        for (int i = 0; i < fractions.length; i++) {
            String score = java.math.BigDecimal.valueOf(Math.round(points * fractions[i] * 100) / 100.0)
                    .stripTrailingZeros().toPlainString();
            rubric.append("Score ").append(score).append(" — ").append(labels[i]).append("\nPrint the sequence.\n\n");
        }
        return rubric.toString().trim();
    }
}
