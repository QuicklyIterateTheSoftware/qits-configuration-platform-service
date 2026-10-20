package eu.wohlben.qits.configuration.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * <b>The two foreign events this service consumes, checked against the wire form they have to
 * match</b> (qits-1472, modelled on qits-maintenance's test of the same name).
 *
 * <p>Both listeners read their payloads through {@code CanonicalJson.payloadTo} into records of their
 * own, and each of those is a hand-kept copy of a record in another repository. That leaves one thing
 * to go wrong: the publisher renames the event or a field, this service compiles, and a listener goes
 * deaf. For {@code SoftwareRelease} that is no release ever recorded; for {@code
 * ReleaseRequestStateChanged} it is worse and quieter — every release recorded and none ever adopted,
 * so every pin on the platform freezes with nothing in any log.
 *
 * <p><b>Why a transcription rather than a dependency.</b> qits-projects keeps {@code
 * ReleaseRequestStateChanged} in its own service module and publishes no vocabulary jar for it; this
 * repository's rule is that what crosses from another context is a wire contract, not a type.
 *
 * <p><b>The records below ARE the contract.</b> Each transcribes the component list, order and types
 * of one record in another repository, named at its declaration. The bytes are produced by {@link
 * CanonicalJson} — the serializer the real publisher runs the real record through — and then bound
 * through the listener's OWN payload record, so the claim is "the field this listener reads binds to
 * the value the publisher put there". A change to either publisher's record is a change here, in the
 * same campaign.
 */
class ForeignEventContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final Instant WHEN = Instant.parse("2026-10-20T13:33:38.763Z");

  // --- the transcriptions ----------------------------------------------------------------------

  /**
   * qits-ci's {@code SoftwareRelease}, from {@code
   * components/qits-ci/qits-ci-service/ci-events/src/main/java/eu/wohlben/qits/ci/events/SoftwareRelease.java}.
   * The NAME is transcribed too: {@code signature()} is the simple class name.
   */
  record SoftwareRelease(
      UUID eventId,
      String repository,
      String projectId,
      String repoId,
      String repoName,
      String version,
      String packageType,
      String packageName,
      Instant occurredAt,
      String priority,
      String section,
      String runId)
      implements QitsEvent {}

  /**
   * qits-projects' {@code ReleaseRequestStateChanged}, from {@code
   * components/qits-projects/qits-projects-service/service/src/main/java/eu/wohlben/qits/projects/bus/ReleaseRequestStateChanged.java}.
   *
   * <p>Its {@code occurredAt} is an override returning {@code changedAt} — the component is spelled
   * {@code changedAt} precisely so the mix-in, which hides {@code occurredAt}, leaves it in the payload.
   */
  record ReleaseRequestStateChanged(
      UUID eventId,
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
      List<String> branches)
      implements QitsEvent {

    @Override
    public Instant occurredAt() {
      return changedAt;
    }
  }

  // --- what a publisher would send ---------------------------------------------------------------

  static String softwareReleasePayload(String repository, String version) {
    return CanonicalJson.payload(
        new SoftwareRelease(
            UUID.randomUUID(),
            repository,
            "qits",
            repository,
            "qits-workspaces-service",
            version,
            "docker",
            "qits/qits-workspaces",
            WHEN,
            "NORMAL",
            "artifacts",
            UUID.randomUUID().toString()));
  }

  static String stateChangedPayload(String repoId, String state, String version) {
    return CanonicalJson.payload(
        new ReleaseRequestStateChanged(
            UUID.randomUUID(),
            "0afceb63-rr",
            "qits-workspaces-service-rr-204",
            "qits",
            repoId,
            "qits-workspaces-service",
            "release-request/qits-workspaces-service-rr-204",
            state,
            "RELEASED",
            WHEN,
            "abc1234",
            version,
            null,
            List.of("main", "ticket/qits-1472")));
  }

  static EventFrame frame(String name, String payload) {
    return new EventFrame(UUID.randomUUID().toString(), name, WHEN, payload, null, null, null);
  }

  // --- the names ----------------------------------------------------------------------------------

  @Test
  void theEventNamesTheListenersSubscribeToAreTheOnesTheseEventsRideUnder() {
    assertEquals(
        SoftwareRelease.class.getSimpleName(), SoftwareReleaseListener.SOFTWARE_RELEASE);
    assertEquals(
        ReleaseRequestStateChanged.class.getSimpleName(),
        ReleaseFinalizedListener.RELEASE_REQUEST_STATE_CHANGED);
    assertEquals(
        java.util.Set.of(SoftwareReleaseListener.SOFTWARE_RELEASE),
        new SoftwareReleaseListener().signatures());
    assertEquals(
        java.util.Set.of(ReleaseFinalizedListener.RELEASE_REQUEST_STATE_CHANGED),
        new ReleaseFinalizedListener().signatures());
  }

  /**
   * The consumer ids are STORAGE keys, pinned as literals: a change to either is a brand-new consumer
   * initializing at the head of the log and silently skipping everything in between. {@code
   * configuration.project-agent-image} under-describes its listener on purpose — see its javadoc.
   */
  @Test
  void theConsumerIdsAreStorageKeysAndAreSpelledOut() {
    assertEquals("configuration.project-agent-image", SoftwareReleaseListener.CONSUMER_ID);
    assertEquals("configuration.release-finalized", ReleaseFinalizedListener.CONSUMER_ID);
    assertNotEquals(SoftwareReleaseListener.CONSUMER_ID, ReleaseFinalizedListener.CONSUMER_ID);
  }

  /** The state word the listener acts on is qits-projects' {@code ReleaseRequest.State} spelling. */
  @Test
  void theStateWordIsTheOneThePublisherWrites() {
    assertEquals("FINALIZED", ReleaseFinalizedListener.FINALIZED);
  }

  // --- the field lists ----------------------------------------------------------------------------

  @Test
  void aSoftwareReleaseBindsIntoTheRecordThisServiceReads() throws Exception {
    String payload = softwareReleasePayload("8c0f5b8e-repo", "2026.1020.130544");

    SoftwareReleaseListener.SoftwareReleasePayload read =
        CanonicalJson.payloadTo(payload, SoftwareReleaseListener.SoftwareReleasePayload.class);
    assertEquals("8c0f5b8e-repo", read.repository());
    assertEquals("2026.1020.130544", read.version());
    assertEquals("docker", read.packageType());
    assertEquals("qits/qits-workspaces", read.packageName());

    JsonNode json = MAPPER.readTree(payload);
    for (String field : List.of("repository", "version", "packageType", "packageName")) {
      assertTrue(json.has(field), "the canonical payload carries no " + field);
    }
    assertTrue(new SoftwareReleaseListener().selects(frame("SoftwareRelease", payload)));
  }

  @Test
  void aReleaseRequestStateChangedBindsIntoTheRecordThisServiceReads() throws Exception {
    String payload = stateChangedPayload("8c0f5b8e-repo", "FINALIZED", "2026.1020.130544");

    ReleaseFinalizedListener.ReleaseRequestStateChangedPayload read =
        CanonicalJson.payloadTo(
            payload, ReleaseFinalizedListener.ReleaseRequestStateChangedPayload.class);
    assertEquals("8c0f5b8e-repo", read.repoId());
    assertEquals("FINALIZED", read.state());
    assertEquals("2026.1020.130544", read.version());
    assertEquals("qits-workspaces-service-rr-204", read.releaseRequestQualifiedId());
    assertEquals("RELEASED", read.previousState());
    assertEquals(WHEN, read.changedAt());
    assertEquals(List.of("main", "ticket/qits-1472"), read.branches());

    JsonNode json = MAPPER.readTree(payload);
    for (String field : List.of("repoId", "state", "version", "changedAt")) {
      assertTrue(json.has(field), "the canonical payload carries no " + field);
    }
    // detail was null, so it is absent — and the record still binds.
    assertFalse(json.has("detail"));
    assertTrue(
        new ReleaseFinalizedListener().selects(frame("ReleaseRequestStateChanged", payload)),
        "a FINALIZED naming a repository and a version is claimed");
    assertFalse(
        new ReleaseFinalizedListener()
            .selects(
                frame(
                    "ReleaseRequestStateChanged",
                    stateChangedPayload("8c0f5b8e-repo", "RELEASED", "2026.1020.130544"))),
        "any other state is settled");
  }

  /**
   * The JOIN KEY: the value a {@code SoftwareRelease} carries as {@code repository} and the one a
   * {@code ReleaseRequestStateChanged} carries as {@code repoId} are the same qits-projects row id,
   * and each listener reads its own one.
   */
  @Test
  void bothHalvesCarryTheJoinKeyUnderTheirOwnName() {
    String repository = UUID.randomUUID().toString();
    SoftwareReleaseListener.SoftwareReleasePayload released =
        CanonicalJson.payloadTo(
            softwareReleasePayload(repository, "2026.1020.1"),
            SoftwareReleaseListener.SoftwareReleasePayload.class);
    ReleaseFinalizedListener.ReleaseRequestStateChangedPayload finalized =
        CanonicalJson.payloadTo(
            stateChangedPayload(repository, "FINALIZED", "2026.1020.1"),
            ReleaseFinalizedListener.ReleaseRequestStateChangedPayload.class);
    assertEquals(released.repository(), finalized.repoId());
  }

  /** Identity travels in the envelope; the mix-in keeps {@code eventId} and {@code occurredAt} out. */
  @Test
  void identityAndTimeTravelInTheEnvelopeAndNeverInAPayload() throws Exception {
    for (String payload :
        List.of(
            softwareReleasePayload("r", "1"), stateChangedPayload("r", "FINALIZED", "1"))) {
      JsonNode json = MAPPER.readTree(payload);
      assertFalse(json.has("eventId"), "identity travels in the envelope, never in the payload");
      assertFalse(json.has("occurredAt"), "and so does the timestamp");
    }
  }
}
