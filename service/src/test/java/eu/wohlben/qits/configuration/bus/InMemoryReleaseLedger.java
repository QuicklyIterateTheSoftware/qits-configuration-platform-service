package eu.wohlben.qits.configuration.bus;

import eu.wohlben.qits.configuration.control.ReleaseLedger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A {@link ReleaseLedger} held in memory, for the listener tests.
 *
 * <p><b>Storage only, and that is the point.</b> Every DECISION the listeners make about a release —
 * adoptable or not, which version per package, whether it would rewind — is {@code
 * control/AdoptionRule}, which these tests run for real; this class stands in for nothing but the two
 * tables, with the same idempotency the {@code on conflict do nothing} inserts give them. The
 * database half is {@code ReleaseLedgerTest}'s, against a real postgres.
 */
class InMemoryReleaseLedger extends ReleaseLedger {

  /** repository -> finalized versions, in the order recorded. */
  final Map<String, Set<String>> finalized = new LinkedHashMap<>();

  /** Every recorded release -> adopted? */
  final Map<Published, Boolean> published = new LinkedHashMap<>();

  InMemoryReleaseLedger finalizing(String repository, String version) {
    recordFinalized(repository, version);
    return this;
  }

  @Override
  public void recordPublished(Published release) {
    published.putIfAbsent(release, false);
  }

  @Override
  public boolean recordFinalized(String repository, String version) {
    return finalized.computeIfAbsent(repository, r -> new LinkedHashSet<>()).add(version);
  }

  @Override
  public List<String> finalizedVersions(String repository) {
    return List.copyOf(finalized.getOrDefault(repository, Set.of()));
  }

  @Override
  public List<Published> unadopted(String repository) {
    List<Published> waiting = new ArrayList<>();
    published.forEach(
        (release, adopted) -> {
          if (!adopted && release.repository().equals(repository)) {
            waiting.add(release);
          }
        });
    return waiting;
  }

  @Override
  public List<String> adoptedVersions(String packageType, String packageName) {
    List<String> versions = new ArrayList<>();
    published.forEach(
        (release, adopted) -> {
          if (adopted
              && release.packageType().equals(packageType)
              && release.packageName().equals(packageName)) {
            versions.add(release.version());
          }
        });
    return versions;
  }

  @Override
  public void markAdopted(Published release) {
    published.replace(release, true);
  }

  /** Whether a release was recorded and has been adopted. */
  boolean adopted(Published release) {
    return Boolean.TRUE.equals(published.get(release));
  }
}
