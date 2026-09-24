package eu.wohlben.qits.configuration.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

/**
 * REST round-trips for the configuration boundary.
 *
 * <p>The addresses are the shipped ones — the suite inherits {@code
 * quarkus.rest.path=/configuration/api} from main's application.properties rather than re-declaring
 * it — so a change to the segment fails here rather than in a deployment.
 *
 * <p><b>No test sends an identity header</b>, and that is not a hole: qits-auth-core ships a
 * {@code %test} dev user carrying {@code qits:admin} and {@code qits:system}, so the shipped
 * {@code @RolesAllowed} pair is exercised rather than bypassed. What the suite cannot prove is that
 * the gateway asserts the header; that is the gateway's own contract.
 *
 * <p>Each test names an application of its own. The suite shares one database across classes.
 *
 * <p><b>Every route names its env.</b> {@code /applications/<app>/envs/<env>/…} is the whole
 * surface — the env-less spellings that carried callers across the plane move are gone, and {@link
 * #theEnvLessRoutesAreGone} is the assertion that they stayed gone.
 */
@QuarkusTest
class ConfigurationApiTest {

  private static final String BASE = "/configuration/api";

  /** The env this suite writes in. */
  private static final String ENV = "test";

  /** A second environment, to prove one env's rows are not the other's. */
  private static final String OTHER_ENV = "staging";

  /** A write into this suite's env. */
  private void put(String application, String key, String value, int expected) {
    putIn(ENV, application, key, value, expected);
  }

  /** A write into a named env. */
  private void putIn(String env, String application, String key, String value, int expected) {
    given()
        .contentType(ContentType.JSON)
        .body(new ConfigurationController.SetEntryRequest(value))
        .when()
        .put(BASE + "/applications/" + application + "/envs/" + env + "/entries/" + key)
        .then()
        .statusCode(expected);
  }

  @Test
  void aFirstWriteIs201AndARewriteIs200() {
    put("api-create", "env.QITS_REGISTRY", "localhost:8081", 201);
    put("api-create", "env.QITS_REGISTRY", "localhost:8082", 200);

    given()
        .when()
        .get(BASE + "/applications/api-create/envs/" + ENV + "/entries")
        .then()
        .statusCode(200)
        .body("entries.size()", equalTo(1))
        .body("entries[0].key", equalTo("env.QITS_REGISTRY"))
        .body("entries[0].value", equalTo("localhost:8082"))
        .body("entries[0].entryClass", equalTo("plain"))
        .body("entries[0].revision", greaterThan(0))
        .body("entries[0].updatedBy", notNullValue());
  }

  @Test
  void theResolvedReadCarriesTheFullPropertyNames() {
    put("api-resolve", "env.QITS_A", "one", 201);
    put("api-resolve", "aliases[0]", "api.dev.localhost", 201);

    given()
        .when()
        .get(BASE + "/applications/api-resolve/envs/" + ENV + "/resolved")
        .then()
        .statusCode(200)
        .body("headRevision", greaterThan(0))
        .body(
            "properties.'qits.platform.deployments.extras.api-resolve.env.QITS_A'", equalTo("one"))
        .body(
            "properties.'qits.platform.deployments.extras.api-resolve.aliases[0]'",
            equalTo("api.dev.localhost"));
  }

  @Test
  void anUnconfiguredApplicationResolvesEmptyRatherThan404() {
    given()
        .when()
        .get(BASE + "/applications/api-unconfigured/envs/" + ENV + "/resolved")
        .then()
        .statusCode(200)
        .body("headRevision", equalTo(0))
        .body("properties.size()", equalTo(0));
  }

  @Test
  void aDeleteIs204AndTheValueStaysInTheHistory() {
    put("api-delete", "env.A", "one", 201);

    given()
        .when()
        .delete(BASE + "/applications/api-delete/envs/" + ENV + "/entries/env.A")
        .then()
        .statusCode(204);

    given()
        .when()
        .get(BASE + "/applications/api-delete/envs/" + ENV + "/entries")
        .then()
        .statusCode(200)
        .body("entries.size()", equalTo(0));

    given()
        .when()
        .get(BASE + "/applications/api-delete/envs/" + ENV + "/history")
        .then()
        .statusCode(200)
        .body("revisions.size()", equalTo(2))
        .body("revisions[0].deleted", equalTo(true))
        .body("revisions[0].value", nullValue())
        .body("revisions[1].deleted", equalTo(false))
        .body("revisions[1].value", equalTo("one"));
  }

  @Test
  void deletingWhatIsNotThereIs404WithAMessage() {
    given()
        .when()
        .delete(BASE + "/applications/api-missing/envs/" + ENV + "/entries/env.A")
        .then()
        .statusCode(404)
        .body("message", notNullValue());
  }

  /**
   * The listing is per application, with a row per environment. An application configured in two
   * tiers is ONE entry in {@code applications} carrying TWO rows in its {@code envs} — which is the
   * shape the platform promotion exists to produce, and the one a comparison between tiers can be
   * read off.
   */
  @Test
  void theApplicationListingCarriesOneRowPerEnvironment() {
    put("api-listed", "env.A", "one", 201);
    putIn(OTHER_ENV, "api-listed", "env.A", "one", 201);
    putIn(OTHER_ENV, "api-listed", "env.B", "two", 201);

    given()
        .when()
        .get(BASE + "/applications")
        .then()
        .statusCode(200)
        .body("applications.application", hasItem("api-listed"))
        .body(
            "applications.find { it.application == 'api-listed' }.envs.env",
            equalTo(java.util.List.of(OTHER_ENV, ENV)))
        .body(
            "applications.find { it.application == 'api-listed' }.envs"
                + ".find { it.env == '"
                + ENV
                + "' }.entries",
            equalTo(1))
        .body(
            "applications.find { it.application == 'api-listed' }.envs"
                + ".find { it.env == '"
                + OTHER_ENV
                + "' }.entries",
            equalTo(2))
        .body(
            "applications.find { it.application == 'api-listed' }.envs"
                + ".find { it.env == '"
                + OTHER_ENV
                + "' }.headRevision",
            greaterThan(0));
  }

  /**
   * THE CLAIM THE PLANE FLIP RESTS ON, over the wire: a value written into one env is absent from
   * another, at every route that can be asked. A read that had lost its env predicate would pass a
   * test that only ever looked where it wrote, so each assertion here is a MISS.
   */
  @Test
  void aValueWrittenInOneEnvIsNotVisibleFromAnother() {
    putIn(ENV, "api-envs", "env.A", "from-test", 201);

    given()
        .when()
        .get(BASE + "/applications/api-envs/envs/" + OTHER_ENV + "/entries")
        .then()
        .statusCode(200)
        .body("entries.size()", equalTo(0));

    given()
        .when()
        .get(BASE + "/applications/api-envs/envs/" + OTHER_ENV + "/resolved")
        .then()
        .statusCode(200)
        .body("headRevision", equalTo(0))
        .body("properties.size()", equalTo(0));

    given()
        .when()
        .get(BASE + "/applications/api-envs/envs/" + OTHER_ENV + "/history")
        .then()
        .statusCode(200)
        .body("revisions.size()", equalTo(0));

    given()
        .when()
        .delete(BASE + "/applications/api-envs/envs/" + OTHER_ENV + "/entries/env.A")
        .then()
        .statusCode(404);

    // The same key in the other env is a NEW entry, not an update — 201, not 200.
    putIn(OTHER_ENV, "api-envs", "env.A", "from-staging", 201);

    given()
        .when()
        .get(BASE + "/applications/api-envs/envs/" + ENV + "/entries")
        .then()
        .statusCode(200)
        .body("entries.size()", equalTo(1))
        .body("entries[0].env", equalTo(ENV))
        .body("entries[0].value", equalTo("from-test"));

    given()
        .when()
        .get(BASE + "/applications/api-envs/envs/" + OTHER_ENV + "/entries")
        .then()
        .statusCode(200)
        .body("entries[0].env", equalTo(OTHER_ENV))
        .body("entries[0].value", equalTo("from-staging"));
  }

  /**
   * THE CUTOVER, asserted rather than assumed. The env-less spellings answered for one configured
   * env and are removed; a caller that still asks for one gets a 404 from the router rather than a
   * silent write into whichever env this instance happened to be told about.
   */
  @Test
  void theEnvLessRoutesAreGone() {
    given().when().get(BASE + "/applications/api-legacy/resolved").then().statusCode(404);
    given().when().get(BASE + "/applications/api-legacy/entries").then().statusCode(404);
    given().when().get(BASE + "/applications/api-legacy/history").then().statusCode(404);
    given()
        .when()
        .delete(BASE + "/applications/api-legacy/entries/env.A")
        .then()
        .statusCode(404);
    given()
        .contentType(ContentType.JSON)
        .body(new ConfigurationController.SetEntryRequest("one"))
        .when()
        .put(BASE + "/applications/api-legacy/entries/env.A")
        .then()
        .statusCode(404);
  }

  @Test
  void aRefusedEnvNameIs400AndTheMessageNamesIt() {
    given()
        .when()
        .get(BASE + "/applications/api-envrefuse/envs/Not_An_Env/resolved")
        .then()
        .statusCode(400)
        .body("message", org.hamcrest.Matchers.containsString("Not_An_Env"));
  }

  @Test
  void aRefusedKeyIs400AndTheMessageNamesTheGrammar() {
    given()
        .contentType(ContentType.JSON)
        .body(new ConfigurationController.SetEntryRequest("x"))
        .when()
        .put(BASE + "/applications/api-refuse/envs/" + ENV + "/entries/volumes[0]")
        .then()
        .statusCode(400)
        .body("message", org.hamcrest.Matchers.containsString("mounts"));
  }

  @Test
  void aRefusedApplicationNameIs400OnAReadToo() {
    given()
        .when()
        .get(BASE + "/applications/Not_A_Label/envs/" + ENV + "/resolved")
        .then()
        .statusCode(400)
        .body("message", org.hamcrest.Matchers.containsString("Not_A_Label"));
  }

  @Test
  void aMissingValueIs400RatherThanADeletionInDisguise() {
    given()
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .put(BASE + "/applications/api-novalue/envs/" + ENV + "/entries/env.A")
        .then()
        .statusCode(400)
        .body("message", org.hamcrest.Matchers.containsString("DELETE"));
  }

  // ------------------------------------------------------------ the overlay, over the wire

  /** Record one declaration for {@code application}, the way the pipeline posts it. */
  private void declare(String application, String version, String target, String document) {
    given()
        .config(
            io.restassured.config.RestAssuredConfig.config()
                .encoderConfig(
                    io.restassured.config.EncoderConfig.encoderConfig()
                        .encodeContentTypeAs("application/yaml", ContentType.TEXT)))
        .contentType("application/yaml")
        .body(document)
        .when()
        .post(
            BASE
                + "/applications/"
                + application
                + "/declarations/"
                + version
                + "?deploymentTarget="
                + target)
        .then()
        .statusCode(201);
  }

  /**
   * THE OVERLAY READ, at the address the deployer will use: the same route, one query parameter
   * richer.
   *
   * <p>The shape is unchanged — a flat property map and a head revision — and that is the contract
   * this test exists to hold. The deployer layers the map verbatim as a configuration source, so a
   * per-key envelope would have made every consumer unwrap it, and the flat map is what makes
   * {@code ?version=} an addition rather than a second API.
   */
  @Test
  void theResolvedReadTakesAVersionAndKeepsItsShape() {
    declare("api-bus", "1.0", "platform", "keys: {}\n");
    declare(
        "api-overlay",
        "1.0",
        "environment",
        """
        keys:
          env.QITS_UNSET:
            type: string
            default: from-the-declaration
          env.QITS_SET:
            type: string
            default: from-the-declaration
          env.QITS_BUS_URL:
            type: serviceAddress
            service: api-bus
            port: 8080
        """);
    putIn(ENV, "api-overlay", "env.QITS_SET", "from-the-operator", 201);

    String prefix = "properties.'qits.platform.deployments.extras.api-overlay.";
    given()
        .when()
        .get(BASE + "/applications/api-overlay/envs/" + ENV + "/resolved?version=1.0")
        .then()
        .statusCode(200)
        .body("headRevision", greaterThan(0))
        .body(prefix + "env.QITS_UNSET'", equalTo("from-the-declaration"))
        .body(prefix + "env.QITS_SET'", equalTo("from-the-operator"))
        // The address is rendered against the ENV being read, and nothing else. api-bus declares
        // `platform` — a retired value older senders still state — and gets the same `<env>-` alias
        // every other application gets, because there is only one shape of alias now.
        .body(prefix + "env.QITS_BUS_URL'", equalTo("http://" + ENV + "-api-bus:8080"));

    // Without the parameter the answer is the entries and nothing else. The deployer does not pass
    // a version yet, and this is the assertion that it keeps getting exactly what it gets today.
    given()
        .when()
        .get(BASE + "/applications/api-overlay/envs/" + ENV + "/resolved")
        .then()
        .statusCode(200)
        .body("properties.size()", equalTo(1))
        .body(prefix + "env.QITS_SET'", equalTo("from-the-operator"));
  }

  /**
   * The wire case of the unmigrated estate: {@code ?version=} naming no declaration is a 200 holding
   * the entries, not a 404. The deployer passes the deployed version on every extras read, and it
   * reads this route for applications that have never carried a declaration.
   */
  @Test
  void aResolvedReadForAVersionThatDoesNotExistIsTheEntries() {
    putIn(ENV, "api-noversion", "env.QITS_STORED", "from-the-operator", 201);

    given()
        .when()
        .get(BASE + "/applications/api-noversion/envs/" + ENV + "/resolved?version=9.9")
        .then()
        .statusCode(200)
        .body("properties.size()", equalTo(1))
        .body(
            "properties.'qits.platform.deployments.extras.api-noversion.env.QITS_STORED'",
            equalTo("from-the-operator"));
  }

  @Test
  void anEntryTheDeclarationDoesNotAccountForIsFlaggedOnTheWire() {
    put("api-orphan", "env.QITS_DECLARED", "one", 201);
    put("api-orphan", "env.QITS_STRAY", "two", 201);
    declare(
        "api-orphan", "1.0", "environment", "keys:\n  env.QITS_DECLARED:\n    type: string\n");

    given()
        .when()
        .get(BASE + "/applications/api-orphan/envs/" + ENV + "/entries")
        .then()
        .statusCode(200)
        .body("entries.find { it.key == 'env.QITS_DECLARED' }.orphaned", equalTo(false))
        .body("entries.find { it.key == 'env.QITS_STRAY' }.orphaned", equalTo(true))
        .body("entries.find { it.key == 'env.QITS_STRAY' }.entryClass", equalTo("plain"));
  }

  @Test
  void aKeyThePlatformRendersCannotBeSetByHand() {
    declare("api-guard-bus", "1.0", "platform", "keys: {}\n");
    declare(
        "api-guarded",
        "1.0",
        "environment",
        """
        keys:
          env.QITS_BUS_URL:
            type: serviceAddress
            service: api-guard-bus
            port: 8080
        """);

    given()
        .contentType(ContentType.JSON)
        .body(new ConfigurationController.SetEntryRequest("http://mine:1"))
        .when()
        .put(BASE + "/applications/api-guarded/envs/" + ENV + "/entries/env.QITS_BUS_URL")
        .then()
        .statusCode(400)
        .body("message", org.hamcrest.Matchers.containsString("cannot be set by hand"));
  }
}
