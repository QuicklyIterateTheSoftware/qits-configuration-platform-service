package eu.wohlben.qits.configuration.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

/**
 * The declaration intake, over the wire, with a body shaped like the file it takes: raw YAML, posted
 * as {@code application/yaml}.
 *
 * <p><b>What this suite CANNOT observe, and it is worth naming rather than faking.</b> The two
 * writes here are {@code @RolesAllowed("qits:system")} and call {@code MachineAuth.require()}, and
 * neither guard can refuse anything inside a {@code @QuarkusTest}: qits-auth-core's {@code %test}
 * dev user carries {@code qits:system}, and the machine gate follows {@code
 * qits.auth.machine.required}, which is false in every posture a clone-alone build has. So what is
 * asserted here is that the shipped annotations ADMIT the traffic they are meant to — a route
 * accidentally locked shut would fail these — and the refusals live where they can be real, against
 * the launched artifact under {@code stories/refusals}. Fabricating an identity with
 * {@code @TestSecurity} would prove something about a caller no deployment produces.
 *
 * <p>Each test names an application of its own; the suite shares one database across classes.
 */
@QuarkusTest
class DeclarationsApiTest {

  private static final String BASE = "/configuration/api";

  private static final String YAML = "application/yaml";

  /**
   * RestAssured ships no encoder for {@code application/yaml} and refuses a body it cannot encode
   * ("Don't know how to encode ... as application/yaml"). Telling it to treat the type as text is
   * what keeps these tests posting the REAL content type a pipeline sends, rather than downgrading
   * every one of them to {@code text/plain} and leaving the shipped {@code @Consumes} untested.
   */
  private static final RestAssuredConfig YAML_AS_TEXT =
      RestAssuredConfig.config()
          .encoderConfig(EncoderConfig.encoderConfig().encodeContentTypeAs(YAML, ContentType.TEXT));

  private static final String DOCUMENT =
      """
      keys:
        env.QITS_GREETING:
          type: string
          default: hello
          description: what it says
        env.QITS_EVENTS_URL:
          type: serviceAddress
          service: decl-bus
          port: 8080
        env.QITS_IMAGE_VERSION:
          type: packageVersion
          package:
            type: docker
            name: qits/workspace
      """;

  private static String declarations(String application) {
    return BASE + "/applications/" + application + "/declarations";
  }

  private static io.restassured.response.Response post(
      String application, String version, String target, String body) {
    return given()
        .config(YAML_AS_TEXT)
        .contentType(YAML)
        .body(body)
        .when()
        .post(declarations(application) + "/" + version + "?deploymentTarget=" + target);
  }

  @Test
  void aFirstDocumentIs201AndTheSameOneAgainIs200() {
    post("decl-intake", "1.0", "environment", DOCUMENT)
        .then()
        .statusCode(201)
        .body("declaration.version", equalTo("1.0"))
        .body("declaration.deploymentTarget", equalTo("environment"))
        .body("declaration.governing", equalTo(true))
        .body("declaration.contentHash", notNullValue())
        .body("declaration.receivedBy", notNullValue())
        .body("declaration.keys.size()", equalTo(3))
        .body("declaration.raw", equalTo(DOCUMENT));

    // A pipeline step that retries is not an event: same bytes, 200, and nothing appended.
    post("decl-intake", "1.0", "environment", DOCUMENT).then().statusCode(200);

    given()
        .when()
        .get(declarations("decl-intake"))
        .then()
        .statusCode(200)
        .body("declarations.size()", equalTo(1))
        .body("declarations[0].version", equalTo("1.0"))
        .body("declarations[0].keys", equalTo(3))
        .body("declarations[0].governing", equalTo(true));
  }

  @Test
  void aDifferentDocumentUnderATakenVersionIs409NamingBothHashes() {
    post("decl-conflict", "1.0", "platform", "keys:\n  env.QITS_A:\n    type: string\n")
        .then()
        .statusCode(201);

    post("decl-conflict", "1.0", "platform", "keys:\n  env.QITS_B:\n    type: string\n")
        .then()
        .statusCode(409)
        .body("message", org.hamcrest.Matchers.containsString("content hash"))
        .body("message", org.hamcrest.Matchers.containsString("hashes to"));
  }

  @Test
  void aDocumentThatWillNotParseIs422NamingTheKey() {
    post("decl-refused", "1.0", "platform", "keys:\n  env.QITS_A:\n    type: integer\n")
        .then()
        .statusCode(422)
        .body("message", org.hamcrest.Matchers.containsString("key env.QITS_A"))
        .body("message", org.hamcrest.Matchers.containsString("unknown type 'integer'"));

    given().when().get(declarations("decl-refused")).then().body("declarations.size()", equalTo(0));
  }

  @Test
  void aMissingOrUnknownDeploymentTargetIs400() {
    given()
        .config(YAML_AS_TEXT)
        .contentType(YAML)
        .body("keys: {}\n")
        .when()
        .post(declarations("decl-target") + "/1.0")
        .then()
        .statusCode(400)
        .body("message", org.hamcrest.Matchers.containsString("deploymentTarget"));

    post("decl-target", "1.0", "singleton", "keys: {}\n").then().statusCode(400);
  }

  @Test
  void aVersionOutsideTheGrammarIs400() {
    post("decl-grammar", "-nope", "platform", "keys: {}\n")
        .then()
        .statusCode(400)
        .body("message", org.hamcrest.Matchers.containsString("declaration version"));
  }

  @Test
  void oneDeclarationComesBackParsedAndVerbatim() {
    post("decl-read", "1.0", "environment", DOCUMENT).then().statusCode(201);

    given()
        .when()
        .get(declarations("decl-read") + "/1.0")
        .then()
        .statusCode(200)
        .body("application", equalTo("decl-read"))
        .body("raw", equalTo(DOCUMENT))
        .body("keys.find { it.key == 'env.QITS_GREETING' }.type", equalTo("string"))
        .body("keys.find { it.key == 'env.QITS_GREETING' }.defaultValue", equalTo("hello"))
        .body("keys.find { it.key == 'env.QITS_EVENTS_URL' }.service", equalTo("decl-bus"))
        .body("keys.find { it.key == 'env.QITS_EVENTS_URL' }.port", equalTo(8080))
        // A rendered address carries no fallback, and the wire says so rather than omitting it.
        .body("keys.find { it.key == 'env.QITS_EVENTS_URL' }.defaultValue", nullValue())
        .body("keys.find { it.key == 'env.QITS_IMAGE_VERSION' }.packageType", equalTo("docker"))
        .body(
            "keys.find { it.key == 'env.QITS_IMAGE_VERSION' }.packageName",
            equalTo("qits/workspace"));
  }

  /**
   * {@code qits:desk} is admitted everywhere {@code qits:admin-agent} is (qits-1596) on the reads
   * this controller keeps paired — not on the two system-only writes above, which never admitted
   * {@code qits:admin-agent} either and stay machine-only: an asserted fact about a build, which the
   * desk has no more business hand-posting than a person does.
   */
  @Test
  void aDeskAloneCanReadDeclarations() {
    post("decl-desk", "1.0", "environment", "keys: {}\n").then().statusCode(201);

    given()
        .header("X-Qits-User", "dyn-desk")
        .header("X-Qits-Roles", "qits:desk")
        .when()
        .get(declarations("decl-desk"))
        .then()
        .statusCode(200)
        .body("declarations.size()", equalTo(1));

    given()
        .header("X-Qits-User", "dyn-desk")
        .header("X-Qits-Roles", "qits:desk")
        .when()
        .get(declarations("decl-desk") + "/1.0")
        .then()
        .statusCode(200)
        .body("application", equalTo("decl-desk"));
  }

  @Test
  void anUnknownDeclarationIs404WithAMessage() {
    given()
        .when()
        .get(declarations("decl-absent") + "/9.9")
        .then()
        .statusCode(404)
        .body("message", notNullValue());
  }

  /**
   * The tag-recovery door: remove a version and the one before it governs again, so a re-cut tag can
   * be re-posted without anybody re-posting the declaration it superseded.
   */
  @Test
  void removingAVersionIs204AndLetsThePreviousOneGovernAgain() {
    post("decl-recut", "1.0", "platform", "keys:\n  env.QITS_A:\n    type: string\n")
        .then()
        .statusCode(201);
    post("decl-recut", "2.0", "platform", "keys:\n  env.QITS_B:\n    type: string\n")
        .then()
        .statusCode(201);

    given().when().delete(declarations("decl-recut") + "/2.0").then().statusCode(204);

    given()
        .when()
        .get(declarations("decl-recut"))
        .then()
        .statusCode(200)
        .body("declarations.version", not(hasItem("2.0")))
        .body("declarations.find { it.version == '1.0' }.governing", equalTo(true));

    // And the re-cut may now be posted with different content under the same version.
    post("decl-recut", "2.0", "platform", "keys:\n  env.QITS_C:\n    type: string\n")
        .then()
        .statusCode(201);

    given().when().delete(declarations("decl-recut") + "/9.9").then().statusCode(404);
  }
}
