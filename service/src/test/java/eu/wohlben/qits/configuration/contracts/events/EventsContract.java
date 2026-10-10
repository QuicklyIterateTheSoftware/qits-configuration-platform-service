package eu.wohlben.qits.configuration.contracts.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import eu.wohlben.qits.configuration.contracts.events.EventsGoldenMasters.Trigger;
import eu.wohlben.qits.eventstream.control.EventsQueryProbe;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>What qits-configuration asks qits-events, and why</b> (qits-1149) — the one table both {@code
 * EventsConsumerPactTest} (each row against a pact mock server) and {@code EventsPactFileTest} (the
 * committed {@code pacts/qits-configuration-service_qits-events-service.json}) are built from.
 *
 * <p><b>This service makes no REST call of its own.</b> Every call in this table is the
 * qits-eventstream library's catch-up ({@code CatchupSweeper}, through {@code EventsQuery}), run for
 * the one durable listener this service has, {@code bus/SoftwareReleaseListener}. It reads {@code GET
 * /events/api/events} two ways:
 *
 * <ul>
 *   <li>{@code CatchupSweeper.initialize} — no watermark yet: the newest {@code SoftwareRelease}
 *       ({@code ?limit=1&name=SoftwareRelease}). Reads {@code events[0].id} and {@code
 *       .occurredAt}, or an empty list.
 *   <li>{@code CatchupSweeper.catchUp} — page forward from the watermark ({@code
 *       ?order=asc&limit=200&name=SoftwareRelease[&cursor=...]}). Reads every {@code events[*]}
 *       frame ({@code id}, {@code name}, {@code occurredAt}, {@code payload}) and {@code nextCursor}.
 * </ul>
 *
 * <p><b>Two rows are pending</b> ({@link Case#pending}): a non-empty answer needs a qits-events
 * provider state holding {@code SoftwareRelease} events, and a recording that keeps {@code
 * nextCursor} (qits-events' recorder drops it today). Those rows stay in the table, are not written
 * to the pact, and the consumer test reports them as skipped.
 *
 * <p>The query is this consumer's own; only the response is read off the golden master (see {@link
 * EventsGoldenMasters#interaction}).
 */
final class EventsContract {

  static final String NO_EVENTS = "no events";
  static final String SOFTWARE_RELEASE_EVENTS = "SoftwareRelease events to catch up on";

  static final String LIST_EVENTS = "listEvents";

  /** The one event name {@code SoftwareReleaseListener} subscribes to. */
  static final String SOFTWARE_RELEASE = "SoftwareRelease";

  static final Trigger INITIALIZE = Trigger.schedule("CatchupSweeper.initialize");
  static final Trigger CATCH_UP = Trigger.schedule("CatchupSweeper.catchUp");

  /** What the consumer does with the client for one row, asserting what that code path reads. */
  @FunctionalInterface
  interface Call {
    void run(EventsQueryProbe client);
  }

  /**
   * One (trigger, call).
   *
   * @param reads the top-level response fields the call binds — the only ones the pact carries
   * @param pending null when the row is in the pact; otherwise why it cannot be yet
   */
  record Case(
      Trigger trigger,
      String state,
      String operationId,
      Map<String, String> query,
      List<String> reads,
      Call call,
      String pending) {

    String description() {
      return EventsGoldenMasters.description(operationId, trigger);
    }
  }

  private static Map<String, String> newestQuery() {
    Map<String, String> query = new LinkedHashMap<>();
    query.put("limit", "1");
    query.put("name", SOFTWARE_RELEASE);
    return query;
  }

  private static Map<String, String> pageQuery() {
    Map<String, String> query = new LinkedHashMap<>();
    query.put("order", "asc");
    query.put("limit", Integer.toString(EventsQueryProbe.PAGE_SIZE));
    query.put("name", SOFTWARE_RELEASE);
    return query;
  }

  /** Nothing released yet: the consumer starts at the beginning of an empty log. */
  private static final Call NEWEST_OF_NOTHING =
      client -> assertNull(client.newest(Set.of(SOFTWARE_RELEASE)), "an empty log has no newest");

  /** At the head: an empty last page, which is what lets the watermark stay where it is. */
  private static final Call EMPTY_PAGE =
      client -> {
        EventsQueryProbe.Page page = client.after(Set.of(SOFTWARE_RELEASE), null);
        assertEquals(List.of(), page.events());
        assertNull(page.nextCursor(), "an empty page is the last page");
      };

  private static String needs(String what) {
    return "needs provider state '"
        + SOFTWARE_RELEASE_EVENTS
        + "' for listEvents in qits-events-service ("
        + what
        + ")";
  }

  static final List<Case> CASES =
      List.of(
          new Case(INITIALIZE, NO_EVENTS, LIST_EVENTS, newestQuery(), List.of("events"),
              NEWEST_OF_NOTHING, null),
          new Case(CATCH_UP, NO_EVENTS, LIST_EVENTS, pageQuery(), List.of("events"),
              EMPTY_PAGE, null),
          new Case(INITIALIZE, SOFTWARE_RELEASE_EVENTS, LIST_EVENTS, newestQuery(),
              List.of("events"), client -> {},
              needs("recorded with ?limit=1&name=SoftwareRelease: the newest SoftwareRelease"
                  + " frame, id and occurredAt")),
          new Case(CATCH_UP, SOFTWARE_RELEASE_EVENTS, LIST_EVENTS, pageQuery(),
              List.of("events", "nextCursor"), client -> {},
              needs("recorded with ?order=asc&limit=200&name=SoftwareRelease: frames with id,"
                  + " name, occurredAt and a SoftwareRelease payload, and nextCursor kept")));

  /** The rows the pact holds today. */
  static List<Case> ready() {
    return CASES.stream().filter(c -> c.pending() == null).toList();
  }

  /** The rows waiting on a provider state. */
  static List<Case> pending() {
    return CASES.stream().filter(c -> c.pending() != null).toList();
  }

  private EventsContract() {}

  /** The whole contract as one V4 pact: every ready row, in table order (the file test sorts). */
  static V4Pact pact() {
    return pact(ready());
  }

  /** A pact holding only {@code cases} — one mock server per row, see the pact test. */
  static V4Pact pact(List<Case> cases) {
    PactBuilder builder =
        new PactBuilder(
            EventsGoldenMasters.CONSUMER, EventsGoldenMasters.PROVIDER, PactSpecVersion.V4);
    for (Case c : cases) {
      if (c.pending() != null) {
        throw new IllegalArgumentException(c.description() + " is pending: " + c.pending());
      }
      EventsGoldenMasters.interaction(
          builder, c.state(), c.operationId(), c.trigger(), c.query(), c.reads());
    }
    return builder.toPact();
  }
}
