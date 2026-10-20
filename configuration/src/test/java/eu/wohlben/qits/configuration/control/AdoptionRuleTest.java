package eu.wohlben.qits.configuration.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.configuration.control.ReleaseLedger.Published;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The adoption decision, as the pure functions both listeners run (qits-1472). */
class AdoptionRuleTest {

  private static final String REPO = "repo-1";

  private static Published docker(String version) {
    return new Published(REPO, version, "docker", "qits/a");
  }

  private static Published maven(String version) {
    return new Published(REPO, version, "maven", "eu.wohlben.qits:a");
  }

  @Test
  void aReleaseIsAdoptableOnlyAtOrBelowAFinalizedVersion() {
    assertTrue(AdoptionRule.adoptable("2026.1019.81701", List.of("2026.1019.81701")));
    assertTrue(AdoptionRule.adoptable("2026.1019.81701", List.of("2026.1019.131105")));
    assertFalse(AdoptionRule.adoptable("2026.1019.131105", List.of("2026.1019.81701")));
    assertFalse(AdoptionRule.adoptable("2026.1019.81701", List.of()), "never finalized");
  }

  @Test
  void aPlanTakesTheHighestClearedVersionPerPackageAndSupersedesTheRest() {
    AdoptionRule.Plan plan =
        AdoptionRule.plan(
            List.of(
                docker("2026.1019.81701"),
                docker("2026.1019.131105"),
                maven("2026.1019.81701"),
                docker("2026.1020.1")),
            "2026.1019.131105");

    assertEquals(List.of(docker("2026.1019.131105"), maven("2026.1019.81701")), plan.apply());
    assertEquals(List.of(docker("2026.1019.81701")), plan.superseded());
  }

  @Test
  void aVersionAtOrBelowAnAdoptedOneWouldRewind() {
    assertTrue(AdoptionRule.wouldRewind("2026.1019.81701", List.of("2026.1019.131105")));
    assertTrue(AdoptionRule.wouldRewind("2026.1019.81701", List.of("2026.1019.81701")));
    assertFalse(AdoptionRule.wouldRewind("2026.1019.131105", List.of("2026.1019.81701")));
    assertFalse(AdoptionRule.wouldRewind("2026.1019.131105", List.of()));
  }
}
