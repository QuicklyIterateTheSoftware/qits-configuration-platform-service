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
   * already holds — qits-projects' project agent — and one naming a pair nothing here authors. So
   * one release writes both pairs and writes each of them ONCE: the first declaration shadows the
   * authored row rather than adding a second write of the same entry, and the authored row is not
   * lost to the other pair being declared.
   *
   * <p>It has been retold twice as the authored list shrank under it, and the property never moved —
   * only the pair standing in for the consumer that has adopted. It was qits-workspaces declaring
   * and qits-projects authored until 2026-09-16; then the workspace image's refinement row until
   * 2026-09-17, when that row left too and took the last image with two pins with it. It is told
   * against {@code qits/project-agent} now because that is the only authored row there is, which is
   * also why the un-shadowed half has to be a declared-only pair: there is no second authored row to
   * leave standing.
   */
  @Test
  void aDeclarationShadowsTheAuthoredPinForItsOwnPairAndOnlyThatOne() {
    CapturingService service =
        new CapturingService()
            .declaring(
                "docker",
                "qits/project-agent",
                "qits-projects",
                "env.QITS_PROJECTS_AGENT_IMAGE_VERSION")
            .declaring(
                "docker", "qits/project-agent", "qits-sandbox", "env.QITS_SANDBOX_IMAGE_VERSION");
    SoftwareReleaseListener listener = listenerWith(service);
    EventFrame frame = frameFor("docker", "qits/project-agent", VERSION);

    listener.onFrame(frame);

    assertEquals(
        2,
        service.writes.size(),
        "the shadowed pair and the purely declared one, each written once — a declaration replaces"
            + " the authored row for its pair rather than joining it");
    assertEquals(
        1,
        service.on("qits-projects", "env.QITS_PROJECTS_AGENT_IMAGE_VERSION").size(),
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
   * THE WORKSPACE IMAGE MOVES NOTHING AT ALL NOW, and this is the test that says so.
   *
   * <p>It moved two pins until 2026-09-16 and one until 2026-09-17. qits-workspaces went first;
   * qits-projects — which starts its refinement containers from the same image — followed, and pins
   * it as a maven dependency whose version IS the image tag
   * ({@code eu.wohlben.qits:qits-workspace-daemon-protocol}), gated by its own release request and
   * proven against the daemon by an integration test. So the version it starts a container from is a
   * reviewed line in its own pom rather than an entry written underneath it by this listener. That
   * write had itself replaced qits-projects-service's {@code ci-event-upstream-workspace-daemon.yml};
   * the pom pin replaces both.
   *
   * <p><b>The absence is asserted, not merely unmentioned</b>, and it is asserted on the application
   * as well as on the count. A write reappearing here is the defect coming back, and it would come
   * back silently: the entry would simply start being rewritten again and the version qits-projects
   * tested would stop being the one it starts.
   */
  @Test
  void workspaceImageReleaseWritesNothing() {
    CapturingService service = new CapturingService();
    SoftwareReleaseListener listener = listenerWith(service);
    EventFrame frame = frameFor("docker", "qits/workspace", VERSION);

    assertTrue(listener.selects(frame), "a well-formed release is claimed and decided inside");
    listener.onFrame(frame);

    assertNull(
        service.on("qits-projects"),
        "qits-projects takes the refinement image's version from its own pom; writing"
            + " env.QITS_PROJECTS_REFINEMENT_IMAGE_VERSION here is the retired defect");
    assertTrue(
        service.writes.isEmpty(),
        () -> "the workspace image is pinned by nothing here; wrote " + service.writes);
  }

  /**
   * The editor image moves nothing either, which is the other half of the same departure.
   *
   * <p>{@code qits/workspace-editor} was the original demonstration that the match is a whole name
   * rather than a prefix: it opens with {@code qits/workspace}, so a prefix match would have handed
   * an editor release the workspace image's pins. Neither image has a mapping any more, so this
   * frame no longer exercises that rule — a prefix implementation would find nothing to match
   * either — and it is kept for what it still proves: a released image nothing maps writes nothing,
   * told against the two names most likely to be added back by mistake. The prefix rule itself is
   * exercised below, against the image that IS still pinned.
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

  /**
   * THE WHOLE-NAME MATCH, held against the one image that is still pinned.
   *
   * <p>A release of {@code qits/project-agent-next} must write nothing: it opens with {@code
   * qits/project-agent}, and a prefix match would hand it that image's pin — an entry pinning the
   * agent's container to a tag from a different image's release, which is exactly how a container
   * ends up starting on a tag that does not exist.
   *
   * <p>The name is synthetic, and has to be: the two real images that shared an opening
   * ({@code qits/workspace} and {@code qits/workspace-editor}) both left this list in September
   * 2026, so asserting against them proves nothing about the lookup any more. The rule outlives the
   * collision that found it, and waiting for two real images to collide again is how a platform
   * rediscovers a defect it has already paid for.
   */
  @Test
  void aReleaseWhoseNameMerelyOpensWithAPinnedImagesWritesNothing() {
    CapturingService service = new CapturingService();
    SoftwareReleaseListener listener = listenerWith(service);

    listener.onFrame(frameFor("docker", "qits/project-agent-next", VERSION));

    assertEquals(
        List.of(),
        service.writes,
        "a name that merely opens with a pinned image's name is not that image");

    // …and the same listener does write for the whole name, so the assertion above is about the
    // match and not about a listener that had stopped writing anything.
    listener.onFrame(frameFor("docker", "qits/project-agent", VERSION));
    Write write = service.only();
    assertNotNull(write);
    assertEquals("env.QITS_PROJECTS_AGENT_IMAGE_VERSION", write.key());
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
