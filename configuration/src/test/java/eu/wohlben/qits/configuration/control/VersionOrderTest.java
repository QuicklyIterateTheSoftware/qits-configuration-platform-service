package eu.wohlben.qits.configuration.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The one version order this service holds — see {@link VersionOrder} for why it holds one. */
class VersionOrderTest {

  /** The case a string comparison gets wrong, measured on the live log (qits-1472). */
  @Test
  void calverSegmentsCompareNumericallyNotAsStrings() {
    assertTrue(VersionOrder.compare("2026.1019.81701", "2026.1019.131105") < 0);
    assertTrue("2026.1019.81701".compareTo("2026.1019.131105") > 0, "which a string order inverts");
    assertTrue(VersionOrder.compare("2026.919.120127", "2026.1002.70617") < 0, "MMDD 919 < 1002");
    assertTrue(VersionOrder.compare("2027.101.1", "2026.1231.235959") > 0);
  }

  @Test
  void atOrBelowIncludesEquality() {
    assertTrue(VersionOrder.atOrBelow("2026.1020.130544", "2026.1020.130544"));
    assertTrue(VersionOrder.atOrBelow("2026.1020.130544", "2026.1020.133338"));
    assertFalse(VersionOrder.atOrBelow("2026.1020.133338", "2026.1020.130544"));
  }

  @Test
  void aLeadingVAndTrailingZeroSegmentsAreNotDifferences() {
    assertEquals(0, VersionOrder.compare("v1.2.3", "1.2.3"));
    assertEquals(0, VersionOrder.compare("1.2", "1.2.0"));
    assertEquals(0, VersionOrder.compare("1.02", "1.2"));
  }

  @Test
  void aQualifierRanksBelowTheNumberAndOverlongNumbersDoNotOverflow() {
    assertTrue(VersionOrder.compare("1.0-rc1", "1.0.1") < 0);
    assertTrue(VersionOrder.compare("1.99999999999999999999", "1.100000000000000000000") < 0);
  }

  @Test
  void itSortsAMixedListTheWayAReleaseHistoryReads() {
    List<String> versions =
        new ArrayList<>(
            List.of("2026.1019.131105", "2026.1002.170855", "2026.1019.81701", "2026.919.40352"));
    versions.sort(VersionOrder.ORDER);
    assertEquals(
        List.of("2026.919.40352", "2026.1002.170855", "2026.1019.81701", "2026.1019.131105"),
        versions);
  }
}
