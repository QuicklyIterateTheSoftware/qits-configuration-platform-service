package eu.wohlben.qits.configuration.persistence;

import eu.wohlben.qits.configuration.entity.ReleaseFinalized;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The finalized releases, one row per (repository, version). */
@ApplicationScoped
public class ReleaseFinalizedRepository implements PanacheRepositoryBase<ReleaseFinalized, UUID> {

  /**
   * Record one finalization, or do nothing when it is already recorded.
   *
   * <p><b>Native, and {@code on conflict do nothing}, on purpose.</b> FINALIZED is announced twice as
   * often as not, under distinct event ids a few milliseconds apart, and the two can be offered on
   * two threads at once (the live socket and the catch-up sweep). A read-then-persist would let both
   * see "absent" and the second insert die on the unique constraint; this cannot.
   *
   * @return whether a row was written — false for a repeat
   */
  public boolean insertIfAbsent(String repository, String version, Instant at) {
    return getEntityManager()
            .createNativeQuery(
                "insert into configuration_release_finalized"
                    + " (id, repository, version, finalized_at) values (?1, ?2, ?3, ?4)"
                    + " on conflict (repository, version) do nothing")
            .setParameter(1, UUID.randomUUID())
            .setParameter(2, repository)
            .setParameter(3, version)
            .setParameter(4, at)
            .executeUpdate()
        > 0;
  }

  /** Every finalized version of one repository, in no particular order — they are ranked in Java. */
  public List<String> listVersions(String repository) {
    return getEntityManager()
        .createQuery(
            "select f.version from ReleaseFinalized f where f.repository = ?1", String.class)
        .setParameter(1, repository)
        .getResultList();
  }
}
