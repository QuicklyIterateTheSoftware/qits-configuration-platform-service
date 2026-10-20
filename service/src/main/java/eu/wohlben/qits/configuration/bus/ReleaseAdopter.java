package eu.wohlben.qits.configuration.bus;

import eu.wohlben.qits.configuration.control.AdoptionRule;
import eu.wohlben.qits.configuration.control.ConfigurationService;
import eu.wohlben.qits.configuration.control.ImagePins;
import eu.wohlben.qits.configuration.control.ImagePins.Pin;
import eu.wohlben.qits.configuration.control.ReleaseLedger;
import eu.wohlben.qits.configuration.control.ReleaseLedger.Published;
import eu.wohlben.qits.configuration.entity.ConfigurationEntry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * The one place a released version becomes a pin, shared by the two listeners that can each be the
 * second half of an adoption (qits-1472): {@link SoftwareReleaseListener}, when the release is
 * announced after its finalization, and {@link ReleaseFinalizedListener}, when the finalization is
 * announced after the release.
 *
 * <p>{@link AdoptionRule} decides WHICH releases are adopted; {@link ReleaseLedger} holds both halves;
 * this class sequences them and makes the pin write. <b>The write itself is the one the listener has
 * always made</b> — the declared pins plus the authored residual ({@link ImagePins#BY_IMAGE}, docker
 * only), merged, over every env {@link ConfigurationService#pinEnvs} names, through {@link
 * ConfigurationService#upsert} in a transaction of its own — moved here unchanged so both doors run
 * exactly the same code. See {@link SoftwareReleaseListener} for why each of those parts is what it
 * is.
 *
 * <p><b>The order of the steps is the crash story.</b> Record the half that arrived (its own
 * transaction, committed), read the other half (another), write the pins (one transaction each), and
 * only then mark the release adopted. A failure anywhere before the mark leaves the release unadopted
 * and the frame owed, and the retry repeats writes the upsert makes no-ops. Marking first would turn a
 * failed pin write into a release that is adopted on paper and pinned nowhere.
 *
 * <p>Every database failure is left to throw: the listener's claim rolls back and the frame is offered
 * again.
 */
@ApplicationScoped
public class ReleaseAdopter {

  private static final Logger LOG = Logger.getLogger(ReleaseAdopter.class);

  /** Who the revision records as the writer, the way {@code ConfigurationController.actor()} does. */
  static final String ACTOR = SoftwareReleaseListener.ACTOR;

  @Inject ConfigurationService configuration;

  @Inject ReleaseLedger ledger;

  /**
   * A {@code SoftwareRelease} arrived: record it, and adopt it at once when its repository has
   * already finalized at or above its version. Otherwise it waits for {@link #finalized}.
   */
  void published(String eventId, Published release) {
    ledger.recordPublished(release);
    if (!AdoptionRule.adoptable(release.version(), ledger.finalizedVersions(release.repository()))) {
      LOG.infof(
          "SoftwareRelease %s: %s %s %s of %s recorded; it waits for its release to be finalized",
          eventId,
          release.packageType(),
          release.packageName(),
          release.version(),
          release.repository());
      return;
    }
    adopt(eventId, release);
  }

  /**
   * A release request of {@code repository} was FINALIZED at {@code version}: record it, and adopt
   * every recorded release of that repository it clears — the highest per package.
   */
  void finalized(String eventId, String repository, String version) {
    if (!ledger.recordFinalized(repository, version)) {
      LOG.debugf(
          "ReleaseRequestStateChanged %s: %s %s was already recorded finalized; adopting what is"
              + " still owed",
          eventId, repository, version);
    }
    AdoptionRule.Plan plan = AdoptionRule.plan(ledger.unadopted(repository), version);
    for (Published release : plan.apply()) {
      adopt(eventId, release);
    }
    for (Published lower : plan.superseded()) {
      ledger.markAdopted(lower);
      LOG.infof(
          "ReleaseRequestStateChanged %s: %s %s %s is superseded by a higher cleared version and"
              + " moves no pin",
          eventId, lower.packageType(), lower.packageName(), lower.version());
    }
  }

  /** Apply one cleared release's pins, unless that would move them backwards, then mark it. */
  private void adopt(String eventId, Published release) {
    if (AdoptionRule.wouldRewind(
        release.version(), ledger.adoptedVersions(release.packageType(), release.packageName()))) {
      LOG.infof(
          "%s: %s %s %s is not applied — that version or a higher one was already adopted",
          eventId, release.packageType(), release.packageName(), release.version());
      ledger.markAdopted(release);
      return;
    }
    writePins(eventId, release);
    ledger.markAdopted(release);
  }

  /** The pin write, unchanged from when {@link SoftwareReleaseListener} made it on arrival. */
  private void writePins(String eventId, Published release) {
    List<Pin> pins = pinsOf(release);
    if (pins.isEmpty()) {
      LOG.debugf(
          "%s: %s %s, which nothing declares and nothing pins",
          eventId, release.packageType(), release.packageName());
      return;
    }
    List<String> envs = configuration.pinEnvs();
    for (Pin pin : pins) {
      for (String env : envs) {
        ConfigurationEntry entry =
            configuration.upsert(env, pin.application(), pin.key(), release.version(), ACTOR);
        LOG.infof(
            "%s pinned %s=%s (env %s, application %s, revision %d)",
            eventId, pin.key(), release.version(), env, pin.application(), entry.headRevision);
      }
    }
  }

  /**
   * The pins this release moves: what the applications declared, and the authored residual for the
   * pairs no declaration claims — the latter only for a {@code docker} release, because that is what
   * those rows are versions of.
   */
  private List<Pin> pinsOf(Published release) {
    List<Pin> authored =
        ImagePins.DOCKER_TYPE.equals(release.packageType())
            ? ImagePins.BY_IMAGE.getOrDefault(release.packageName(), List.of())
            : List.of();
    return ImagePins.merge(
        configuration.declaredPins(release.packageType(), release.packageName()), authored);
  }
}
