package eu.wohlben.qits.configuration.bus;

import eu.wohlben.qits.configuration.control.ConfigurationService;
import eu.wohlben.qits.configuration.control.ImagePins;
import eu.wohlben.qits.configuration.control.ImagePins.Pin;
import eu.wohlben.qits.configuration.entity.ConfigurationEntry;
import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * The bus end of the platform's pins: consumes qits-ci's {@code SoftwareRelease} and, when the
 * released package is one an application asked to be told about, writes its version into this
 * service's own store as an env entry on that application.
 *
 * <p><b>THE MATCH HAS TWO HALVES AND THE FIRST ONE IS THE APPLICATIONS' OWN.</b> An application
 * declares its {@code packageVersion} keys in {@code .config/qits/configuration.yml}, naming the
 * package each key carries a version of; {@link ConfigurationService#declaredPins} is that question
 * asked backwards — who declared THIS coordinate — restricted to the declaration each application
 * currently stands behind. The second half is {@link ImagePins#BY_IMAGE}, the residual list of pins
 * still carried by hand for consumers that have not declared yet. {@link ImagePins#merge} puts the
 * two together with declarations winning per (application, key), and {@code
 * ConfigurationService.imagePins} reports through the same function — so the pin mechanism and the
 * pin report cannot disagree about which half won.
 *
 * <p><b>A declared coordinate is matched on both strings and is NOT gated on docker.</b> The
 * declaration names its own package type, so {@code binary} matches exactly as {@code docker} does.
 * The docker gate belongs to the authored list alone, whose rows are images by construction and have
 * no type of their own to name — a maven artifact sharing an image's coordinate must never move one
 * of those.
 *
 * <p><b>What these pins replaced.</b> Each one used to be a per-repository CI hop
 * ({@code .config/qits/ci-event-upstream-*.yml}) that caught the same {@code SoftwareRelease},
 * rewrote a version literal into the consuming repository's {@code application.properties}, force-
 * pushed a {@code maintenance/} branch and released that service just to carry a number. The pin does
 * the same follow as one config write, with no release of the consumer at all — the value reaches the
 * container as an env override on its next deploy. The property still holds a committed default, so a
 * clone that has never met this service starts something sensible.
 *
 * <p><b>Why the bus rather than a call.</b> An owning application starts its image per container and
 * needs to start the version that was just released — a fact only qits-ci knows, the moment its
 * release pipeline goes green. The alternative, qits-ci reaching into this service on every release,
 * would make a config write a synchronous leg of a release and lose it whenever this service was
 * mid-cutover. A {@link QitsDurableEventListener} closes that: the release is caught up after a
 * restart, and the write happens exactly once per release the platform can be sure of.
 *
 * <p>It adapts inward the way the platform's other consumers do — an {@link EventFrame} is decoded
 * into a local {@link SoftwareReleasePayload} record by the eventstream lib's {@link CanonicalJson},
 * so this module depends on no qits-ci module at all. The record is registered for native reflection
 * in {@code bus/EventWireReflection}, because {@code CanonicalJson} binds through its own
 * ObjectMapper the build step cannot see.
 *
 * <h2>Why by the wire strings</h2>
 *
 * <p>{@code packageType} and {@code packageName} are compared as the literal wire values qits-ci
 * publishes — the {@code SoftwareRelease} javadoc fixes {@code packageType}'s vocabulary as {@code
 * npm/maven/docker/daemon} — rather than through qits-ci's {@code CiArtifact.Type} enum, which lives
 * in a module this service does not (and should not) depend on. The name travels unqualified, so it
 * is matched unqualified.
 *
 * <p>The name match is exact and <b>whole, never a prefix</b>, on both halves: a map lookup on the
 * authored side, an indexed equality on the declared one. The case that established it was {@code
 * qits/workspace} and {@code qits/workspace-editor}, which share an opening and each had pins of
 * their own, so a release of either would have moved the other's keys under a prefix match. Neither
 * is pinned here any more — both consumers took the version into their own poms in September 2026 —
 * and the rule outlives them: a released name that merely opens with a pinned image's name is a
 * different image, whether or not two images in the list happen to collide this month.
 *
 * <h2>THE FAN-OUT: one write per env, because an entry is an override</h2>
 *
 * <p>A pin is written into <b>every env {@link ConfigurationService#pinEnvs} names</b>, not into one.
 * The store holds every environment's configuration now and an entry is a per-env override with no
 * default row underneath it, so an env the release does not reach is not an env that inherits the
 * old value — it is an env whose container starts on its image's committed default, silently and
 * differently from its siblings.
 *
 * <p><b>The accepted residual: an env born after a release has no row until the next one.</b>
 * Nothing backfills a new tier, because "start whatever was last released" is a decision rather than
 * a fact this listener holds, and the image's own default answers it more conservatively in the
 * meantime. The next release of that package closes the gap by itself.
 *
 * <h2>Last-writer-wins, and why it is safe here</h2>
 *
 * <p>The effect is an idempotent upsert keyed by the event's own fact — the released version — so it
 * needs none of the tip-checking a ladder does. {@link ConfigurationService#upsert} writes no
 * revision when the value is already what it would set, so a redelivery of the same release, live or
 * caught up, changes nothing. The one residual is a catch-up frame arriving after a newer live one
 * could momentarily rewrite an older version; the next release corrects it, and the platform's own
 * ordering makes it rare. Writing through {@code upsert} keeps the revision and audit trail exactly
 * as an operator's edit would.
 *
 * <p><b>It overwrites whatever class the row carries, operator rows included</b>, and that is the
 * decision rather than an oversight: a version pinned by hand holds until the next release of that
 * package and no longer. An always-auto-update pin is what the CI hops it replaced did, and a pin
 * that an operator's edit could freeze forever would be a container left on an old image by a
 * one-off nobody remembers making. Freezing a version is expressed by taking the key out of the
 * declaration, where it is written down.
 *
 * <h2>Failure: what is retried and what is swallowed</h2>
 *
 * <p><b>Retryable, and left to throw:</b> anything the write raises out of its own database work. A
 * store that is down is a condition rather than a verdict, so the claim rolls back and the release
 * stays owed for the next sweep.
 *
 * <p><b>Poison, and swallowed with a WARN:</b> a payload that will not parse, and one that names no
 * version. Neither can succeed on a later offer — the same bytes fail identically every time — and a
 * throw would hold this consumer's watermark behind one bad event forever.
 */
@ApplicationScoped
public class SoftwareReleaseListener implements QitsDurableEventListener {

  private static final Logger LOG = Logger.getLogger(SoftwareReleaseListener.class);

  /**
   * The storage key of this consumption: it names every {@code consumed_event} row and the {@code
   * consumer_watermark} this listener is caught up by. A stable storage key, not a description — its
   * value long predates the second image, and renaming it would reset the watermark and re-consume
   * every past release. It survives a rename of this class, and it is never handed to a listener that
   * means something else.
   *
   * <p><b>It under-describes this listener on purpose, and by a wider margin every wave.</b> It was
   * named for one image; it now covers every declared coordinate on the platform. Reading it as a
   * description and "fixing" it is the one change that cannot be undone by a redeploy: a new id has
   * no watermark, initializes at the head of the log and settles every release in between as though
   * it had been handled. The name is a key. Leave it alone.
   */
  static final String CONSUMER_ID = "configuration.project-agent-image";

  /** The one event name this listener wants — {@code SoftwareRelease}'s signature. */
  static final String SOFTWARE_RELEASE = "SoftwareRelease";

  /** Who the revision records as the writer, the way {@code ConfigurationController.actor()} does. */
  static final String ACTOR = "qits-configuration/software-release-listener";

  /**
   * The {@code SoftwareRelease} payload wire fields this listener reads, as a local record bound by
   * {@link CanonicalJson}. Only the four the match and the pin need — {@code occurredAt} is on the
   * envelope, not the payload, so it is not here. Public so {@code bus/EventWireReflection} and the
   * test can name it; a copy of qits-ci's wire shape rather than a dependency on its module.
   */
  public record SoftwareReleasePayload(
      String repository, String version, String packageType, String packageName) {}

  @Inject ConfigurationService configuration;

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(SOFTWARE_RELEASE);
  }

  /**
   * Whether this frame is a release that COULD pin something — decided from the payload alone, with
   * no store behind it.
   *
   * <p><b>The narrowing this predicate cannot do any more, and why it does not try.</b> Half the
   * match now lives in the declarations table, and this method is the one place a durable listener is
   * asked a question OUTSIDE its transaction: {@code DurableFunnel} calls it before the claim opens
   * one, on a socket callback thread and on the sweeper's, where there is no transaction and no
   * request context for a query to run in. And the failure mode is the worse one — a predicate that
   * throws is a FAILURE rather than a "no", so a store that hiccuped while answering "does anyone
   * declare this" would leave the event owed and the watermark parked behind it until somebody
   * looked.
   *
   * <p>So the coordinate check moved into {@link #onFrame}, inside the claim, and what is paid for it
   * is a claim row per {@code SoftwareRelease} the platform publishes rather than per release this
   * listener acts on — a handful of rows a day, pruned on the same horizon as every other, against a
   * database read in a predicate the bus contract does not offer one in. Not a close call.
   *
   * <p>What it still answers no to is what it can decide from the bytes: an unreadable payload, one
   * naming no package, and one naming no version. Those are settled here rather than claimed and
   * dropped, which keeps the ledger free of frames nothing could ever have been done with.
   */
  @Override
  public boolean selects(EventFrame frame) {
    return payloadOf(frame) != null;
  }

  /**
   * Writes the released version through the service's own write seam, so each entry carries a
   * revision and an author.
   *
   * <p><b>NOTHING HERE TOUCHES THIS SERVICE'S STORE INSIDE THE CLAIM'S TRANSACTION, reads
   * included.</b> The funnel calls this method inside the transaction that writes the claim, and
   * that transaction belongs to the EVENTSTREAM datasource — two non-XA datasources cannot both join
   * one, so a query of the configuration store from in here fails to enlist at all ({@code Exception
   * in association of connection to existing transaction}, measured by {@code ImageReleasePinIT} on
   * 2026-09-07: every frame failed, the claim rolled back, and the release stayed owed forever). The
   * writes always suspended it — {@code DbRetry.inNewTx} — and the two reads the match now needs do
   * the same, which is why they are methods on {@code ConfigurationService} rather than repository
   * calls made here.
   *
   * <p>The price of that suspension is the effect not being atomic with the claim: a crash between
   * two writes leaves some pins landed and the frame still owed, and the retry writes the rest. That
   * is the shape this listener has always had, and the upsert being idempotent is what makes it a
   * non-event.
   *
   * <p><b>One frame is many writes</b>, and they are deliberately not one. Every (application, key)
   * the release moves, in every env this store knows about, is an independent config entry with a
   * revision of its own. A write that throws is retryable and rolls the claim back, which re-offers
   * the frame and repeats the earlier upserts — harmless, because an upsert that would set the value
   * already there writes no revision.
   *
   * <p><b>A frame that matches nothing is a no-op and says so at DEBUG.</b> It was claimed rather
   * than skipped (see {@link #selects}), so this is the ordinary outcome for most releases on the
   * platform and not a condition to report.
   */
  @Override
  public void onFrame(EventFrame frame) {
    SoftwareReleasePayload released = payloadOf(frame);
    if (released == null) {
      // selects() said yes a moment ago on the same immutable frame, so this is unreachable; for a
      // non-selecting frame the funnel never calls onFrame. Cheaper than an assertion.
      return;
    }
    List<Pin> pins = pinsOf(released);
    if (pins.isEmpty()) {
      LOG.debugf(
          "SoftwareRelease %s released %s %s, which nothing declares and nothing pins",
          frame.id(), released.packageType(), released.packageName());
      return;
    }
    List<String> envs = configuration.pinEnvs();
    for (Pin pin : pins) {
      for (String env : envs) {
        ConfigurationEntry entry =
            configuration.upsert(env, pin.application(), pin.key(), released.version(), ACTOR);
        LOG.infof(
            "SoftwareRelease %s pinned %s=%s (env %s, application %s, revision %d)",
            frame.id(),
            pin.key(),
            released.version(),
            env,
            pin.application(),
            entry.headRevision);
      }
    }
  }

  /**
   * The pins this release moves: what the applications declared, and the authored residual for the
   * pairs no declaration claims.
   *
   * <p>The authored half is consulted only for a {@code docker} release, because that is what those
   * rows are versions of; the declared half needs no such gate, since the declaration named its own
   * type and the query matched it.
   */
  private List<Pin> pinsOf(SoftwareReleasePayload released) {
    List<Pin> authored =
        ImagePins.DOCKER_TYPE.equals(released.packageType())
            ? ImagePins.BY_IMAGE.getOrDefault(released.packageName(), List.of())
            : List.of();
    return ImagePins.merge(
        configuration.declaredPins(released.packageType(), released.packageName()), authored);
  }

  /**
   * The release this frame announces, or null when it announces nothing this listener could act on.
   *
   * <p>Asked twice per event — once by {@link #selects}, once by {@link #onFrame} — because a
   * predicate the seam calls separately cannot hand state forward, and one more decode of an
   * already-in-memory string is cheaper than a per-frame cache that could disagree with itself.
   *
   * <p>Every null it returns is POISON rather than a condition: the same bytes decide identically on
   * every later offer, so answering "no" settles the frame instead of wedging the watermark behind
   * it. The two that are worth a person's attention say so at WARN — qits-ci always sends a package
   * and a version, so a release missing either is a contract failure upstream, whether or not this
   * platform would have pinned it.
   */
  private static SoftwareReleasePayload payloadOf(EventFrame frame) {
    SoftwareReleasePayload released;
    try {
      released = CanonicalJson.payloadTo(frame.payload(), SoftwareReleasePayload.class);
    } catch (RuntimeException unreadable) {
      LOG.warnf(
          "SoftwareRelease %s carried an unreadable payload: %s", frame.id(), unreadable.toString());
      return null;
    }
    if (blank(released.packageType()) || blank(released.packageName())) {
      LOG.warnf(
          "SoftwareRelease %s names no package (type %s, name %s); there is nothing to match on",
          frame.id(), released.packageType(), released.packageName());
      return null;
    }
    if (blank(released.version())) {
      LOG.warnf(
          "SoftwareRelease %s released %s %s but names no version; nothing to pin",
          frame.id(), released.packageType(), released.packageName());
      return null;
    }
    return released;
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
