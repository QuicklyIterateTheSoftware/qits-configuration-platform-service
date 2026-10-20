package eu.wohlben.qits.configuration.bus;

import eu.wohlben.qits.configuration.control.AdoptionRule;
import eu.wohlben.qits.configuration.control.ReleaseLedger;
import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * THE GREEN LIGHT: consumes qits-projects' {@code ReleaseRequestStateChanged} and, on {@code
 * FINALIZED}, adopts the releases of that repository it clears (qits-1472).
 *
 * <p>FINALIZATION, not the SCM release and not the publish, is what says a release may be used: it
 * comes after the post-release gates (the publish run green, the deployment active when the tag
 * declares one), and only then is the tag merged into main. {@link SoftwareReleaseListener} records
 * each announced artifact in {@link ReleaseLedger} and adopts it on arrival only when its repository
 * is already finalized at or above it; this listener is the other order — the finalization arriving
 * second — and adopts every recorded, unadopted release of the repository at or below the finalized
 * version, the highest per package ({@link AdoptionRule#plan}). Both make their write through {@link
 * ReleaseAdopter}.
 *
 * <p><b>The join key is (repository, version)</b>: {@code repoId} here is the same qits-projects
 * repository row id {@code SoftwareRelease.repository} carries.
 *
 * <p><b>It acts on {@code FINALIZED} alone, and settles every other state in {@link #selects}.</b>
 * RELEASED is the state the old behaviour adopted at and is exactly what this listener exists not to
 * trust; OBSOLETE is a request whose tag a later one folded in, and that later one's FINALIZED is what
 * clears its artifacts.
 *
 * <p><b>FINALIZED is often announced twice</b> — distinct event ids a few milliseconds apart, 66 of
 * 263 in one measured week — so dedup by event id is not enough: the finalized row is written {@code
 * on conflict do nothing}, and a second pass finds nothing left unadopted.
 *
 * <p>It decodes into a local {@link ReleaseRequestStateChangedPayload} through the eventstream lib's
 * {@link CanonicalJson}, with no qits-projects jar on the classpath — the publisher keeps the event in
 * its own service module, and {@code ForeignEventContractTest} holds the transcription this record is
 * checked against.
 *
 * <p><b>Failure</b>, the same policy as {@link SoftwareReleaseListener}: an unreadable payload, or a
 * FINALIZED naming no repository or no version, is poison — WARN and settle; a database error throws
 * and the frame is retried.
 */
@ApplicationScoped
public class ReleaseFinalizedListener implements QitsDurableEventListener {

  private static final Logger LOG = Logger.getLogger(ReleaseFinalizedListener.class);

  /**
   * The storage key of this consumption — its {@code consumed_event} rows and its {@code
   * consumer_watermark}. A key, not a description: never rename it, and never hand it to a listener
   * that means something else. A new id initializes at the head of the log, which is why this one
   * was chosen once and is pinned as a literal in {@code ForeignEventContractTest}.
   */
  static final String CONSUMER_ID = "configuration.release-finalized";

  /** The one event name this listener wants — qits-projects' {@code ReleaseRequestStateChanged}. */
  static final String RELEASE_REQUEST_STATE_CHANGED = "ReleaseRequestStateChanged";

  /** The one state that clears a release. */
  static final String FINALIZED = "FINALIZED";

  /**
   * The {@code ReleaseRequestStateChanged} payload, as a local record bound by {@link CanonicalJson}.
   * Every component of qits-projects' record bar {@code eventId} (which travels on the envelope) is
   * transcribed — only {@code repoId}, {@code state} and {@code version} are read, the rest is
   * there for the log line and so the contract test can bind the whole wire form. Public so {@code
   * bus/EventWireReflection} and the tests can name it.
   */
  public record ReleaseRequestStateChangedPayload(
      String releaseRequestId,
      String releaseRequestQualifiedId,
      String projectId,
      String repoId,
      String repoName,
      String backingBranch,
      String state,
      String previousState,
      Instant changedAt,
      String mergedSha,
      String version,
      String detail,
      List<String> branches) {}

  @Inject ReleaseAdopter adopter;

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(RELEASE_REQUEST_STATE_CHANGED);
  }

  /**
   * Whether this frame is a FINALIZED naming a repository and a version — decided from the bytes
   * alone, outside any transaction, for the reason {@link SoftwareReleaseListener#selects} gives.
   * Every other state is settled here rather than claimed, which keeps the ledger free of the many
   * state changes that are not this listener's business.
   */
  @Override
  public boolean selects(EventFrame frame) {
    return finalizationOf(frame) != null;
  }

  @Override
  public void onFrame(EventFrame frame) {
    ReleaseRequestStateChangedPayload finalized = finalizationOf(frame);
    if (finalized == null) {
      // Unreachable for the reason SoftwareReleaseListener.onFrame gives.
      return;
    }
    LOG.infof(
        "ReleaseRequestStateChanged %s: %s finalized %s at %s",
        frame.id(),
        finalized.releaseRequestQualifiedId(),
        finalized.repoId(),
        finalized.version());
    adopter.finalized(
        "ReleaseRequestStateChanged " + frame.id(), finalized.repoId(), finalized.version());
  }

  /**
   * The finalization this frame announces, or null when it is not one this listener can act on.
   * Every null is settled: a state other than FINALIZED silently, an unreadable payload or a
   * FINALIZED missing its repository or version at WARN — qits-projects sets {@code repoId} always
   * and {@code version} from RELEASED on, so either missing is a contract failure upstream.
   */
  private static ReleaseRequestStateChangedPayload finalizationOf(EventFrame frame) {
    ReleaseRequestStateChangedPayload changed;
    try {
      changed = CanonicalJson.payloadTo(frame.payload(), ReleaseRequestStateChangedPayload.class);
    } catch (RuntimeException unreadable) {
      LOG.warnf(
          "ReleaseRequestStateChanged %s carried an unreadable payload: %s",
          frame.id(), unreadable.toString());
      return null;
    }
    if (!FINALIZED.equals(changed.state())) {
      return null;
    }
    if (blank(changed.repoId()) || blank(changed.version())) {
      LOG.warnf(
          "ReleaseRequestStateChanged %s finalized %s but names no repository or no version"
              + " (repoId %s, version %s); nothing can be cleared by it",
          frame.id(),
          changed.releaseRequestQualifiedId(),
          changed.repoId(),
          changed.version());
      return null;
    }
    return changed;
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
