package eu.wohlben.qits.configuration.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.configuration.bus.SoftwareReleaseListenerTest.CapturingService;
import eu.wohlben.qits.configuration.bus.SoftwareReleaseListenerTest.Write;
import eu.wohlben.qits.configuration.control.ReleaseLedger.Published;
import eu.wohlben.qits.eventstream.control.EventFrame;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * THE GATE (qits-1472): a released version moves a pin only once its release is FINALIZED, in
 * either arrival order, and never backwards.
 *
 * <p>Both listeners are driven with real frames and share one {@link InMemoryReleaseLedger} and one
 * {@link CapturingService}, exactly as the two durable consumers share one store in a running
 * service. The decision is {@code control/AdoptionRule}, run for real; the ledger is storage only.
 *
 * <p>Every case here is a shape the live log produced in October 2026: a deployed service's {@code
 * SoftwareRelease} 71 s before its FINALIZED, a library's in either order 200 ms apart, FINALIZED
 * announced twice, and requests that went OBSOLETE with their artifacts announced anyway.
 */
class ReleaseAdoptionTest {

  private static final String REPO = "8c0f5b8e-repo-of-qits-stt";

  private static final String OTHER_REPO = "1d2e3f40-another-repo";

  private static final String IMAGE = "qits/stt";

  private static final String APP = "qits-stt";

  private static final String KEY = "env.QITS_STT_VERSION";

  private static final String V1 = "2026.1019.81701";

  private static final String V2 = "2026.1019.131105";

  private static final String V3 = "2026.1020.90000";

  private final CapturingService service =
      new CapturingService().declaring("docker", IMAGE, APP, KEY);

  private final InMemoryReleaseLedger ledger = new InMemoryReleaseLedger();

  private final SoftwareReleaseListener releases =
      SoftwareReleaseListenerTest.listenerWith(service, ledger);

  private final ReleaseFinalizedListener finalizations = finalizationsWith(service, ledger);

  private static ReleaseFinalizedListener finalizationsWith(
      CapturingService service, InMemoryReleaseLedger ledger) {
    ReleaseFinalizedListener listener = new ReleaseFinalizedListener();
    listener.adopter = SoftwareReleaseListenerTest.adopterWith(service, ledger);
    return listener;
  }

  // ------------------------------------------------------------ frames

  private static EventFrame release(String repository, String version) {
    return new EventFrame(
        UUID.randomUUID().toString(),
        "SoftwareRelease",
        Instant.parse("2026-10-20T13:32:27Z"),
        "{\"packageName\":\""
            + IMAGE
            + "\",\"packageType\":\"docker\",\"repository\":\""
            + repository
            + "\",\"version\":\""
            + version
            + "\"}",
        null,
        null,
        null);
  }

  private static EventFrame stateChanged(String repository, String state, String version) {
    return new EventFrame(
        UUID.randomUUID().toString(),
        "ReleaseRequestStateChanged",
        Instant.parse("2026-10-20T13:33:38Z"),
        "{\"backingBranch\":\"release-request/qits-stt-rr-7\",\"changedAt\":\"2026-10-20T13:33:38Z\""
            + ",\"previousState\":\"RELEASED\",\"releaseRequestId\":\"rr-7\""
            + ",\"releaseRequestQualifiedId\":\"qits-stt-rr-7\",\"repoId\":\""
            + repository
            + "\",\"repoName\":\"qits-stt\",\"state\":\""
            + state
            + "\""
            + (version == null ? "" : ",\"version\":\"" + version + "\"")
            + "}",
        null,
        null,
        null);
  }

  /** Offer a frame the way the funnel does: only a selected frame reaches {@code onFrame}. */
  private void deliver(EventFrame frame) {
    if ("SoftwareRelease".equals(frame.name())) {
      if (releases.selects(frame)) {
        releases.onFrame(frame);
      }
    } else if (finalizations.selects(frame)) {
      finalizations.onFrame(frame);
    }
  }

  private List<String> pinned() {
    return service.writes.stream().map(Write::value).toList();
  }

  private String pin() {
    List<String> values = pinned();
    return values.isEmpty() ? null : values.get(values.size() - 1);
  }

  // ------------------------------------------------------------ the cases

  /** The defect: an announced release, not yet finalized, moved the pin at once. */
  @Test
  void aSoftwareReleaseAloneMovesNoPin() {
    deliver(release(REPO, V1));

    assertEquals(List.of(), service.writes, "RELEASED is not the green light");
    assertFalse(
        ledger.adopted(new Published(REPO, V1, "docker", IMAGE)), "recorded, and waiting");
    assertTrue(ledger.published.containsKey(new Published(REPO, V1, "docker", IMAGE)));
  }

  /** The order a deployed service produces: the release, then — minutes later — its finalization. */
  @Test
  void aSoftwareReleaseThenItsFinalizationMovesThePin() {
    deliver(release(REPO, V1));
    deliver(stateChanged(REPO, "FINALIZED", V1));

    assertEquals(List.of(V1), pinned());
    assertTrue(ledger.adopted(new Published(REPO, V1, "docker", IMAGE)));
  }

  /** The order a library can produce: the finalization a few milliseconds ahead of the release. */
  @Test
  void aFinalizationThenItsSoftwareReleaseMovesThePin() {
    deliver(stateChanged(REPO, "FINALIZED", V1));
    assertEquals(List.of(), service.writes, "nothing was published yet");

    deliver(release(REPO, V1));

    assertEquals(List.of(V1), pinned());
  }

  /** FINALIZED announced twice, under two event ids: one adoption, one write. */
  @Test
  void aDuplicateFinalizationIsIdempotent() {
    deliver(release(REPO, V1));
    deliver(stateChanged(REPO, "FINALIZED", V1));
    deliver(stateChanged(REPO, "FINALIZED", V1));
    // …and a redelivered release after the adoption writes nothing either.
    deliver(release(REPO, V1));

    assertEquals(List.of(V1), pinned(), "one write, however often either half is announced");
    assertEquals(List.of(V1), ledger.finalizedVersions(REPO), "and one finalized row");
  }

  /** The join key is (repository, version): another repository's FINALIZED clears nothing here. */
  @Test
  void aFinalizationOfAnotherRepositoryMovesNothing() {
    deliver(release(REPO, V1));
    deliver(stateChanged(OTHER_REPO, "FINALIZED", V3));

    assertEquals(List.of(), service.writes);
  }

  /** A finalized version BELOW the release does not clear it. */
  @Test
  void aFinalizationAtALowerVersionMovesNothing() {
    deliver(release(REPO, V2));
    deliver(stateChanged(REPO, "FINALIZED", V1));

    assertEquals(List.of(), service.writes, V1 + " is below " + V2 + " in calver order");
    assertFalse(ledger.adopted(new Published(REPO, V2, "docker", IMAGE)), "still waiting");
  }

  /**
   * THE OBSOLETE CASE: v1 was published and its request went OBSOLETE; v2 folded it in, was
   * finalized, and published no new version of this package (an {@code if-changed} artifact that did
   * not change). An exact-match join would strand v1 forever; "at or below" adopts it.
   */
  @Test
  void aReleaseWithNoSoftwareReleaseAtTheFinalizedVersionIsAdoptedFromBelow() {
    deliver(release(REPO, V1));
    deliver(stateChanged(REPO, "OBSOLETE", V1));
    assertEquals(List.of(), service.writes, "OBSOLETE clears nothing");

    deliver(stateChanged(REPO, "FINALIZED", V2));

    assertEquals(List.of(V1), pinned());
  }

  /** Every state but FINALIZED is settled without a claim, and moves nothing. */
  @Test
  void aStateOtherThanFinalizedMovesNothing() {
    deliver(release(REPO, V1));
    for (String state :
        List.of(
            "PENDING", "READY", "RELEASED", "REJECTED", "FAILED", "CONFLICTED", "WITHDRAWN",
            "OBSOLETE")) {
      EventFrame frame = stateChanged(REPO, state, V1);
      assertFalse(finalizations.selects(frame), state + " is settled, not claimed");
      deliver(frame);
    }

    assertEquals(List.of(), service.writes);
  }

  /** A FINALIZED naming no version, or no repository, is poison: settled, and nothing moves. */
  @Test
  void aFinalizationNamingNoVersionOrNoRepositoryIsSettled() {
    deliver(release(REPO, V1));
    EventFrame noVersion = stateChanged(REPO, "FINALIZED", null);
    EventFrame noRepository = stateChanged("  ", "FINALIZED", V1);
    EventFrame unreadable =
        new EventFrame("evt-x", "ReleaseRequestStateChanged", Instant.EPOCH, "not json", null, null, null);

    assertFalse(finalizations.selects(noVersion));
    assertFalse(finalizations.selects(noRepository));
    assertFalse(finalizations.selects(unreadable));
    assertEquals(List.of(), service.writes);
  }

  /** Several cleared versions of one package at once: only the highest is applied. */
  @Test
  void oneFinalizationAppliesOnlyTheHighestClearedVersionPerPackage() {
    deliver(release(REPO, V1));
    deliver(release(REPO, V2));
    deliver(stateChanged(REPO, "FINALIZED", V2));

    assertEquals(List.of(V2), pinned(), "the lower one is superseded, not written first");
    assertTrue(ledger.adopted(new Published(REPO, V1, "docker", IMAGE)), "and never reconsidered");
  }

  /**
   * A PIN NEVER MOVES BACKWARDS: v2 is adopted, then a late finalization clears v1 (catch-up
   * delivering an older request's FINALIZED after a newer one). v1 is marked, not applied.
   */
  @Test
  void aLowerAdoptableVersionNeverRewindsAHigherAdoptedPin() {
    deliver(release(REPO, V1));
    deliver(release(REPO, V2));
    deliver(stateChanged(REPO, "FINALIZED", V2));
    assertEquals(List.of(V2), pinned());

    // The same shape on the arrival side: a release at v1 announced late, with v3 finalized and
    // v2 adopted. It is cleared — and still must not rewind the pin.
    deliver(stateChanged(REPO, "FINALIZED", V3));
    EventFrame late =
        new EventFrame(
            UUID.randomUUID().toString(),
            "SoftwareRelease",
            Instant.parse("2026-10-20T14:00:00Z"),
            "{\"packageName\":\""
                + IMAGE
                + "\",\"packageType\":\"docker\",\"repository\":\""
                + REPO
                + "\",\"version\":\"2026.1019.90000\"}",
            null,
            null,
            null);
    deliver(late);

    assertEquals(List.of(V2), pinned(), "nothing below the adopted version is ever written");
    assertEquals(V2, pin());
  }
}
