package eu.wohlben.qits.eventstream.control;

import java.util.Collection;
import java.util.List;

/**
 * <b>The eventstream catch-up's two reads, opened to this repository's consumer pact</b>
 * (qits-1149). {@link EventsQuery} and its page are package-private in qits-eventstream, so this
 * test-only class sits in the same package and hands the pact test the real client, pointed at a
 * pact mock server — the exact code {@code CatchupSweeper} runs for {@code SoftwareReleaseListener}.
 */
public final class EventsQueryProbe {

  /** One page as the catch-up reads it: the events and the cursor that says whether more follow. */
  public record Page(List<EventFrame> events, String nextCursor) {}

  /** The catch-up's own page size, so the pact asks for what production asks for. */
  public static final int PAGE_SIZE = CatchupSweeper.PAGE_SIZE;

  private final EventsQuery query = new EventsQuery();

  public EventsQueryProbe(String eventsUrl) {
    query.eventsUrl = eventsUrl;
  }

  /** {@code CatchupSweeper.initialize}'s read: the newest matching event, or null. */
  public EventFrame newest(Collection<String> names) {
    return query.newest(names);
  }

  /** {@code CatchupSweeper.catchUp}'s read: the next page after {@code cursor}, oldest first. */
  public Page after(Collection<String> names, String cursor) {
    EventPage page = query.after(names, cursor, PAGE_SIZE);
    return new Page(page.events(), page.nextCursor());
  }
}
