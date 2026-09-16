package eu.wohlben.qits.configuration.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.configuration.control.ConfigurationService;
import eu.wohlben.qits.configuration.control.ImagePins;
import eu.wohlben.qits.configuration.entity.ConfigurationEntry;
import eu.wohlben.qits.eventstream.control.EventFrame;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The listener's decision, in isolation from the bus and the database. A {@link CapturingService}
 * stands in for the store — the declared half of the match, the envs to fan out over, and the write
 * seam — and records what {@link SoftwareReleaseListener#onFrame} asks of it, so the test is about
 * the match rule alone; a {@code @QuarkusTest} would prove the same thing behind a Quarkus boot it
 * does not need.
 *
 * <p><b>The merge is NOT stubbed.</b> {@link ImagePins#merge} is the real one, so the shadowing rule
 * these tests assert is the same code {@code ConfigurationService.imagePins} reports through. A
 * stand-in that decided which half won would be testing the test.
 *
 * <p><b>Every write's env is asserted</b>, because the fan-out is the half of this listener that
 * changed: a pin is an entry, an entry is a per-env override with no default row under it, and an
 * env the release does not reach starts its containers on the image's committed default instead.
 * {@link CapturingService#envs} is what the store would have answered.
 */
class SoftwareReleaseListenerTest {

  /** The one env of a store that knows one — what {@code pinEnvs()} answers for a single-tier platform. */
  private static final String ENV = "test";

  /** …and the second one, for the fan-out. */
  private static final String OTHER_ENV = "prod";

  private static final String ACTOR = "qits-configuration/software-release-listener";

  private static final String VERSION = "2026.822.101500";

  /** One {@code upsert} as the seam received it. */
  private record Write(String env, String application, String key, String value, String actor) {}

  /**
   * A {@link ConfigurationService} that writes nothing, answers the declared half from a canned map,
   * and remembers every call it was handed.
   *
   * <p>Every call, not the last one: one release is a write per (application, key) per env, and a
   * stand-in holding only the newest would let a listener that wrote one of them pass.
   */
  private static final class CapturingService extends ConfigurationService {

    final List<Write> writes = new ArrayList<>();

    /** What the declarations table would answer, by {@code type + " " + name}. */
    final Map<String, List<ImagePins.Pin>> declared = new LinkedHashMap<>();

    /** What the store knows of, and it is asked rather than assumed. */
    List<String> envs = List.of(ENV);

    CapturingService declaring(
        String packageType, String packageName, String application, String key) {
      declared
          .computeIfAbsent(packageType + " " + packageName, coordinate -> new ArrayList<>())
          .add(new ImagePins.Pin(packageName, application, key));
      return this;
    }

    @Override
    public List<ImagePins.Pin> declaredPins(String packageType, String packageName) {
      return declared.getOrDefault(packageType + " " + packageName, List.of());
    }

    @Override
    public List<String> pinEnvs() {
      return envs;
    }

    @Override
    public ConfigurationEntry upsert(
        String env, String application, String key, String value, String actor) {
      writes.add(new Write(env, application, key, value, actor));
      ConfigurationEntry entry = new ConfigurationEntry();
      entry.headRevision = 1;
      return entry;
    }

    /** The single write this frame was expected to make, failing the test when it made several. */
    Write only() {
      assertEquals(1, writes.size(), "exactly one entry is written");
      return writes.get(0);
    }

    /** The write that landed on an application, or null — the assertion for a fan-out frame. */
    Write on(String application) {
      return writes.stream()
          .filter(write -> write.application().equals(application))
          .findFirst()
          .orElse(null);
    }

    /** Every write on one (application, key), whichever env it went to. */
    List<Write> on(String application, String key) {
      return writes.stream()
          .filter(write -> write.application().equals(application) && write.key().equals(key))
          .toList();
    }
  }

  private static EventFrame frameFor(String packageType, String packageName, String version) {
    return frameCarrying(
        "{\"repository\":\"qits-projects\",\"version\":"
            + json(version)
            + ",\"packageType\":"
            + json(packageType)
            + ",\"packageName\":"
            + json(packageName)
            + "}");
  }

  private static EventFrame frameCarrying(String payload) {
    return new EventFrame(
        "evt-1", "SoftwareRelease", Instant.parse("2026-08-22T10:00:00Z"), payload, null, null, null);
  }

  private static String json(String value) {
    return value == null ? "null" : "\"" + value + "\"";
  }

  private SoftwareReleaseListener listenerWith(CapturingService service) {
    SoftwareReleaseListener listener = new SoftwareReleaseListener();
    listener.configuration = service;
    return listener;
  }

  // ------------------------------------------------------------ the declared half

  /**
   * THE MATCH THE APPLICATIONS MAKE THEMSELVES: an application declared a {@code packageVersion} key
   * naming this image, and a release of it lands on that key with nothing written down in {@link
   * ImagePins}. This is the path every consumer moves onto, and the authored list is what is left
   * over until they all have.
   */
  @Test
  void aDeclaredDockerCoordinateIsPinnedWithNothingAuthored() {
    CapturingService service =
        new CapturingService().declaring("docker", "qits/stt", "qits-stt", "env.QITS_STT_VERSION");
    SoftwareReleaseListener listener = listenerWith(service);
    EventFrame frame = frameFor("docker", "qits/stt", VERSION);

    assertTrue(listener.selects(frame), "a well-formed release is claimed and decided inside");
    listener.onFrame(frame);

    Write write = service.only();
    assertEquals(ENV, write.env());
    assertEquals("qits-stt", write.application());
    assertEquals("env.QITS_STT_VERSION", write.key());
    assertEquals(VERSION, write.value());
    assertEquals(ACTOR, write.actor());
  }

  /**
   * A declared coordinate is NOT gated on docker, and this is the case that says so: the declaration
   * named its own package type, so a {@code binary} release matches by the same two strings. The gate
   * belongs to the authored list alone, whose rows are images by construction — see {@link
   * #aNonDockerReleaseNeverMovesAnAuthoredPin}, which is the other side of the same coin.
   */
  @Test
  void aDeclaredCoordinateOfAnotherPackageTypeIsPinnedToo() {
    CapturingService service =
        new CapturingService()
            .declaring("binary", "qits-agent-cli", "qits-projects", "env.QITS_AGENT_CLI_VERSION");
    SoftwareReleaseListener listener = listenerWith(service);
    EventFrame frame = frameFor("binary", "qits-agent-cli", VERSION);

    assertTrue(listener.selects(frame));
    listener.onFrame(frame);

    Write write = service.only();
    assertEquals("qits-projects", write.application());
    assertEquals("env.QITS_AGENT_CLI_VERSION", write.key());
    assertEquals(VERSION, write.value());
  }

  /**
   * THE HALF-ADOPTED CONSUMER, which is the state every consumer passes through.
   *
   * <p>Two declarations for one image: one naming the same (application, key) the authored list
   * already holds — qits-projects' refinement container — and one naming a pair nothing here
   * authors. So one release writes both pairs and writes each of them ONCE: the first declaration
   * shadows the authored row rather than adding a second write of the same entry, and the authored
   * row is not lost to the other pair being declared.
   *
   * <p>It used to be told with qits-workspaces as the declaring half and qits-projects as the
   * authored one, which stopped composing on 2026-09-16: qits-workspaces' rows left the authored
   * list entirely, so there was no longer a shadowed pair and an un-shadowed one among the workspace
   * image's own pins. The property under test did not change — only the pair standing in for the
   * consumer that has adopted.
   */
  @Test
  void aDeclarationShadowsTheAuthoredPinForItsOwnPairAndOnlyThatOne() {
    CapturingService service =
        new CapturingService()
            .declaring(
                "docker",
                "qits/workspace",
                "qits-projects",
                "env.QITS_PROJECTS_REFINEMENT_IMAGE_VERSION")
            .declaring("docker", "qits/workspace", "qits-sandbox", "env.QITS_SANDBOX_IMAGE_VERSION");
    SoftwareReleaseListener listener = listenerWith(service);
    EventFrame frame = frameFor("docker", "qits/workspace", VERSION);

    listener.onFrame(frame);

    assertEquals(
        2,
        service.writes.size(),
        "the shadowed pair and the purely declared one, each written once — a declaration replaces"
            + " the authored row for its pair rather than joining it");
    assertEquals(
        1,
        service.on("qits-projects", "env.QITS_PROJECTS_REFINEMENT_IMAGE_VERSION").size(),
        "the pair both halves name is one entry and one write");
    assertEquals(
        1,
        service.on("qits-sandbox", "env.QITS_SANDBOX_IMAGE_VERSION").size(),
        "the pair only the declaration names must be written too");
  }

  // ------------------------------------------------------------ the fan-out

  /**
   * ONE RELEASE, EVERY ENV. An entry is a per-env override with no default row beneath it, so an env
   * this store knows about and the release does not reach is an env whose containers start on the
   * image's committed default — silently, and differently from its siblings.
   */
  @Test
  void aPinIsWrittenIntoEveryEnvTheStoreKnowsAbout() {
    CapturingService service = new CapturingService();
    service.envs = List.of(ENV, OTHER_ENV);
    SoftwareReleaseListener listener = listenerWith(service);
    EventFrame frame = frameFor("docker", "qits/project-agent", VERSION);

    listener.onFrame(frame);

    List<Write> written =
        service.on("qits-projects", "env.QITS_PROJECTS_AGENT_IMAGE_VERSION");
    assertEquals(2, written.size(), "one pin, one write per env");
    assertEquals(
        List.of(ENV, OTHER_ENV),
        written.stream().map(Write::env).toList(),
        "every env the store named, in the order it named them");
    assertTrue(
        written.stream().allMatch(write -> VERSION.equals(write.value())),
        "the same released version in each of them — a fan-out is not a promotion policy");
  }

  // ------------------------------------------------------------ the authored residual

  @Test
  void anImageNobodyHasDeclaredStillFollowsTheAuthoredList() {
    CapturingService service = new CapturingService();
    SoftwareReleaseListener listener = listenerWith(service);
    EventFrame frame = frameFor("docker", "qits/project-agent", VERSION);

    assertTrue(listener.selects(frame), "the project-agent docker image must be acted on");
    listener.onFrame(frame);

    Write write = service.only();
    assertEquals(ENV, write.env());
    assertEquals("qits-projects", write.application());
    assertEquals("env.QITS_PROJECTS_AGENT_IMAGE_VERSION", write.key());
    assertEquals(VERSION, write.value());
    assertEquals(ACTOR, write.actor());
  }

  /**
   * The workspace image moves ONE pin now: qits-projects' refinement container.
   *
   * <p>It moved two until 2026-09-16 — qits-workspaces read the other one — and that second write is
   * what this ticket removed: qits-workspaces pins the image as a maven dependency whose version is
   * the tag, so the version it starts a container from is a line in its own pom rather than an entry
   * written underneath it. The surviving write replaced qits-projects-service's {@code
   * ci-event-upstream-workspace-daemon.yml}, which used to carry the same follow by rewriting a
   * property and releasing that service.
   *
   * <p><b>The absence is asserted, not merely unmentioned.</b> A write to qits-workspaces reappearing
   * here is the defect coming back, and it would come back silently: the entry would simply start
   * being rewritten again and the version qits-workspaces tested would stop being the one it starts.
   */
  @Test
  void workspaceImageReleaseWritesOnlyTheRefinementPin() {
    CapturingService service = new CapturingService();
    SoftwareReleaseListener listener = listenerWith(service);
    EventFrame frame = frameFor("docker", "qits/workspace", VERSION);

    assertTrue(listener.selects(frame));
    listener.onFrame(frame);

    assertEquals(1, service.writes.size(), "the workspace image moves one pin now, not two");

    Write projects = service.on("qits-projects");
    assertNotNull(projects, "the application that starts a refinement container must be pinned");
    assertEquals(ENV, projects.env());
    // The env override of qits.projects.refinement-image-version, which
    // refinementhost/RefinementContainerFactory reads to compose the image it starts.
    assertEquals("env.QITS_PROJECTS_REFINEMENT_IMAGE_VERSION", projects.key());
    assertEquals(VERSION, projects.value());

    assertNull(
        service.on("qits-workspaces"),
        "qits-workspaces takes this version from its own pom; writing it here is the retired defect");
  }

  /**
   * The editor image moves nothing, and its name is what keeps the whole-name match under test.
   *
   * <p>{@code qits/workspace-editor} opens with {@code qits/workspace}, so a prefix match would hand
   * an editor release the workspace image's pins. It has no mapping of its own since qits-workspaces
   * started pinning it in its pom, which makes this the strongest form of that assertion: the
   * correct answer is no write at all, and a prefix match would produce one.
   */
  @Test
  void workspaceEditorImageReleaseWritesNothing() {
    CapturingService service = new CapturingService();
    SoftwareReleaseListener listener = listenerWith(service);
    EventFrame frame = frameFor("docker", "qits/workspace-editor", VERSION);

    listener.onFrame(frame);

    assertTrue(
        service.writes.isEmpty(),
        () -> "the editor image is pinned by nothing here; wrote " + service.writes);
  }

  // ------------------------------------------------------------ what moves nothing

  /**
   * A release nothing declares and nothing pins is CLAIMED and then does nothing, which is the price
   * of the predicate no longer being able to ask the store — see {@link
   * SoftwareReleaseListener#selects}. What matters is the effect, and the effect is no write.
   */
  @Test
  void aPackageNobodyDeclaresOrPinsWritesNothing() {
    CapturingService service = new CapturingService();
    SoftwareReleaseListener listener = listenerWith(service);
    EventFrame frame = frameFor("docker", "qits/qits-stt", VERSION);

    assertTrue(listener.selects(frame), "a well-formed release is decided inside the claim now");
    listener.onFrame(frame);

    assertEquals(List.of(), service.writes, "no entry is written for a package nothing wants");
  }

  /**
   * Same name, wrong type: the maven artifact of a repository that also publishes an image must not
   * move the image's pin — a version written from a jar's release would start containers on a tag
   * that does not exist. Nothing declares this coordinate, and the authored list is images only.
   */
  @Test
  void aNonDockerReleaseNeverMovesAnAuthoredPin() {
    CapturingService service = new CapturingService();
    SoftwareReleaseListener listener = listenerWith(service);
    EventFrame frame = frameFor("maven", "qits/project-agent", VERSION);

    listener.onFrame(frame);

    assertEquals(List.of(), service.writes, "no entry is written for a non-docker release");
  }

  // ------------------------------------------------------------ poison

  @Test
  void aReleaseNamingNoVersionIsSettledRatherThanClaimed() {
    CapturingService service = new CapturingService();
    SoftwareReleaseListener listener = listenerWith(service);
    EventFrame frame = frameFor("docker", "qits/project-agent", "  ");

    assertFalse(
        listener.selects(frame),
        "the same bytes decide the same way forever, so it is settled rather than owed");
    listener.onFrame(frame);

    assertEquals(List.of(), service.writes, "there is nothing to pin");
  }

  @Test
  void aReleaseNamingNoPackageIsSettledRatherThanClaimed() {
    CapturingService service = new CapturingService();
    SoftwareReleaseListener listener = listenerWith(service);
    EventFrame frame = frameFor("docker", null, VERSION);

    assertFalse(listener.selects(frame), "there is nothing to match a declaration against");
    listener.onFrame(frame);

    assertEquals(List.of(), service.writes);
  }

  /**
   * An unreadable payload answers NO rather than throwing: a predicate that throws is a failure
   * rather than a "no", and the seam keeps offering an event whose predicate throws — which is wrong
   * for one that will read the same bytes and fail identically forever.
   */
  @Test
  void anUnreadablePayloadIsSettledRatherThanThrown() {
    CapturingService service = new CapturingService();
    SoftwareReleaseListener listener = listenerWith(service);
    EventFrame frame = frameCarrying("this is not json");

    assertFalse(listener.selects(frame));
    listener.onFrame(frame);

    assertEquals(List.of(), service.writes);
  }
}
