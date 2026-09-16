package eu.wohlben.qits.configuration.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.oneOf;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

/**
 * The pin report over the wire — the answer qits-artifacts' collector holds against age when it
 * decides which images it may delete.
 *
 * <p>The applications and keys are the platform's real ones, because the AUTHORED half of the map is
 * a compile-time constant: there is no pin on an application of this test's own to write. Nothing
 * else in this suite touches them, and every test begins by writing all four values, so none depends
 * on the order the class is run in — the two that change the answer put back what they removed or
 * added.
 *
 * <p>The DECLARED half needs no such apology: an application of this test's own declares a
 * {@code packageVersion} key and the report answers for it, which is the whole point of the
 * generalisation — a pin arrives with its consumer's own document instead of with an edit here.
 *
 * <p><b>No test sends an identity header</b>, as in {@code ConfigurationApiTest}: qits-auth-core
 * ships a {@code %test} dev user carrying {@code qits:admin} and {@code qits:system}, so the shipped
 * {@code @RolesAllowed} pair is exercised rather than bypassed. What a refusal looks like is the
 * packaged catalogue's business — a {@code @QuarkusTest} cannot observe one.
 */
@QuarkusTest
class ImagePinsApiTest {

  private static final String BASE = "/configuration/api";

  private static final String AGENT_VERSION = "2026.904.160152";

  private static final String WORKSPACE_VERSION = "2026.904.160522";

  private static final String EDITOR_VERSION = "2026.904.100239";

  /** The application of the declared half — this test's own, since a declaration is per application. */
  private static final String DECLARED_APP = "pins-declaring-app";

  private static final String DECLARED_VERSION = "2026.905.1";

  private static final String DECLARED_KEY = "env.QITS_DECLARED_IMAGE_VERSION";

  private static final String DECLARED_IMAGE_VERSION = "2026.905.113000";

  private static final String YAML = "application/yaml";

  /** The env this suite pins in. The report reads every env; these tests use one. */
  private static final String ENV = "test";

  /**
   * RestAssured ships no encoder for {@code application/yaml}, so it is told to encode that type as
   * text — the same line {@code DeclarationsApiTest} carries, and for the same reason: the
   * alternative is posting a content type the shipped {@code @Consumes} does not name.
   */
  private static final RestAssuredConfig YAML_AS_TEXT =
      RestAssuredConfig.config()
          .encoderConfig(EncoderConfig.encoderConfig().encodeContentTypeAs(YAML, ContentType.TEXT));

  /** Every mapping of the map, written. Idempotent, so either test may run first. */
  private void pinEveryImage() {
    put("qits-projects", "env.QITS_PROJECTS_AGENT_IMAGE_VERSION", AGENT_VERSION);
    put("qits-projects", "env.QITS_PROJECTS_REFINEMENT_IMAGE_VERSION", WORKSPACE_VERSION);
  }

  private void put(String application, String key, String value) {
    given()
        .contentType(ContentType.JSON)
        .body(new ConfigurationController.SetEntryRequest(value))
        .when()
        .put(BASE + "/applications/" + application + "/envs/" + ENV + "/entries/" + key)
        .then()
        .statusCode(oneOf(200, 201));
  }

  /**
   * Every mapping is a row, in the order the contract names — image, then application, then key.
   *
   * <p>It was four rows until 2026-09-16, when qits-workspaces stopped taking the workspace and
   * editor image versions from configuration and started pinning them as maven dependencies of its
   * own. Two rows left with it, and the one that still names {@code qits/workspace} is qits-projects'
   * refinement container — which is why the image is still here at all.
   */
  @Test
  void everyConfiguredImageVersionIsARowInTheMapsOrder() {
    pinEveryImage();

    given()
        .when()
        .get(BASE + "/pins")
        .then()
        .statusCode(200)
        // An ISO instant rather than an epoch number: a receipt quotes when it asked.
        .body("generatedAt", endsWith("Z"))
        .body("pins.size()", equalTo(2))
        .body("pins[0].image", equalTo("qits/project-agent"))
        .body("pins[0].version", equalTo(AGENT_VERSION))
        .body("pins[0].application", equalTo("qits-projects"))
        .body("pins[0].key", equalTo("env.QITS_PROJECTS_AGENT_IMAGE_VERSION"))
        .body("pins[1].image", equalTo("qits/workspace"))
        .body("pins[1].version", equalTo(WORKSPACE_VERSION))
        .body("pins[1].application", equalTo("qits-projects"))
        .body("pins[1].key", equalTo("env.QITS_PROJECTS_REFINEMENT_IMAGE_VERSION"))
        // AND NOTHING FOR qits-workspaces. The entries it used to be handed may well still exist —
        // nothing here deletes one — but they are no longer MAPPED, so they are not launchable-by-
        // configuration and the report must not claim them.
        .body("pins.application", everyItem(not(equalTo("qits-workspaces"))));
  }

  /**
   * A mapping with nothing stored is omitted rather than answered with a blank version: the image
   * has never been released into this environment, and a row naming {@code qits/project-agent:}
   * would be a tag that cannot exist.
   *
   * <p>Told against the agent pin since 2026-09-16. It used to be told against the editor's, which
   * was the natural choice while that was the one image most likely to be genuinely unreleased —
   * and the editor has no mapping at all now, so deleting its entry would prove nothing about
   * omission. The property is the mapping's, not any particular image's.
   */
  @Test
  void aMappingWithNothingStoredHasNoRow() {
    pinEveryImage();

    given()
        .when()
        .delete(
            BASE
                + "/applications/qits-projects/envs/"
                + ENV
                + "/entries/env.QITS_PROJECTS_AGENT_IMAGE_VERSION")
        .then()
        .statusCode(204);

    given()
        .when()
        .get(BASE + "/pins")
        .then()
        .statusCode(200)
        .body("pins.size()", equalTo(1))
        .body("pins.image", everyItem(not(equalTo("qits/project-agent"))));

    // Put it back: the other test asserts the whole list, and the suite shares one database.
    put("qits-projects", "env.QITS_PROJECTS_AGENT_IMAGE_VERSION", AGENT_VERSION);
  }

  /**
   * A pin nobody wrote into {@code control/ImagePins}: the application declared the key itself, said
   * which image its version is of, and the report answers for it. This is the path the authored list
   * is being emptied into, and over the wire it is the same four fields.
   *
   * <p>It <b>takes its declaration and its entry away again</b> at the end, for the same reason the
   * omission test puts back what it removed: this suite shares one database across classes, and the
   * two tests above assert a size.
   */
  @Test
  void anImageAnApplicationDeclaredForItselfIsAPinToo() {
    pinEveryImage();

    given()
        .config(YAML_AS_TEXT)
        .contentType(YAML)
        .body(
            """
            keys:
              env.QITS_DECLARED_IMAGE_VERSION:
                type: packageVersion
                package: { type: docker, name: qits/api-declared }
            """)
        .when()
        .post(BASE + "/applications/" + DECLARED_APP + "/declarations/" + DECLARED_VERSION
            + "?deploymentTarget=environment")
        .then()
        .statusCode(oneOf(200, 201));
    put(DECLARED_APP, DECLARED_KEY, DECLARED_IMAGE_VERSION);

    given()
        .when()
        .get(BASE + "/pins")
        .then()
        .statusCode(200)
        .body("pins.size()", equalTo(3))
        // qits/api-declared sorts ahead of every authored image, so the declared row is first — one
        // order over the merged list, not the authored ones followed by the declared ones.
        .body("pins[0].image", equalTo("qits/api-declared"))
        .body("pins[0].version", equalTo(DECLARED_IMAGE_VERSION))
        .body("pins[0].application", equalTo(DECLARED_APP))
        .body("pins[0].key", equalTo(DECLARED_KEY))
        .body("pins[1].image", equalTo("qits/project-agent"));

    given()
        .when()
        .delete(BASE + "/applications/" + DECLARED_APP + "/declarations/" + DECLARED_VERSION)
        .then()
        .statusCode(204);
    given()
        .when()
        .delete(BASE + "/applications/" + DECLARED_APP + "/envs/" + ENV + "/entries/" + DECLARED_KEY)
        .then()
        .statusCode(204);

    given()
        .when()
        .get(BASE + "/pins")
        .then()
        .statusCode(200)
        .body("pins.size()", equalTo(2))
        .body(
            "pins.image",
            everyItem(not(equalTo("qits/api-declared"))));
  }
}
