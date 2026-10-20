package eu.wohlben.qits.configuration.persistence;

import eu.wohlben.qits.configuration.entity.ReleasePublished;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The announced releases, one row per (repository, version, package type, package name). */
@ApplicationScoped
public class ReleasePublishedRepository implements PanacheRepositoryBase<ReleasePublished, UUID> {

  /**
   * Record one announced release, or do nothing when it is already recorded — a redelivered {@code
   * SoftwareRelease} is the same row. Native {@code on conflict do nothing} for the reason {@link
   * ReleaseFinalizedRepository#insertIfAbsent} gives.
   */
  public void insertIfAbsent(
      String repository, String version, String packageType, String packageName, Instant at) {
    getEntityManager()
        .createNativeQuery(
            "insert into configuration_release_published"
                + " (id, repository, version, package_type, package_name, recorded_at)"
                + " values (?1, ?2, ?3, ?4, ?5, ?6)"
                + " on conflict (repository, version, package_type, package_name) do nothing")
        .setParameter(1, UUID.randomUUID())
        .setParameter(2, repository)
        .setParameter(3, version)
        .setParameter(4, packageType)
        .setParameter(5, packageName)
        .setParameter(6, at)
        .executeUpdate();
  }

  /** Every release of one repository still waiting to be adopted. */
  public List<ReleasePublished> listUnadopted(String repository) {
    return list("repository = ?1 and adoptedAt is null", repository);
  }

  /** One recorded release, adopted or not, or null when it was never recorded. */
  public ReleasePublished findRelease(
      String repository, String version, String packageType, String packageName) {
    return find(
            "repository = ?1 and version = ?2 and packageType = ?3 and packageName = ?4",
            repository,
            version,
            packageType,
            packageName)
        .firstResult();
  }

  /** Every version of one package that has already been adopted, from whichever repository. */
  public List<String> listAdoptedVersions(String packageType, String packageName) {
    return getEntityManager()
        .createQuery(
            "select p.version from ReleasePublished p where p.packageType = ?1"
                + " and p.packageName = ?2 and p.adoptedAt is not null",
            String.class)
        .setParameter(1, packageType)
        .setParameter(2, packageName)
        .getResultList();
  }

  /** Mark one release adopted. A release already marked keeps its first timestamp. */
  public void markAdopted(
      String repository, String version, String packageType, String packageName, Instant at) {
    update(
        "adoptedAt = ?1 where repository = ?2 and version = ?3 and packageType = ?4"
            + " and packageName = ?5 and adoptedAt is null",
        at,
        repository,
        version,
        packageType,
        packageName);
  }
}
