package eu.wohlben.qits.configuration.bus;

import eu.wohlben.qits.eventstream.control.EventFrame;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Native-image reflection registration for every type the event bus binds JSON to on the CONSUME
 * path — a class with no code, only the annotation.
 *
 * <p><b>Nothing registers these automatically, and the reason is deliberate on the library's
 * side.</b> {@code CanonicalJson} builds its own {@code ObjectMapper} rather than injecting the CDI
 * one — the payload string is a byte-for-byte wire contract a consuming application's customizers
 * must not reach — so the graph that mapper binds is invisible to the build step that scans for what
 * needs reflecting on. On a JVM these bind whether anyone registered them or not, which is exactly
 * what lets the omission survive a green suite: the failure is in the binary, at runtime, on the
 * first frame.
 *
 * <p>This service consumes and publishes nothing, so it registers only the consume path:
 *
 * <ul>
 *   <li>{@link EventFrame} — a live frame off {@code /events/stream}, and every row of the catch-up
 *       log, which binds to the same record.
 *   <li>{@code EventPage} — one page of {@code GET /events/api/events}, by string name because it is
 *       package-private in the library. Without it the stream works in the binary and <b>catch-up
 *       alone</b> fails — the half that only matters after a cutover.
 *   <li>{@link SoftwareReleaseListener.SoftwareReleasePayload} and {@link
 *       ReleaseFinalizedListener.ReleaseRequestStateChangedPayload} — the payloads this component
 *       reads out of the frames (the second since qits-1472, which gates a pin on finalization).
 *   <li>The {@code CanonicalJson$QitsEventMixin}, by string name because it is a nested type inside
 *       the library — the mix-in that keeps {@code eventId} out of the payload, so its absence is a
 *       payload silently gaining a field the contract omits.
 * </ul>
 */
@RegisterForReflection(
    targets = {
      EventFrame.class,
      SoftwareReleaseListener.SoftwareReleasePayload.class,
      ReleaseFinalizedListener.ReleaseRequestStateChangedPayload.class
    },
    classNames = {
      "eu.wohlben.qits.eventstream.control.EventPage",
      "eu.wohlben.qits.eventstream.control.CanonicalJson$QitsEventMixin"
    })
final class EventWireReflection {

  private EventWireReflection() {}
}
