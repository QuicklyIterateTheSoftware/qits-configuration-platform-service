package eu.wohlben.qits.configuration.stories.release;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.configuration.api.TokenValidationBootstrapIT;
import eu.wohlben.qits.configuration.stories.bootstrap.ConfigurationImportIT;
import eu.wohlben.qits.configuration.stories.deployment.DeploymentConfigurationIT;
import eu.wohlben.qits.configuration.stories.operator.OperatorEditIT;
import eu.wohlben.qits.configuration.stories.refusals.AccessRefusalIT;
import eu.wohlben.qits.configuration.stories.support.StoryEventBus;
import eu.wohlben.qits.configuration.stories.support.StoryIdentities;
import eu.wohlben.qits.configuration.stories.support.StoryTarget;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.NetworkTaps;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.UserflowRunsAfter;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * <b>The one thing this service goes out and fetches</b> — and the only way a value gets into its
 * store without somebody typing it.
 *
 * <p>qits-projects starts a project agent per unit of work, and has to start the version that was
 * <b>just released</b> — a fact only qits-ci knows, and only at the moment its release pipeline goes
 * green. The alternative — qits-ci reaching into this service on every release — would make a
 * configuration write a synchronous leg of a release and lose it whenever this service was
 * mid-cutover. So the release travels as a {@code SoftwareRelease} on the platform's durable event
 * log, and {@code bus/SoftwareReleaseListener} pages it forward into an ordinary entry with an
 * ordinary revision: {@code env.QITS_PROJECTS_AGENT_IMAGE_VERSION} on {@code qits-projects}. The
 * next deployment reads it through the same resolved read every other extra comes through.
 *
 * <p><b>The workspace image used to be half of this story and is deliberately no longer in it at
 * all.</b> Two applications took its version from entries this listener wrote: qits-workspaces,
 * which starts a workspace from it, and qits-projects, which starts a refinement container from the
 * same image. Both moved the same way and within a day of each other — qits-workspaces on
 * 2026-09-16, qits-projects on 2026-09-17 — to a maven dependency whose version IS the image tag,
 * so each one's own release decides which image it starts, gated by its own tests. What that leaves
 * here is the frame that moves nothing, which the story keeps and asserts in both its shapes: a
 * released image no mapping names must write no entry, and an entry the store still holds from a
 * mapping that has gone must not be rewritten by the release that used to move it.
 *
 * <p><b>The direction of the arrow is the point.</b> Nothing pushes into this service: the listener
 * is durable, so the catch-up sweep <i>pulls</i> the log forward from its own watermark — which is
 * what makes a release survive this service being down, restarted or replaced while it happened.
 * The edge in the diagram is therefore {@code qits-configuration -> qits-events}, and it is the only
 * outgoing HTTP arrow in this whole catalogue that is not the startup fetch of the idp's keys.
 *
 * <p><b>And three of the five frames are deliberately ignored.</b> A {@code SoftwareRelease} is acted
 * on only when some application declared its coordinate or {@code control/ImagePins} names the image.
 * Two of the three are the images that left; the third is the maven release of the same repository
 * as the pinned image, published moments earlier and carrying a much higher version, which must not
 * touch the pin — a version this service wrote from a jar's release would start containers on an
 * image tag that does not exist. It is ordered <b>before</b> the project-agent frame on purpose:
 * when the pin appears, the frame before it has provably been offered and skipped.
 *
 * <p><b>The last frame is the one nothing here was ever told about.</b> {@code qits/story-declared}
 * is in no list in this repository; it is pinned because the application declared the key itself and
 * the pipeline published that document a moment before announcing the release. That is the direction
 * the platform is moving in — a consumer arrives with its own statement of what it needs — and this
 * is the story that walks it end to end, over the same bus and into the same entry with the same
 * revision.
 *
 * <p>The far side is {@code stories.support.StoryEventBus}, which serves the log's list route and
 * records what was read — see it for why an empty poll is not an arrow.
 */
@QuarkusIntegrationTest
@TestProfile(TokenValidationBootstrapIT.PackagedWithMockIdp.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ImageReleasePinIT {

  static final String CATEGORY = "release";

  static final String PINNED_SLUG = "a-released-image-becomes-what-the-next-container-starts-with";

  /** The reader of the pin, and the reason it is written at all. */
  static final String DEPLOYER = "qits-platform-deployments";

  /**
   * The application the retired workspace and editor pins used to land on. It is in this story only
   * so the frames that must move nothing can be asserted against the keys they would have been
   * written under.
   */
  static final String WORKSPACES = "qits-workspaces";

  static final String PROJECTS = "qits-projects";

  /** …and the env-var key the deployer expands into the project-agent's container. */
  static final String AGENT_IMAGE_KEY = "env.QITS_PROJECTS_AGENT_IMAGE_VERSION";

  /**
   * The key the editor image USED to be pinned under, kept so the story can assert it is not written.
   *
   * <p>Until 2026-09-16 this was a pin of its own on qits-workspaces. It is named here for the
   * opposite reason now: a released image whose key nothing maps must leave no entry, and asserting
   * that against the key it would have been written under is stronger than asserting nothing
   * anywhere. {@code env.QITS_WORKSPACE_IMAGE_VERSION} left in the same wave and is not named at all
   * — the key below covers the workspace image's departure on the application that held it longest.
   */
  static final String EDITOR_IMAGE_KEY = "env.QITS_EDITOR_IMAGE_VERSION";

  /**
   * The key the workspace image USED to be pinned under on qits-projects, kept for the same reason
   * the editor's is: so the story can assert it is not written.
   *
   * <p>qits-projects starts a refinement container from the workspace image, and until 2026-09-17
   * this listener wrote that version here — the last reason {@code qits/workspace} was in {@code
   * control/ImagePins} at all. It pins the image as a maven dependency now, whose version is the
   * tag, so the release that used to move this key must move nothing. This is the sharper of the two
   * absences: the editor's key was never written in this run, while THIS one names a mapping that
   * existed, on an application this story does pin under another key — so a listener that had
   * quietly kept the row would be caught writing it rather than merely failing to.
   */
  static final String REFINEMENT_IMAGE_KEY = "env.QITS_PROJECTS_REFINEMENT_IMAGE_VERSION";

  /** The versions this story releases. Authored literals: a value is not a path and is not scrubbed. */
  static final String WORKSPACE_VERSION = "2026.829.110000";

  static final String AGENT_VERSION = "2026.829.111000";

  static final String EDITOR_VERSION = "2026.829.111500";

  /** The version the maven release carries, and which must never reach a pin. */
  static final String MAVEN_VERSION = "9999.1.1";

  /**
   * THE APPLICATION THAT ASKS FOR ITSELF: it declares a {@code packageVersion} key naming its own
   * image, and there is no row for it in {@code control/ImagePins} — which is the point.
   */
  static final String DECLARING = "story-declaring-app";

  /** The tag its declaration is published under. A literal, like every name in this catalogue. */
  static final String DECLARED_TAG = "1.0";

  /** The image that declaration names, and the key it carries the version of. */
  static final String DECLARED_IMAGE = "qits/story-declared";

  static final String DECLARED_IMAGE_KEY = "env.QITS_STORY_DECLARED_IMAGE_VERSION";

  static final String DECLARED_VERSION = "2026.829.112000";

  /** Who publishes a declaration: the pipeline that built the release, with a machine identity. */
  static final String PIPELINE = "qits-ci";

  /** The document, in the grammar `control/DeclarationParser` is the estate's one parser of. */
  static final String DECLARATION =
      """
      keys:
        env.QITS_STORY_DECLARED_IMAGE_VERSION:
          type: packageVersion
          description: the image a story-declaring container starts from
          package:
            type: docker
            name: qits/story-declared
      """;

  private static final String YAML = "application/yaml";

  /**
   * RestAssured ships no encoder for {@code application/yaml} and refuses a body it cannot encode, so
   * it is told to encode that type as text — the same line {@code DeclarationsApiTest} carries. The
   * request still sends the content type the shipped {@code @Consumes} names, which is the point.
   */
  private static final RestAssuredConfig YAML_AS_TEXT =
      RestAssuredConfig.config()
          .encoderConfig(EncoderConfig.encoderConfig().encodeContentTypeAs(YAML, ContentType.TEXT));

  /** Who the listener records as the writer — a machine, and it says which one. */
  static final String LISTENER_ACTOR = "qits-configuration/software-release-listener";

  /** How long a pin may take to arrive. Generous: the sweep's cadence is two seconds. */
  private static final Duration PATIENCE = Duration.ofSeconds(60);

  /** Kept so {@code @AfterAll} can assert the deployer's bearer is not in the published bundle. */
  private static String deployerBearer;

  /** The inbound tap, once. The framework's own, idempotent per service. */
  @BeforeAll
  static void tapWhatAStorySends() {
    NetworkTaps.restAssured(StoryTarget.SERVICE);
  }

  /**
   * The far-side floor. Everything the catch-up sweep polled while the process was booting — and
   * everything it polled during the four story classes before this one — is below it, which is what
   * lets this story's own arrow be the one that carried a release.
   */
  @BeforeEach
  void floorTheEventLogRecording() {
    StoryEventBus.install();
  }

  @UserStory(
      value = "A released image becomes what the next container starts with",
      category = CATEGORY)
  @UserStoryDescription(
      """
      qits-ci finishes a release of the project-agent image and announces it on the platform's event
      log. qits-configuration is a durable consumer of that log: its catch-up sweep pages the log
      forward from its own watermark, finds the release, and writes the version into its own store
      as env.QITS_PROJECTS_AGENT_IMAGE_VERSION on qits-projects — an ordinary entry, with an ordinary
      revision, attributed to the listener that wrote it. The next deployment of qits-projects
      reads it through the same resolved read every other extra comes through, and starts its
      containers on the image that was just released.

      Which key a released image moves is answered twice over, and the first answer is the
      application's own. Before it announces the release, the pipeline publishes what the application
      declares — one packageVersion key, naming the image it starts from — and that document is what
      the listener matches the release against. Nothing in this service's own list mentions that
      image, and nothing has to: a consumer joins the platform by saying what it needs, not by
      somebody remembering to add a mapping here. The hand-maintained list is what is left over for
      the applications that have not declared yet, and a declaration wins wherever both name the same
      key.

      Five releases arrive together and only two of them are pinned by anything, which is as much of
      the story as the two that are. The maven release of the same repository as the project-agent
      image carries a far higher version and is left alone, because a pin is keyed on the docker
      package name and nothing else. The workspace image and the editor image move nothing at all:
      both were pinned here until September 2026 — the editor and a workspace on qits-workspaces, a
      refinement container on qits-projects — and each of those applications now takes the version
      from a maven dependency of its own, whose version is the image tag, so its own release decides
      which image it starts and its own tests prove the pair before it ships. The entries those pins
      wrote are still in the store, because nothing here deletes one, and the releases that used to
      rewrite them must not. Pulling rather than being pushed is what makes this survive: a release
      announced while this service was restarting is still read back the next time it sweeps.
      """)
  @UserflowRunsAfter({
    TokenValidationBootstrapIT.class,
    ConfigurationImportIT.class,
    DeploymentConfigurationIT.class,
    OperatorEditIT.class,
    AccessRefusalIT.class
  })
  @Order(1)
  void aReleasedImageIsPinnedForTheNextDeployment(Interactions story) {
    NetworkCapture.actor(DEPLOYER);
    deployerBearer = StoryIdentities.platformToken(DEPLOYER);

    // THE DECLARATION COMES FIRST, and the order is the story rather than the setup. A release is
    // matched against what applications have declared AT THE MOMENT IT IS CONSUMED, so a pipeline
    // publishes the document its build produced and then announces the release — which is the order
    // it does them in anyway, both being steps of the same run. A release consumed before its
    // declaration landed would match nothing and be settled forever, and the next release is what
    // repairs that.
    NetworkCapture.actor(PIPELINE);
    StoryIdentities.platformService(given(), PIPELINE)
        .config(YAML_AS_TEXT)
        .contentType(YAML)
        .body(DECLARATION)
        .when()
        .post(StoryTarget.declarationPath(DECLARING, DECLARED_TAG) + "?deploymentTarget=environment")
        .then()
        .statusCode(201);
    story
        .note("the pipeline publishes what its application declares: one key, carrying a version of one image")
        .as("declaration-published");
    NetworkCapture.actor(DEPLOYER);

    StoryEventBus.arm(
        List.of(
            StoryEventBus.softwareRelease(
                "release-workspace-image",
                "2026-08-29T11:00:00Z",
                WORKSPACES,
                WORKSPACE_VERSION,
                "docker",
                "qits/workspace"),
            // Immediately BEFORE the pin it shares a name with, on purpose: when that pin lands
            // holding the docker version, this frame has provably been offered to the listener and
            // left alone. It names qits/project-agent since 2026-09-17 — it used to be the maven
            // jar of qits/workspace, and that image has no pin left for a jar to threaten.
            StoryEventBus.softwareRelease(
                "release-project-agent-maven-jar",
                "2026-08-29T11:05:00Z",
                PROJECTS,
                MAVEN_VERSION,
                "maven",
                "qits/project-agent"),
            StoryEventBus.softwareRelease(
                "release-project-agent-image",
                "2026-08-29T11:10:00Z",
                PROJECTS,
                AGENT_VERSION,
                "docker",
                "qits/project-agent"),
            // The editor image, whose name opens with the workspace image's. Neither is pinned by
            // anything here any more, so this frame's correct effect — like the workspace frame's —
            // is no write at all.
            StoryEventBus.softwareRelease(
                "release-workspace-editor-image",
                "2026-08-29T11:15:00Z",
                WORKSPACES,
                EDITOR_VERSION,
                "docker",
                "qits/workspace-editor"),
            // The image nothing in this service has ever been told about: it is matched by the
            // declaration the pipeline just published and by nothing else.
            StoryEventBus.softwareRelease(
                "release-story-declared-image",
                "2026-08-29T11:20:00Z",
                DECLARING,
                DECLARED_VERSION,
                "docker",
                DECLARED_IMAGE)));
    story
        .note("qits-ci announces five releases: four docker images and one jar of the same repository — and only two of them are pinned by anything")
        .as("releases-announced");

    // The one authored pin left. Waiting on it is also what puts the two frames announced BEFORE it
    // — the workspace image and the maven jar — provably behind the listener's watermark, which is
    // what makes the assertions below about them assertions rather than a race.
    assertEquals(
        AGENT_VERSION,
        awaitPin(PROJECTS, AGENT_IMAGE_KEY),
        "the project-agent image's version must reach the application that starts it");
    story
        .note("the project agent image's version reaches qits-projects, which starts a container per unit of work from it — with no release of qits-projects to carry the number")
        .as("agent-image-pinned");

    // The maven frame sat immediately before it, so it has been offered and skipped by now. This is
    // what says so: the pin is still the DOCKER version, not the jar's much higher one.
    assertEquals(
        AGENT_VERSION,
        pinOf(PROJECTS, AGENT_IMAGE_KEY),
        "a maven release of the same repository must never move an image pin");
    story
        .note("the jar release of the same repository moved nothing: a pin is keyed on the image")
        .as("maven-release-ignored");

    // THE IMAGE THIS SERVICE NO LONGER PINS AT ALL, and the sharper of the two frames that say so.
    // qits-projects starts a refinement container from the workspace image and took that version
    // from here until 2026-09-17 — it pins eu.wohlben.qits:qits-workspace-daemon-protocol now, whose
    // version IS the tag, gated by its own release request and proven against the daemon before it
    // ships. So this release must leave the key alone. The application is one this story DOES pin
    // under another key, and the agent pin above has already landed on it, so a listener that had
    // kept the row would be caught having written it rather than merely not having.
    assertNull(
        pinOf(PROJECTS, REFINEMENT_IMAGE_KEY),
        "the workspace image is pinned by nothing here; qits-projects pins it in its own pom");
    story
        .note("the workspace image's release moves nothing at all: the refinement container's version is qits-projects' own pom's business now, decided by its release rather than written underneath it")
        .as("workspace-release-moves-nothing");

    // THE HALF THIS SERVICE WAS NEVER TOLD ABOUT. Nothing in control/ImagePins names
    // qits/story-declared; the only reason this release lands anywhere is the document the pipeline
    // published above, which said that this key on this application carries a version of that image.
    assertEquals(
        DECLARED_VERSION,
        awaitPin(DECLARING, DECLARED_IMAGE_KEY),
        "an application that declared the coordinate must be pinned with nothing written down here");
    story
        .note("the image only the application itself declared is pinned too — a new consumer needs no edit to this service")
        .as("declared-image-pinned");

    // …and the editor image, which left a day earlier than the workspace one, with qits-workspaces.
    // Its name opens with the workspace image's, which is the collision the whole-name match was
    // written for; neither has a mapping now, so what this frame still proves is the plainer claim —
    // a released image nothing maps writes no entry, asserted against the key it would have been
    // written under.
    //
    // ASSERTED LAST BECAUSE IT WAS ANNOUNCED LAST BUT ONE. This frame sits after the project-agent
    // release on the log, so the agent pin landing says nothing about whether it has been offered
    // yet; the DECLARED pin above was announced after it, and waiting on that is what turns this
    // from a race that always passes into an assertion.
    assertNull(
        pinOf(WORKSPACES, EDITOR_IMAGE_KEY),
        "the editor image is pinned by nothing here; qits-workspaces pins it in its own pom");
    story
        .note("the editor image's release moves nothing either: both halves of the image pair this service used to pin now come from their consumers' own poms")
        .as("editor-release-moves-nothing");

    List<Map<String, Object>> revisions =
        StoryIdentities.platformService(given(), DEPLOYER)
            .when()
            .get(StoryTarget.historyPath(PROJECTS))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("revisions");
    Map<String, Object> newest = revisions.stream().findFirst().orElseGet(() -> fail("no history"));
    // The project-agent image was the last pin paged forward onto qits-projects, so it is the newest
    // revision there — and it carries the same machine attribution as any other write. Read on
    // qits-projects because qits-workspaces receives no writes from this listener any more, which is
    // this ticket's whole point and would make an empty history the wrong thing to assert against.
    assertEquals(AGENT_IMAGE_KEY, newest.get("key"));
    assertEquals(AGENT_VERSION, newest.get("value"));
    assertEquals(
        LISTENER_ACTOR,
        newest.get("updatedBy"),
        "the write is attributed like any other — to the listener, by name");
    story
        .note("the history records the newest pin — the project agent image — as a revision, attributed to the listener that wrote it")
        .as("pin-attributed");
  }

  /**
   * The resolved read, repeated until the pin is there.
   *
   * <p>Every poll is the same request with the same answer status, so the tap draws exactly one
   * arrow for all of them — an edge is a dependency, and asking the same question twice does not
   * make two of them. Waiting is honest here rather than a workaround: the sweep is a timer, and the
   * story's claim is that the release arrives, not that it arrives instantly.
   */
  private static String awaitPin(String application, String key) {
    long deadline = System.nanoTime() + PATIENCE.toNanos();
    String value = null;
    while (System.nanoTime() < deadline) {
      value = pinOf(application, key);
      if (value != null) {
        return value;
      }
      try {
        Thread.sleep(500);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    assertNotNull(
        value,
        "the release never reached " + application + "." + key + " — the catch-up sweep found nothing");
    return value;
  }

  /** One pinned value as the deployer reads it, or null while the release has not arrived. */
  private static String pinOf(String application, String key) {
    JsonPath resolved =
        given()
            .header("Authorization", "Bearer " + deployerBearer)
            .when()
            .get(StoryTarget.resolvedPath(application))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    Map<String, String> properties = resolved.getMap("properties");
    return properties.get("qits.platform.deployments.extras." + application + "." + key);
  }

  @AfterAll
  static void theStoryReportIsComplete() {
    ReportAssertions.assertComplete(CATEGORY, PINNED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, PINNED_SLUG, "releases-announced");
    ReportAssertions.assertStepId(CATEGORY, PINNED_SLUG, "agent-image-pinned");
    ReportAssertions.assertStepId(CATEGORY, PINNED_SLUG, "maven-release-ignored");
    // Where `workspace-image-pinned`, `editor-image-pinned` and `refinement-image-pinned` were,
    // until the three rows naming those two images left control/ImagePins in September 2026. Two
    // frames are still told and both now say the opposite of what they used to: the image is
    // released and this service writes nothing, because the applications that start containers from
    // it take the version from their own poms.
    ReportAssertions.assertStepId(CATEGORY, PINNED_SLUG, "workspace-release-moves-nothing");
    ReportAssertions.assertStepId(CATEGORY, PINNED_SLUG, "editor-release-moves-nothing");
    ReportAssertions.assertStepId(CATEGORY, PINNED_SLUG, "declaration-published");
    ReportAssertions.assertStepId(CATEGORY, PINNED_SLUG, "declared-image-pinned");
    ReportAssertions.assertStepId(CATEGORY, PINNED_SLUG, "pin-attributed");

    // THE ONE OUTGOING ARROW. This service pages the platform's event log forward from its own
    // watermark; it is not called by qits-ci and it calls qits-ci about nothing.
    ReportAssertions.assertEdge(
        CATEGORY,
        PINNED_SLUG,
        NetworkEdge.HTTP,
        StoryTarget.SERVICE,
        StoryEventBus.SERVICE_NAME,
        StoryEventBus.CATCHUP_LABEL);
    ReportAssertions.assertEdge(
        CATEGORY,
        PINNED_SLUG,
        NetworkEdge.HTTP,
        DEPLOYER,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.resolvedPath(WORKSPACES) + " -> 200");
    ReportAssertions.assertEdge(
        CATEGORY,
        PINNED_SLUG,
        NetworkEdge.HTTP,
        DEPLOYER,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.resolvedPath(PROJECTS) + " -> 200");
    ReportAssertions.assertEdge(
        CATEGORY,
        PINNED_SLUG,
        NetworkEdge.HTTP,
        DEPLOYER,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.historyPath(PROJECTS) + " -> 200");
    ReportAssertions.assertEdge(
        CATEGORY,
        PINNED_SLUG,
        NetworkEdge.HTTP,
        DEPLOYER,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.resolvedPath(DECLARING) + " -> 200");
    // The only arrow in this story that is not a read: a machine asserting what its build declares.
    // The query the request carried (?deploymentTarget=) is not in the label — the tap draws paths.
    ReportAssertions.assertEdge(
        CATEGORY,
        PINNED_SLUG,
        NetworkEdge.HTTP,
        PIPELINE,
        StoryTarget.SERVICE,
        "POST " + StoryTarget.declarationPath(DECLARING, DECLARED_TAG) + " -> 201");
    // Six arrows and no seventh, however many times the story polled: the resolved read of each of
    // the three applications it asks about — two it waited on for a pin, one it checks for the
    // absence of one — the history it checked, the declaration the pipeline published, and the one
    // page of the log that carried the releases. Two actors, because a declaration is a machine's
    // assertion about a build and a resolved read is the deployer's — no person is on this path at
    // all.
    ReportAssertions.assertEdgeCount(CATEGORY, PINNED_SLUG, 6);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY, PINNED_SLUG, List.of(DEPLOYER, PIPELINE, StoryTarget.SERVICE));
    ReportAssertions.assertNotLeaked(CATEGORY, PINNED_SLUG, deployerBearer);
  }
}
