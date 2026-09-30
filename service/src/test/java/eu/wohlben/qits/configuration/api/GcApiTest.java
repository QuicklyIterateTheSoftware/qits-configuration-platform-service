package eu.wohlben.qits.configuration.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.oneOf;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The entry collector's door, over the wire: who may call it, what it refuses, and that a dry run
 * and a real run report the same thing while only one of them deletes.
 *
 * <p>Seeding and the collecting calls send no identity header and so run as the {@code %test} dev
 * user ({@code qits:admin} + {@code qits:system}); the role cases use the forward-auth pair, as
 * {@link AgentReadAccessTest} does. The suite shares one database, so every body pins only this
 * class's own applications — everything else in the store is unpinned and kept.
 */
@QuarkusTest
class GcApiTest {

  private static final String BASE = "/configuration/api";
  private static final String GC = BASE + "/gc/entries";
  private static final String ENV = "test";
  private static final String YAML = "application/yaml";

  /** A value nothing in any answer may ever contain. */
  private static final String SECRET = "gc-s3cret-value-never-echoed";

  private static final RestAssuredConfig YAML_AS_TEXT =
      RestAssuredConfig.config()
          .encoderConfig(EncoderConfig.encoderConfig().encodeContentTypeAs(YAML, ContentType.TEXT));

  private static void put(String application, String key, String value) {
    given()
        .contentType(ContentType.JSON)
        .body(new ConfigurationController.SetEntryRequest(value))
        .when()
        .put(BASE + "/applications/" + application + "/envs/" + ENV + "/entries/" + key)
        .then()
        .statusCode(oneOf(200, 201));
  }

  private static void declare(String application, String version, String document) {
    given()
        .config(YAML_AS_TEXT)
        .contentType(YAML)
        .body(document)
        .when()
        .post(
            BASE
                + "/applications/"
                + application
                + "/declarations/"
                + version
                + "?deploymentTarget=environment")
        .then()
        .statusCode(oneOf(200, 201));
  }

  /** The request, with {@code deployments} shaped as qits-deployments answers it, extras included. */
  private static Map<String, Object> body(boolean dryRun, String application, String... versions) {
    return Map.of(
        "dryRun",
        dryRun,
        "deployments",
        Map.of(
            "generatedAt",
            "2026-09-30T00:00:00Z",
            "pins",
            List.of(Map.of("applicationName", application, "shas", List.of(versions)))));
  }

  @Test
  void anAgentIsRefusedAndTheSystemRoleIsAdmitted() {
    Map<String, Object> harmless = Map.of("dryRun", true, "deployments", Map.of("pins", List.of()));
    given()
        .header("X-Qits-User", "dyn-workspace-gc")
        .header("X-Qits-Roles", "qits:agent")
        .contentType(ContentType.JSON)
        .body(harmless)
        .when()
        .post(GC)
        .then()
        .statusCode(403);
    given()
        .header("X-Qits-User", "qits-platform-orchestrator")
        .header("X-Qits-Roles", "qits:system")
        .contentType(ContentType.JSON)
        .body(harmless)
        .when()
        .post(GC)
        .then()
        .statusCode(200)
        .body("dryRun", equalTo(true));
  }

  @Test
  void missingPinsAre400AndDeleteNothing() {
    put("gc-api-refused", "env.QITS_OLD", "x");
    declare("gc-api-refused", "1.0", "keys:\n  env.QITS_OLD:\n    type: string\n");
    declare("gc-api-refused", "2.0", "keys: {}\n");

    for (Object refused :
        List.of(
            Map.of("dryRun", false),
            Map.of("dryRun", false, "deployments", Map.of()),
            Map.of(
                "dryRun",
                false,
                "deployments",
                Map.of("pins", List.of(Map.of("applicationName", "gc-api-refused")))))) {
      given()
          .contentType(ContentType.JSON)
          .body(refused)
          .when()
          .post(GC)
          .then()
          .statusCode(400)
          .body("message", containsString("pins"));
    }

    // Pins that are not a list at all never reach the collector.
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("dryRun", false, "deployments", Map.of("pins", Map.of())))
        .when()
        .post(GC)
        .then()
        .statusCode(400);

    given()
        .when()
        .get(BASE + "/applications/gc-api-refused/envs/" + ENV + "/entries")
        .then()
        .statusCode(200)
        .body("entries.key", hasItem("env.QITS_OLD"));
  }

  /**
   * The whole round: a key declared by 1.0 and dropped by 2.0, with only 2.0 pinned. The dry run
   * names it and leaves it; the real run removes it through the ordinary delete, so the history ends
   * in a deleted revision attributed to the caller. Neither answer carries the value.
   */
  @Test
  void aDryRunReportsWhatARealRunRemoves() {
    String app = "gc-api-retired";
    put(app, "env.QITS_OLD", SECRET);
    put(app, "env.QITS_KEPT", SECRET);
    declare(
        app,
        "1.0",
        "keys:\n  env.QITS_OLD:\n    type: string\n  env.QITS_KEPT:\n    type: string\n");
    declare(app, "2.0", "keys:\n  env.QITS_KEPT:\n    type: string\n");

    String dry =
        given()
            .contentType(ContentType.JSON)
            .body(body(true, app, "2.0"))
            .when()
            .post(GC)
            .then()
            .statusCode(200)
            .body(not(containsString(SECRET)))
            .extract()
            .asString();
    JsonPath dryReport = JsonPath.from(dry);
    List<Map<String, Object>> mine =
        dryReport.getList("removed.findAll { it.application == '" + app + "' }");
    assertRemovedOnlyTheOldKey(mine);

    given()
        .when()
        .get(BASE + "/applications/" + app + "/envs/" + ENV + "/entries")
        .then()
        .statusCode(200)
        .body("entries.key", hasItem("env.QITS_OLD"));

    String real =
        given()
            .contentType(ContentType.JSON)
            .body(body(false, app, "2.0"))
            .when()
            .post(GC)
            .then()
            .statusCode(200)
            .body("dryRun", equalTo(false))
            .body(not(containsString(SECRET)))
            .extract()
            .asString();
    assertRemovedOnlyTheOldKey(
        JsonPath.from(real).getList("removed.findAll { it.application == '" + app + "' }"));

    given()
        .when()
        .get(BASE + "/applications/" + app + "/envs/" + ENV + "/entries")
        .then()
        .statusCode(200)
        .body("entries.key", not(hasItem("env.QITS_OLD")))
        .body("entries.key", hasItem("env.QITS_KEPT"));
    given()
        .when()
        .get(BASE + "/applications/" + app + "/envs/" + ENV + "/history")
        .then()
        .statusCode(200)
        .body("revisions[0].key", equalTo("env.QITS_OLD"))
        .body("revisions[0].deleted", equalTo(true))
        .body("revisions[0].updatedBy", org.hamcrest.Matchers.notNullValue());
  }

  private static void assertRemovedOnlyTheOldKey(List<Map<String, Object>> removed) {
    org.junit.jupiter.api.Assertions.assertEquals(
        List.of(
            Map.of(
                "application",
                "gc-api-retired",
                "env",
                ENV,
                "key",
                "env.QITS_OLD",
                "reason",
                "retired",
                "lastDeclaredBy",
                "1.0")),
        removed);
  }
}
