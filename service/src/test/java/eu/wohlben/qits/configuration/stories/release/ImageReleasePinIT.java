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
 * <p>An application that starts a container per unit of work has to start the version that was
 * <b>just released</b> — a fact only qits-ci knows, and only at the moment its release pipeline goes
 * green. The alternative — qits-ci reaching into this service on every release — would make a
 * configuration write a synchronous leg of a release and lose it whenever this service was
 * mid-cutover. So the release travels as a {@code SoftwareRelease} on the platform's durable event
 * log, and {@code bus/SoftwareReleaseListener} pages it forward into an ordinary entry with an
 * ordinary revision. The next deployment reads it through the same resolved read every other extra
 * comes through.
 *
 * <p><b>The pinned image in this story is one the application DECLARED for itself</b>, and by
 * 2026-09-17 that is the only kind there is. {@code control/ImagePins.AUTHORED} — the
 * hand-maintained residual — is EMPTY: its four rows left one by one as their consumers stopped
 * taking an image version from configuration and started pinning it as a maven dependency whose
 * version IS the image tag, gated by their own release requests and proven against the daemon by
 * their own tests. qits-workspaces took the workspace and editor rows on 2026-09-16; qits-projects
 * took the refinement container's and then the project agent's on 2026-09-17. So this story walks
 * the path the platform is on rather than the one it is leaving: a consumer joins by saying what it
 * needs, and the pipeline publishes that document a moment before announcing the release.
 *
 * <p><b>Three of the five frames are deliberately ignored, and that is as much of the story as the
 * one that is not.</b> The workspace image and the project-agent image are the departures: both were
 * pinned here until September 2026, both are somebody's pom pin now, and the entries those pins
 * wrote are still in the store because nothing here deletes one — so the releases that used to
 * rewrite them must not. The third is the MAVEN release of the same repository as the pinned image,
 * published moments earlier and carrying a much higher version: a pin is keyed on the package type
 * as well as the name, and a version written from a jar's release would start containers on an image
 * tag that does not exist. It is ordered <b>before</b> the docker frame on purpose — when the pin
 * appears, the frame before it has provably been offered and skipped.
 *
 * <p><b>The direction of the arrow is the point.</b> Nothing pushes into this service: the listener
 * is durable, so the catch-up sweep <i>pulls</i> the log forward from its own watermark — which is
 * what makes a release survive this service being down, restarted or replaced while it happened.
 * The edge in the diagram is therefore {@code qits-configuration -> qits-events}, and it is the only
 * outgoing HTTP arrow in this whole catalogue that is not the startup fetch of the idp's keys.
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

  /**
   * The key the project-agent image USED to be pinned under, kept so the story can assert it is not
   * written.
   *
   * <p>It was the last authored pin on this platform and it left on 2026-09-17: qits-projects takes
   * the qits/project-agent version from {@code eu.wohlben.qits:qits-projects-daemon-protocol} now —
   * whose own version IS the image tag — gated by its own release request and proven against the
   * running daemon by ProjectAgentDaemonPinIT. The entry this listener already wrote is still in
   * every real store, which is exactly why the absence below is asserted against the key rather
   * than against a count.
   */
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
      qits-ci finishes a release of a container image and announces it on the platform's event log.
      qits-configuration is a durable consumer of that log: its catch-up sweep pages the log forward
      from its own watermark, finds the release, and writes the version into its own store as an
      ordinary entry, with an ordinary revision, attributed to the listener that wrote it. The next
      deployment reads it through the same resolved read every other extra comes through, and starts
      its containers on the image that was just released.

      Which key a released image moves is the APPLICATION'S OWN answer. Before it announces the
      release, the pipeline publishes what the application declares — one packageVersion key, naming
      the image it starts from — and that document is what the listener matches the release against.
      Nothing in this service's own list mentions that image, and by September 2026 that list
      mentions nothing at all: a consumer joins the platform by saying what it needs, not by somebody
      remembering to add a mapping here.

      Five releases arrive together and only one of them is pinned by anything, which is as much of
      the story as the one that is. The maven release of the same repository as the pinned image
      carries a far higher version and is left alone, because a pin is keyed on the package type as
      well as the name. The project-agent image, the workspace image and the editor image move
      nothing at all: each was pinned here until September 2026 and each of their applications now
      takes the version from a maven dependency of its own, whose version is the image tag, so its
      own release decides which image it starts and its own tests prove the pair before it ships. The
      entries those pins wrote are still in the store, because nothing here deletes one, and the
      releases that used to rewrite them must not. Pulling rather than being pushed is what makes
      this survive: a release announced while this service was restarting is still read back the next
      time it sweeps.
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
            // The project-agent image, which was the LAST authored pin on this platform until
            // 2026-09-17. qits-projects pins it in its own pom now, so this frame's correct effect
            // is no write at all — asserted below against the key it would have been written under,
            // on an application whose entry for that key is in the store throughout.
            StoryEventBus.softwareRelease(
                "release-project-agent-image",
                "2026-08-29T11:05:00Z",
                PROJECTS,
                AGENT_VERSION,
                "docker",
                "qits/project-agent"),
            // The editor image, whose name opens with the workspace image's. Neither is pinned by
            // anything here any more, so this frame's correct effect — like the two above — is no
            // write at all.
            StoryEventBus.softwareRelease(
                "release-workspace-editor-image",
                "2026-08-29T11:10:00Z",
                WORKSPACES,
                EDITOR_VERSION,
                "docker",
                "qits/workspace-editor"),
            // Immediately BEFORE the pin it shares a name with, on purpose: when that pin lands
            // holding the docker version, this frame has provably been offered to the listener and
            // left alone. A declared coordinate is matched on the package TYPE as well as the name,
            // so the jar of the same repository reaches nothing.
            StoryEventBus.softwareRelease(
                "release-story-declared-maven-jar",
                "2026-08-29T11:15:00Z",
                DECLARING,
                MAVEN_VERSION,
                "maven",
                DECLARED_IMAGE),
            // The image nothing in this service has ever been told about: it is matched by the
            // declaration the pipeline just published and by nothing else. It is announced LAST, so
            // waiting on its pin puts every frame above it provably behind the listener's watermark.
            StoryEventBus.softwareRelease(
                "release-story-declared-image",
                "2026-08-29T11:20:00Z",
                DECLARING,
                DECLARED_VERSION,
                "docker",
                DECLARED_IMAGE)));
    story
        .note("qits-ci announces five releases: four docker images and one jar of the same repository — and only one of them is pinned by anything")
        .as("releases-announced");

    // THE HALF THIS SERVICE WAS NEVER TOLD ABOUT, and since 2026-09-17 the only half there is.
    // Nothing in control/ImagePins names qits/story-declared — nothing in it names anything — so the
    // only reason this release lands anywhere is the document the pipeline published above, which
    // said that this key on this application carries a version of that image.
    //
    // Waiting on it is also what puts the four frames announced BEFORE it provably behind the
    // listener's watermark, which is what makes every assertion below an assertion rather than a
    // race.
    assertEquals(
        DECLARED_VERSION,
        awaitPin(DECLARING, DECLARED_IMAGE_KEY),
        "an application that declared the coordinate must be pinned with nothing written down here");
    story
        .note("the image the application itself declared is pinned — a new consumer needs no edit to this service, and no release of it to carry the number")
        .as("declared-image-pinned");

    // The maven frame sat immediately before it, so it has been offered and skipped by now. This is
    // what says so: the pin is still the DOCKER version, not the jar's much higher one.
    assertEquals(
        DECLARED_VERSION,
        pinOf(DECLARING, DECLARED_IMAGE_KEY),
        "a maven release of the same repository must never move an image pin");
    story
        .note("the jar release of the same repository moved nothing: a pin is keyed on the package type as well as the name")
        .as("maven-release-ignored");

    // THE IMAGE THAT LEFT LAST, and the sharpest of the three frames that move nothing.
    // qits-projects starts a project agent from it and took that version from here until
    // 2026-09-17 — it pins eu.wohlben.qits:qits-projects-daemon-protocol now, whose version IS the
    // tag, gated by its own release request and proven against the daemon by ProjectAgentDaemonPinIT
    // before it ships. So this release must leave the key alone, and the key is one this store has
    // a real entry for in every deployment: a listener that had quietly kept the row would be caught
    // writing it rather than merely failing to.
    assertNull(
        pinOf(PROJECTS, AGENT_IMAGE_KEY),
        "the agent image is pinned by nothing here; qits-projects pins it in its own pom");
    story
        .note("the project agent image's release moves nothing: which image a project's agent starts from is qits-projects' own pom's business now, decided by its release rather than written underneath it")
        .as("agent-release-moves-nothing");

    // The workspace image, which left a day's work earlier with qits-projects' refinement container
    // — and, before that, with qits-workspaces.
    assertNull(
        pinOf(PROJECTS, REFINEMENT_IMAGE_KEY),
        "the workspace image is pinned by nothing here; qits-projects pins it in its own pom");
    story
        .note("the workspace image's release moves nothing either: the refinement container's version came off this list the same way, a few hours before the agent's")
        .as("workspace-release-moves-nothing");

    // …and the editor image, which left first, with qits-workspaces. Its name opens with the
    // workspace image's, which is the collision the whole-name match was written for; neither has a
    // mapping now, so what this frame still proves is the plainer claim — a released image nothing
    // maps writes no entry, asserted against the key it would have been written under.
    assertNull(
        pinOf(WORKSPACES, EDITOR_IMAGE_KEY),
        "the editor image is pinned by nothing here; qits-workspaces pins it in its own pom");
    story
        .note("the editor image's release moves nothing either: every image this service used to pin by hand now comes from its consumer's own pom")
        .as("editor-release-moves-nothing");

    List<Map<String, Object>> revisions =
        StoryIdentities.platformService(given(), DEPLOYER)
            .when()
            .get(StoryTarget.historyPath(DECLARING))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("revisions");
    Map<String, Object> newest = revisions.stream().findFirst().orElseGet(() -> fail("no history"));
    // Read on the DECLARING application rather than on qits-projects, and that is this ticket's
    // whole point rather than a detail: qits-projects receives no writes from this listener any
    // more, so its history would answer with somebody else's revision or with nothing at all, and
    // neither would be an assertion about a pin. The declared image was the last frame announced,
    // so its write is the newest revision here — and it carries the same machine attribution as any
    // other write.
    assertEquals(DECLARED_IMAGE_KEY, newest.get("key"));
    assertEquals(DECLARED_VERSION, newest.get("value"));
    assertEquals(
        LISTENER_ACTOR,
        newest.get("updatedBy"),
        "the write is attributed like any other — to the listener, by name");
    story
        .note("the history records the pin as a revision, attributed to the listener that wrote it")
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
    ReportAssertions.assertStepId(CATEGORY, PINNED_SLUG, "maven-release-ignored");
    // Where `workspace-image-pinned`, `editor-image-pinned`, `refinement-image-pinned` and
    // `agent-image-pinned` were, until the four rows naming those three images left
    // control/ImagePins in September 2026. Three frames are still told and each now says the
    // opposite of what it used to: the image is released and this service writes nothing, because
    // the applications that start containers from it take the version from their own poms.
    ReportAssertions.assertStepId(CATEGORY, PINNED_SLUG, "agent-release-moves-nothing");
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
        "GET " + StoryTarget.historyPath(DECLARING) + " -> 200");
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
    // the three applications it asks about — one it waited on for a pin, two it checks for the
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
