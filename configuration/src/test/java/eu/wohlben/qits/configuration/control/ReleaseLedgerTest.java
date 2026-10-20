package eu.wohlben.qits.configuration.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.configuration.control.ReleaseLedger.Published;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The two adoption tables against a real postgres (qits-1472): idempotent records under the unique
 * constraints, and the reads {@link AdoptionRule} decides over. Each test names a repository of its
 * own, because the suite shares one database across classes.
 */
@QuarkusTest
class ReleaseLedgerTest {

  @Inject ReleaseLedger ledger;

  private static String repository() {
    return "repo-" + UUID.randomUUID();
  }

  @Test
  void aFinalizationAnnouncedTwiceIsOneRow() {
    String repo = repository();

    assertTrue(ledger.recordFinalized(repo, "2026.1020.133338"));
    assertFalse(ledger.recordFinalized(repo, "2026.1020.133338"), "the second is a no-op");
    assertTrue(ledger.recordFinalized(repo, "2026.1020.140000"));

    assertEquals(
        List.of("2026.1020.133338", "2026.1020.140000"),
        ledger.finalizedVersions(repo).stream().sorted(VersionOrder.ORDER).toList());
  }

  @Test
  void aReleaseRecordedTwiceIsOneRowAndWaitsUntilMarked() {
    String repo = repository();
    Published release = new Published(repo, "2026.1020.130544", "docker", "qits/" + repo);

    ledger.recordPublished(release);
    ledger.recordPublished(release);

    assertEquals(List.of(release), ledger.unadopted(repo));
    assertEquals(List.of(), ledger.adoptedVersions("docker", "qits/" + repo));

    ledger.markAdopted(release);
    ledger.markAdopted(release);

    assertEquals(List.of(), ledger.unadopted(repo));
    assertEquals(List.of("2026.1020.130544"), ledger.adoptedVersions("docker", "qits/" + repo));
  }

  @Test
  void packagesAndRepositoriesAreKeptApart() {
    String repo = repository();
    Published image = new Published(repo, "1", "docker", "qits/" + repo);
    Published jar = new Published(repo, "1", "maven", "qits/" + repo);
    ledger.recordPublished(image);
    ledger.recordPublished(jar);
    ledger.markAdopted(image);

    assertEquals(List.of(jar), ledger.unadopted(repo));
    assertEquals(List.of(), ledger.unadopted(repository()));
    assertEquals(List.of(), ledger.adoptedVersions("maven", "qits/" + repo));
  }
}
