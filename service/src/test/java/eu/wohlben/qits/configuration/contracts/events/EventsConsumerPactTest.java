package eu.wohlben.qits.configuration.contracts.events;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.consumer.ConsumerPactRunnerKt;
import au.com.dius.pact.consumer.PactVerificationResult;
import au.com.dius.pact.consumer.model.MockProviderConfig;
import au.com.dius.pact.core.model.PactSpecVersion;
import eu.wohlben.qits.eventstream.control.EventsQueryProbe;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * <b>The consumer half of the qits-events contract</b> (qits-1149): the real {@code EventsQuery}
 * of qits-eventstream (through {@link EventsQueryProbe}), making a real HTTP call to a pact-jvm
 * mock server that answers exactly what {@link EventsContract}'s row promises, and the row's own
 * assertions on what the client made of it.
 *
 * <p>Plain JUnit 5 and pact-jvm's programmatic runner, one mock server per row, as
 * qits-maintenance-service's {@code ProjectsConsumerPactTest}: two rows send the same {@code GET}
 * with different queries in the same state, and one server per row keeps them apart.
 */
class EventsConsumerPactTest {

  static {
    // pact-jvm reports usage metrics over the network unless told not to; a test never should.
    System.setProperty("pact_do_not_track", "true");
  }

  @Test
  void everyReadyRowIsWhatTheCatchUpAsksAndUnderstands() {
    assertFalse(EventsContract.ready().isEmpty());
    List<String> failures = new ArrayList<>();
    for (EventsContract.Case row : EventsContract.ready()) {
      PactVerificationResult result =
          ConsumerPactRunnerKt.runConsumerTest(
              EventsContract.pact(List.of(row)),
              MockProviderConfig.createDefault(PactSpecVersion.V4),
              (mockServer, context) -> {
                row.call().run(new EventsQueryProbe(mockServer.getUrl()));
                return null;
              });
      if (!(result instanceof PactVerificationResult.Ok)) {
        failures.add(row.description() + " [" + row.state() + "]: " + describe(result));
      }
    }
    if (!failures.isEmpty()) {
      fail(failures.size() + " contract row(s) failed:\n  " + String.join("\n  ", failures));
    }
  }

  /** Skipped, naming each row that waits on a provider state, until qits-events records it. */
  @Test
  void pendingRowsWaitOnAProviderState() {
    Assumptions.assumeTrue(
        EventsContract.pending().isEmpty(),
        () ->
            EventsContract.pending().stream()
                .map(c -> c.description() + " [" + c.state() + "]: " + c.pending())
                .collect(Collectors.joining("\n  ", "skipped:\n  ", "")));
  }

  private static String describe(PactVerificationResult result) {
    if (result instanceof PactVerificationResult.Error error) {
      return "error: " + error.getError();
    }
    return result.getDescription() + " — " + result;
  }
}
