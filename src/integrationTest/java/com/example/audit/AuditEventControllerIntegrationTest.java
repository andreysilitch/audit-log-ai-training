package com.example.audit;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuditEventControllerIntegrationTest {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;

  @Test
  void postsAndSearchesEvents() throws Exception {
    ObjectNode body = objectMapper.createObjectNode();
    body.put("actor", "alice");
    body.put("action", "user.login");
    body.put("resource", "user:42");
    body.put("outcome", "SUCCESS");
    body.set("context", objectMapper.createObjectNode().put("ip", "10.0.0.1"));

    mvc.perform(
            post("/audit-events")
                .contentType(APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(body)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").exists())
        .andExpect(jsonPath("$.timestamp").exists())
        .andExpect(jsonPath("$.actor").value("alice"))
        .andExpect(jsonPath("$.hash").exists());

    Instant from = Instant.now().minusSeconds(60);
    Instant to = Instant.now().plusSeconds(60);

    mvc.perform(
            get("/audit-events")
                .param("actor", "alice")
                .param("from", from.toString())
                .param("to", to.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].actor").value("alice"));
  }

  @Test
  void rejectsBlankActor() throws Exception {
    ObjectNode body = objectMapper.createObjectNode();
    body.put("actor", "");
    body.put("action", "x");
    body.put("resource", "y");
    body.put("outcome", "SUCCESS");

    mvc.perform(
            post("/audit-events")
                .contentType(APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(body)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("validation_failed"));
  }

  @Test
  void rejectsInvalidOutcome() throws Exception {
    String json =
        """
                {"actor":"a","action":"b","resource":"c","outcome":"WAT"}
                """;
    mvc.perform(post("/audit-events").contentType(APPLICATION_JSON).content(json))
        .andExpect(status().isBadRequest());
  }

  @Test
  void searchRequiresTimeRange() throws Exception {
    mvc.perform(get("/audit-events").param("actor", "alice")).andExpect(status().isBadRequest());
  }

  @Test
  void ignoresClientSuppliedTimestamp() throws Exception {
    // Even if a client adds a timestamp field, it must not flow into the server payload.
    ObjectNode body = objectMapper.createObjectNode();
    body.put("actor", "alice");
    body.put("action", "user.login");
    body.put("resource", "user:42");
    body.put("outcome", "SUCCESS");
    body.put("timestamp", "1970-01-01T00:00:00Z");

    mvc.perform(
            post("/audit-events")
                .contentType(APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(body)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.timestamp", org.hamcrest.Matchers.not("1970-01-01T00:00:00Z")));
  }
}
