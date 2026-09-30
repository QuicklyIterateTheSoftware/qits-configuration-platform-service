package eu.wohlben.qits.configuration.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.configuration.control.RetiredEntryCollector.Declared;
import eu.wohlben.qits.configuration.control.RetiredEntryCollector.Judgement;
import eu.wohlben.qits.configuration.control.RetiredEntryCollector.Reason;
import eu.wohlben.qits.configuration.control.RetiredEntryCollector.Stored;
import eu.wohlben.qits.configuration.control.RetiredEntryCollector.Verdict;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The collector's rule, with no database behind it: one case per reason to keep, the one case that
 * collects, the order the reasons are asked in, and the failure that must keep everything.
 *
 * <p>The fixture is one application whose declarations arrive at {@link #T1} (version 1.0), {@link
 * #T2} (2.0) and {@link #T3} (3.0), in that intake order; entries are written at {@link #BEFORE}
 * unless a case is about when they were written.
 */
class RetiredEntryCollectorTest {

  private static final String APP = "gc-app";

  private static final Instant BEFORE = Instant.parse("2026-09-01T00:00:00Z");
  private static final Instant T1 = Instant.parse("2026-09-10T00:00:00Z");
  private static final Instant T2 = Instant.parse("2026-09-20T00:00:00Z");
  private static final Instant T3 = Instant.parse("2026-09-25T00:00:00Z");
  private static final Instant AFTER = Instant.parse("2026-09-29T00:00:00Z");

  private static final Declared V1 = new Declared("1.0", 1, T1, Set.of("env.OLD", "env.KEPT"));
  private static final Declared V2 = new Declared("2.0", 2, T2, Set.of("env.KEPT"));
  private static final Declared V3 = new Declared("3.0", 3, T3, Set.of("env.KEPT", "env.NEXT"));

  private static Stored entry(String key, Instant updatedAt) {
    return new Stored(APP, "dev", key, updatedAt, 7);
  }

  private static Verdict verdict(Stored entry, Set<String> pins, List<Declared> declared) {
    return RetiredEntryCollector.verdict(entry, pins, declared);
  }

  @Test
  void anUnpinnedApplicationKeepsEverything() {
    Verdict verdict = verdict(entry("env.OLD", BEFORE), Set.of(), List.of(V1, V2));
    assertEquals(Reason.UNPINNED, verdict.reason());
  }

  @Test
  void aPinnedVersionWithNoDeclarationKeepsEverything() {
    // 9.9 is running and declared nothing, so it resolves entries-only and may read any of them.
    Verdict verdict = verdict(entry("env.OLD", BEFORE), Set.of("2.0", "9.9"), List.of(V1, V2));
    assertEquals(Reason.UNDECLARED_PINNED_VERSION, verdict.reason());
  }

  @Test
  void aKeyAPinnedVersionStatesIsKept() {
    // The rollback version 1.0 still states env.OLD, though the serving 2.0 does not.
    Verdict verdict = verdict(entry("env.OLD", BEFORE), Set.of("1.0", "2.0"), List.of(V1, V2));
    assertEquals(Reason.PINNED, verdict.reason());
  }

  @Test
  void aKeyAVersionReceivedAfterTheNewestPinnedOneStatesIsInFlight() {
    Verdict verdict = verdict(entry("env.NEXT", BEFORE), Set.of("2.0"), List.of(V1, V2, V3));
    assertEquals(Reason.IN_FLIGHT, verdict.reason());
  }

  @Test
  void aKeyNoDeclarationEverStatedIsNotThisRulesQuestion() {
    Verdict verdict = verdict(entry("env.HAND_SET", BEFORE), Set.of("2.0"), List.of(V1, V2));
    assertEquals(Reason.NEVER_DECLARED, verdict.reason());
  }

  @Test
  void anEntryWrittenAfterTheNewestDeclarationIsStaged() {
    Verdict verdict = verdict(entry("env.OLD", AFTER), Set.of("2.0"), List.of(V1, V2));
    assertEquals(Reason.STAGED, verdict.reason());
  }

  @Test
  void aRetiredKeyIsCollectedNamingTheLastVersionThatDeclaredIt() {
    Declared alsoOld = new Declared("1.5", 5, T2, Set.of("env.OLD"));
    Declared serving = new Declared("2.0", 6, T3, Set.of("env.KEPT"));
    Verdict verdict = verdict(entry("env.OLD", BEFORE), Set.of("2.0"), List.of(V1, alsoOld, serving));
    assertEquals(Reason.RETIRED, verdict.reason());
    assertEquals("1.5", verdict.lastDeclaredBy());
  }

  /**
   * The intake order decides "after", not the version string: 1.5 was received LAST here and is
   * not pinned, so its key is in flight even though "1.5" sorts below "2.0".
   */
  @Test
  void receivedAfterIsTheIntakeOrderNotTheVersionString() {
    Declared recut = new Declared("1.5", 9, T3, Set.of("env.OLD"));
    Verdict verdict = verdict(entry("env.OLD", BEFORE), Set.of("2.0"), List.of(V2, recut));
    assertEquals(Reason.IN_FLIGHT, verdict.reason());
  }

  /** A key that is both pinned-declared and in flight, and written late, counts once: pinned. */
  @Test
  void theFirstReasonThatHoldsIsTheOneCounted() {
    Verdict verdict = verdict(entry("env.KEPT", AFTER), Set.of("2.0"), List.of(V1, V2, V3));
    assertEquals(Reason.PINNED, verdict.reason());
    assertNull(verdict.lastDeclaredBy());
  }

  @Test
  void aDeclarationReadThatFailsKeepsThatApplicationAndJudgesTheRest() {
    Stored broken = new Stored("gc-broken", "dev", "env.OLD", BEFORE, 1);
    Stored fine = entry("env.OLD", BEFORE);
    Judgement judgement =
        RetiredEntryCollector.judge(
            List.of(broken, fine),
            Map.of("gc-broken", Set.of("1.0"), APP, Set.of("2.0")),
            application -> {
              if (application.equals("gc-broken")) {
                throw new IllegalStateException("the store went away");
              }
              return List.of(V1, V2);
            });

    assertEquals(1, judgement.verdicts().size(), "the broken application gets no verdict at all");
    assertEquals(fine, judgement.verdicts().get(0).entry());
    assertEquals(Reason.RETIRED, judgement.verdicts().get(0).reason());
    assertEquals(1, judgement.errors().size());
    assertEquals("gc-broken", judgement.errors().get(0).application());
    assertTrue(judgement.errors().get(0).message().contains("the store went away"));
  }

  @Test
  void anUnpinnedApplicationsDeclarationsAreNeverRead() {
    Judgement judgement =
        RetiredEntryCollector.judge(
            List.of(entry("env.OLD", BEFORE)),
            Map.of(),
            application -> {
              throw new AssertionError("read the declarations of an unpinned application");
            });
    assertEquals(Reason.UNPINNED, judgement.verdicts().get(0).reason());
    assertTrue(judgement.errors().isEmpty());
  }
}
