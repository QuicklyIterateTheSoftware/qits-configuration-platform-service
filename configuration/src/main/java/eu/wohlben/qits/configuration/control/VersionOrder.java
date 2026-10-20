package eu.wohlben.qits.configuration.control;

import java.util.Comparator;
import java.util.regex.Pattern;

/**
 * "Is this version at or below that one" — the one ordering of version strings this service holds,
 * and it holds it for one question only: whether a finalized release clears a published artifact of
 * the same repository (qits-1472).
 *
 * <p><b>This is a narrow exception to a stance stated elsewhere</b>, and it stays narrow. {@code
 * ConfigurationDeclarationRepository.governing} deliberately has no opinion about how versions order,
 * and keeps not having one: which declaration governs is decided by the intake log. Adoption cannot be
 * decided that way, because the finalized version and the artifact's version are two different
 * versions of one repository and "at or below" is the rule — an OBSOLETE request's tag is folded into
 * the next one, which finalizes PAST it, and an {@code if-changed} artifact is never announced at a
 * version where it did not change.
 *
 * <p><b>The order is segment-wise, numeric where both segments are digits.</b> The platform releases
 * calver ({@code YYYY.MMDD.HHMMSS}, written without leading zeros, so {@code 2026.1019.81701} is
 * BELOW {@code 2026.1019.131105}, although a string comparison puts it above). Segments are split
 * on {@code .}, {@code -}, {@code +} and {@code _}; a leading {@code v} is a tag convention and
 * ignored; a missing segment counts as {@code 0}; a numeric segment ranks above a non-numeric one and
 * two non-numeric ones compare as strings. That agrees with maven's {@code
 * ComparableVersion} — the order qits-maintenance ranks the same versions with — on every calver and
 * plain-semver string, which is every version this platform releases, without taking a maven jar for
 * one comparison.
 */
public final class VersionOrder {

  /** The order itself. Never throws on a non-null string. */
  public static final Comparator<String> ORDER = VersionOrder::compare;

  private static final Pattern SEPARATORS = Pattern.compile("[.\\-+_]");

  private VersionOrder() {}

  /** Whether {@code version} is at or below {@code ceiling}. */
  public static boolean atOrBelow(String version, String ceiling) {
    return compare(version, ceiling) <= 0;
  }

  /** Negative, zero or positive as {@code left} is below, equal to or above {@code right}. */
  public static int compare(String left, String right) {
    String[] ours = SEPARATORS.split(normalise(left), -1);
    String[] theirs = SEPARATORS.split(normalise(right), -1);
    int length = Math.max(ours.length, theirs.length);
    for (int i = 0; i < length; i++) {
      int order = compareSegment(i < ours.length ? ours[i] : "0", i < theirs.length ? theirs[i] : "0");
      if (order != 0) {
        return order;
      }
    }
    return 0;
  }

  private static int compareSegment(String left, String right) {
    boolean leftNumeric = numeric(left);
    boolean rightNumeric = numeric(right);
    if (leftNumeric && rightNumeric) {
      // By length once leading zeros are gone, then by digits: no overflow, whatever the length.
      String a = stripZeros(left);
      String b = stripZeros(right);
      return a.length() != b.length() ? Integer.compare(a.length(), b.length()) : a.compareTo(b);
    }
    if (leftNumeric != rightNumeric) {
      return leftNumeric ? 1 : -1;
    }
    return left.compareTo(right);
  }

  private static boolean numeric(String segment) {
    if (segment.isEmpty()) {
      return false;
    }
    for (int i = 0; i < segment.length(); i++) {
      if (!Character.isDigit(segment.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private static String stripZeros(String digits) {
    int i = 0;
    while (i < digits.length() - 1 && digits.charAt(i) == '0') {
      i++;
    }
    return digits.substring(i);
  }

  private static String normalise(String version) {
    String value = version.trim();
    if (value.length() > 1
        && (value.charAt(0) == 'v' || value.charAt(0) == 'V')
        && Character.isDigit(value.charAt(1))) {
      return value.substring(1);
    }
    return value;
  }
}
