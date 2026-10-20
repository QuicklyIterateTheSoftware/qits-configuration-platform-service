package eu.wohlben.qits.configuration.control;

import eu.wohlben.qits.configuration.persistence.ReleaseFinalizedRepository;
import eu.wohlben.qits.configuration.persistence.ReleasePublishedRepository;
import eu.wohlben.qits.db.DbRetry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;

/**
 * THE TWO HALVES OF A RELEASE'S ADOPTION, written down as they arrive (qits-1472): what qits-ci
 * announced as published, and what qits-projects announced as finalized.
 *
 * <p><b>Why a ledger at all.</b> A pin used to move the moment qits-ci said {@code SoftwareRelease}.
 * That announcement comes after RELEASED and before the post-release gates — the publish run green,
 * the deployment active — and a RELEASED request may never finalize: it goes OBSOLETE when a newer
 * request folds its tag in. FINALIZATION is the platform's green light for using a release, and
 * neither event carries the other's facts: a {@code SoftwareRelease} names a package and no state, a
 * {@code ReleaseRequestStateChanged} names a state and no package. So each half is recorded here and
 * the pin moves when the second one lands, in either order. {@link AdoptionRule} is the decision; this
 * class is only the storage it decides over.
 *
 * <p><b>EVERY METHOD IS A TRANSACTION OF ITS OWN, and that is load-bearing twice.</b> First, its
 * callers are the durable listeners, which run inside the funnel's claim on the EVENTSTREAM datasource
 * — the reason {@link ConfigurationService#declaredPins} gives for bracketing a read. Second, and
 * specific to this class: <b>a record is committed BEFORE the other half is read</b>. A {@code
 * SoftwareRelease} and its FINALIZED can be consumed at the same moment on two threads (two listeners,
 * each with a socket and a sweeper). If each wrote its half and read the other's in ONE transaction,
 * both reads could run before either write committed, and the release would be stranded with both
 * halves present. Committing the write first means whichever reads second sees the other's row — at
 * worst both see each other and the pin is written twice, which the idempotent upsert makes a no-op.
 *
 * <p>Nothing here moves a pin; that is the listeners' write through {@link ConfigurationService#upsert},
 * unchanged. {@link #markAdopted} is called AFTER those writes commit, so a crash in between leaves the
 * release unadopted and the redelivered frame repeats the writes rather than skipping them.
 */
@ApplicationScoped
public class ReleaseLedger {

  /** One announced artifact, by the four facts a {@code SoftwareRelease} carries. */
  public record Published(
      String repository, String version, String packageType, String packageName) {}

  @Inject ReleaseFinalizedRepository finalized;

  @Inject ReleasePublishedRepository published;

  /** Record an announced release; a repeat writes nothing. */
  public void recordPublished(Published release) {
    DbRetry.runInNewTx(
        "record " + release.repository() + " " + release.version() + " published "
            + release.packageType() + " " + release.packageName(),
        () -> {
          published.insertIfAbsent(
              release.repository(),
              release.version(),
              release.packageType(),
              release.packageName(),
              Instant.now());
          published.flush();
        });
  }

  /**
   * Record a finalization; a repeat writes nothing.
   *
   * @return whether this call wrote the row — false for the second announcement of the same pair
   */
  public boolean recordFinalized(String repository, String version) {
    return DbRetry.inNewTx(
        "record " + repository + " " + version + " finalized",
        () -> {
          boolean written = finalized.insertIfAbsent(repository, version, Instant.now());
          finalized.flush();
          return written;
        });
  }

  /** Every finalized version of one repository. */
  public List<String> finalizedVersions(String repository) {
    return DbRetry.inNewTx(
        "read the finalized versions of " + repository, () -> finalized.listVersions(repository));
  }

  /** Every recorded release of one repository that has not been adopted yet. */
  public List<Published> unadopted(String repository) {
    return DbRetry.inNewTx(
        "read the unadopted releases of " + repository,
        () ->
            published.listUnadopted(repository).stream()
                .map(
                    row ->
                        new Published(
                            row.repository, row.version, row.packageType, row.packageName))
                .toList());
  }

  /** Every version of one package already adopted — what {@link AdoptionRule#wouldRewind} reads. */
  public List<String> adoptedVersions(String packageType, String packageName) {
    return DbRetry.inNewTx(
        "read the adopted versions of " + packageType + " " + packageName,
        () -> published.listAdoptedVersions(packageType, packageName));
  }

  /** Mark one release adopted. Called after its pins are written; a repeat changes nothing. */
  public void markAdopted(Published release) {
    DbRetry.runInNewTx(
        "mark " + release.repository() + " " + release.version() + " "
            + release.packageType() + " " + release.packageName() + " adopted",
        () -> {
          published.markAdopted(
              release.repository(),
              release.version(),
              release.packageType(),
              release.packageName(),
              Instant.now());
          published.flush();
        });
  }
}
