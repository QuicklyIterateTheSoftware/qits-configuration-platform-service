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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The pin report over the wire — the answer qits-artifacts' collector holds against age when it
 * decides which images it may delete.
 *
 * <p><b>Every row in this class is DECLARED, and that is the platform as it now is.</b> {@code
 * control/ImagePins.AUTHORED} was emptied on 2026-09-17 when its last row — {@code qits/project-agent}
 * on qits-projects — left for a pom pin, so the authored half can no longer put a row in this answer
 * and this suite cannot write one. That is the whole point of the generalisation it was emptied
 * into: a pin arrives with its consumer's own declaration instead of with an edit in this
 * repository.
 *
 * <p>The two qits-projects keys are still written by every test, and they are the platform's real
 * ones on purpose: they are the entries the retired pins left behind. Nothing on this platform
 * deletes a configuration entry, so they are in real stores and will be, and what these tests hold
 * is that the report does not claim them.
 *
 * <p>Every test writes what it needs and takes its own declaration away again, because the suite
 * shares one database across classes and each of these asserts a size.
 *
 * <p><b>No test sends an identity header</b>, as in {@code ConfigurationApiTest}: qits-auth-core
 * ships a {@code %test} dev user carrying {@code qits:admin} and {@code qits:system}, so the shipped
 * {@code @RolesAllowed} pair is exercised rather than bypassed. What a refusal looks like is the
 * packaged catalogue's business — a {@code @QuarkusTest} cannot observe one.
 */
@QuarkusTest
class ImagePinsApiTest {

  private static final String BASE = "/configuration/api";

  /** The version of the entry the RETIRED agent pin left behind; the report must not mention it. */
  private static final String AGENT_VERSION = "2026.904.160152";

  /** …and the one the retired refinement pin left behind, for the same reason. */
  private static final String WORKSPACE_VERSION = "2026.904.160522";

  /** The application of the declared half — this test's own, since a declaration is per application. */
  private static final String DECLARED_APP = "pins-declaring-app";

  private static final String DECLARED_VERSION = "2026.905.1";

  private static final String FIRST_KEY = "env.QITS_DECLARED_IMAGE_VERSION";

  private static final String SECOND_KEY = "env.QITS_DECLARED_SIDECAR_VERSION";

  private static final String FIRST_IMAGE_VERSION = "2026.905.113000";

  private static final String SECOND_IMAGE_VERSION = "2026.905.114500";

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

  /**
   * The two entries retired pins left behind, written deliberately and by every test.
   *
   * <p>{@code env.QITS_PROJECTS_REFINEMENT_IMAGE_VERSION} and {@code
   * env.QITS_PROJECTS_AGENT_IMAGE_VERSION} were authored pins until 2026-09-17, when qits-projects
   * started taking both container images' versions from maven dependencies of its own. Nothing here
   * deletes an entry, so the residue is still in real stores — and writing it is what lets the
   * assertions below say the report does not claim it. A report that answered for one would have
   * qits-artifacts protecting a tag on the strength of a value nothing reads.
   */
  private void writeTheRetiredResidue() {
    put("qits-projects", "env.QITS_PROJECTS_AGENT_IMAGE_VERSION", AGENT_VERSION);
    put("qits-projects", "env.QITS_PROJECTS_REFINEMENT_IMAGE_VERSION", WORKSPACE_VERSION);
  }

  /** Two keys on one application, so the report's order is asserted over rows that can be ordered. */
  private void declareTwoImages() {
    given()
        .config(YAML_AS_TEXT)
        .contentType(YAML)
        .body(
            """
            keys:
              env.QITS_DECLARED_IMAGE_VERSION:
                type: packageVersion
                package: { type: docker, name: qits/api-declared }
              env.QITS_DECLARED_SIDECAR_VERSION:
                type: packageVersion
                package: { type: docker, name: qits/api-declared-sidecar }
            """)
        .when()
        .post(
            BASE + "/applications/" + DECLARED_APP + "/declarations/" + DECLARED_VERSION
                + "?deploymentTarget=environment")
        .then()
        .statusCode(oneOf(200, 201));
  }

  /**
   * The declaration and both entries go at the end of every test, whatever it asserted. In
   * {@code @AfterEach} rather than at the foot of each method, because a test that fails mid-way
   * would otherwise leave a row behind and fail the next class as well — which is the kind of
   * cascade that sends a reader looking in the wrong place.
   */
  @AfterEach
  void takeTheDeclarationAway() {
    delete(BASE + "/applications/" + DECLARED_APP + "/declarations/" + DECLARED_VERSION);
    delete(BASE + "/applications/" + DECLARED_APP + "/envs/" + ENV + "/entries/" + FIRST_KEY);
    delete(BASE + "/applications/" + DECLARED_APP + "/envs/" + ENV + "/entries/" + SECOND_KEY);
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

  /** Idempotent teardown: 204 when it was there, 404 when a test never wrote it. */
  private void delete(String path) {
    given().when().delete(path).then().statusCode(oneOf(204, 404));
  }

  /**
   * THE ENTRIES RETIRED PINS LEFT BEHIND ARE NOT REPORTED, and with nothing authored that leaves an
   * EMPTY answer — which the route is documented to serve as an ordinary 200.
   *
   * <p>This is the strongest form of the assertion rather than a weaker one: there is nowhere for a
   * stray row to hide. Both values are in the store while it runs.
   */
  @Test
  void anEntryNoMappingNamesIsNotAPin() {
    writeTheRetiredResidue();

    given()
        .when()
        .get(BASE + "/pins")
        .then()
        .statusCode(200)
        // An ISO instant rather than an epoch number: a receipt quotes when it asked.
        .body("generatedAt", endsWith("Z"))
        .body("pins.size()", equalTo(0));
  }

  /**
   * A pin nobody wrote into {@code control/ImagePins}: the application declared the key itself, said
   * which image its version is of, and the report answers for it. This is the path the authored list
   * was emptied into, and over the wire it is the same four fields.
   *
   * <p>The order — image, then application, then key — is asserted across the two declared rows,
   * which is where it can still be asserted at all now that no authored row shares the answer.
   */
  @Test
  void anImageAnApplicationDeclaredForItselfIsAPin() {
    writeTheRetiredResidue();
    declareTwoImages();
    put(DECLARED_APP, FIRST_KEY, FIRST_IMAGE_VERSION);
    put(DECLARED_APP, SECOND_KEY, SECOND_IMAGE_VERSION);

    given()
        .when()
        .get(BASE + "/pins")
        .then()
        .statusCode(200)
        .body("pins.size()", equalTo(2))
        .body("pins[0].image", equalTo("qits/api-declared"))
        .body("pins[0].version", equalTo(FIRST_IMAGE_VERSION))
        .body("pins[0].application", equalTo(DECLARED_APP))
        .body("pins[0].key", equalTo(FIRST_KEY))
        .body("pins[1].image", equalTo("qits/api-declared-sidecar"))
        .body("pins[1].key", equalTo(SECOND_KEY))
        // And still nothing for the residue, which is in the store throughout. Asserted on the KEY
        // as well as on the size: the size would catch a third row, and this says which row it would
        // have been.
        .body("pins.key", everyItem(not(equalTo("env.QITS_PROJECTS_AGENT_IMAGE_VERSION"))))
        .body("pins.image", everyItem(not(equalTo("qits/project-agent"))));
  }

  /**
   * A mapping with nothing stored is omitted rather than answered with a blank version: the image
   * has never been released into this environment, and a row naming {@code qits/api-declared:} would
   * be a tag that cannot exist.
   */
  @Test
  void aMappingWithNothingStoredHasNoRow() {
    writeTheRetiredResidue();
    declareTwoImages();
    put(DECLARED_APP, FIRST_KEY, FIRST_IMAGE_VERSION);

    given().when().get(BASE + "/pins").then().statusCode(200).body("pins.size()", equalTo(1));

    delete(BASE + "/applications/" + DECLARED_APP + "/envs/" + ENV + "/entries/" + FIRST_KEY);

    given()
        .when()
        .get(BASE + "/pins")
        .then()
        .statusCode(200)
        .body("pins.size()", equalTo(0));
  }
}
