package eu.wohlben.qits.configuration.control;

import eu.wohlben.qits.configuration.control.ReleaseLedger.Published;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WHEN A RELEASED VERSION MAY MOVE A PIN (qits-1472): the decision over {@link ReleaseLedger}'s two
 * halves, as pure functions so it is tested without a database and the listeners cannot disagree
 * about it.
 *
 * <p><b>The rule.</b> An artifact of repository R at version v is adoptable iff R has a finalized
 * version V with V ≥ v ({@link VersionOrder}). "At or below", not "equal": an OBSOLETE request's tag
 * is folded into the next one, which finalizes past it; and qits-ci does not announce an {@code
 * if-changed} artifact (maven, npm, every contract package) at a version where it did not change, so
 * an artifact first published at a version that went OBSOLETE gets no {@code SoftwareRelease} at the
 * version that finalizes. An exact-match join would strand it forever. An artifact with no finalized
 * row at or above it is never adopted.
 *
 * <p><b>A pin never moves backwards.</b> Several versions of one package can become adoptable at once,
 * and catch-up can deliver them out of order; only the highest is applied, and none is applied when a
 * version of the same package at or above it was already adopted.
 */
public final class AdoptionRule {

  /**
   * What one finalization adopts: the highest cleared release per package, and the lower cleared
   * ones it supersedes — those are marked adopted without being applied, so they are never
   * reconsidered.
   */
  public record Plan(List<Published> apply, List<Published> superseded) {}

  private AdoptionRule() {}

  /** Whether a release at {@code version} is cleared by any of a repository's finalized versions. */
  public static boolean adoptable(String version, Collection<String> finalizedVersions) {
    return finalizedVersions.stream().anyMatch(ceiling -> VersionOrder.atOrBelow(version, ceiling));
  }

  /**
   * What a finalization of {@code finalizedVersion} adopts out of a repository's unadopted releases:
   * those at or below it, the highest per (package type, package name).
   */
  public static Plan plan(Collection<Published> unadopted, String finalizedVersion) {
    Map<String, Published> highest = new LinkedHashMap<>();
    List<Published> superseded = new ArrayList<>();
    for (Published release : unadopted) {
      if (!VersionOrder.atOrBelow(release.version(), finalizedVersion)) {
        continue;
      }
      String coordinate = release.packageType() + " " + release.packageName();
      Published current = highest.get(coordinate);
      if (current == null) {
        highest.put(coordinate, release);
      } else if (VersionOrder.compare(release.version(), current.version()) > 0) {
        superseded.add(current);
        highest.put(coordinate, release);
      } else {
        superseded.add(release);
      }
    }
    return new Plan(List.copyOf(highest.values()), List.copyOf(superseded));
  }

  /**
   * Whether applying {@code version} would move a pin backwards, or nowhere: a version of the same
   * package at or above it has already been adopted. A redelivered release is that case too — its
   * own version is among the adopted ones.
   */
  public static boolean wouldRewind(String version, Collection<String> adoptedVersions) {
    return adoptedVersions.stream().anyMatch(adopted -> VersionOrder.compare(adopted, version) >= 0);
  }
}
