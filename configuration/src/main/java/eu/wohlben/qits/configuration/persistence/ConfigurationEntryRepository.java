package eu.wohlben.qits.configuration.persistence;

import eu.wohlben.qits.configuration.entity.ConfigurationEntry;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The head rows: one per (env, application, key) that currently has a value.
 *
 * <p>Ordering is {@code (env, application, key)} everywhere a list is returned. A resolved read is
 * layered into somebody else's configuration, where order means nothing, but a person diffing two
 * environments reads these lists side by side — and an unordered listing makes that diff noise. Env
 * leads for the same reason it leads in the index: it is the outermost thing a reader groups by.
 *
 * <p>The method names avoid Panache's own ({@code find}, {@code listAll}): an overload that differs
 * from an inherited varargs method only in arity is a coin toss to read, and this repository's
 * callers should never have to check which one they got.
 */
@ApplicationScoped
public class ConfigurationEntryRepository
    implements PanacheRepositoryBase<ConfigurationEntry, UUID> {

  /** The one current row for a key in one env, or empty. */
  public Optional<ConfigurationEntry> findEntry(String env, String application, String key) {
    return find("env = ?1 and application = ?2 and entryKey = ?3", env, application, key)
        .firstResultOptional();
  }

  /** Every current entry of one application in one env, by key. */
  public List<ConfigurationEntry> listByApplication(String env, String application) {
    return list("env = ?1 and application = ?2 order by entryKey", env, application);
  }

  /** Every current entry, env first, then application. */
  public List<ConfigurationEntry> listEverything() {
    return listAll(Sort.by("env").and("application").and("entryKey"));
  }

  /**
   * Every env this store holds anything about, sorted.
   *
   * <p><b>A union over BOTH tables, not a distinct over the heads.</b> An env whose entries have all
   * been deleted is still an env this store has something to say about — its history is intact and
   * the question "what happened to that environment's configuration" is exactly the one a listing
   * that hid it would refuse to answer. It is the same doctrine the application listing follows, one
   * level up.
   */
  public List<String> listDistinctEnvs() {
    TreeSet<String> envs =
        new TreeSet<>(
            getEntityManager()
                .createQuery("select distinct e.env from ConfigurationEntry e", String.class)
                .getResultList());
    envs.addAll(
        getEntityManager()
            .createQuery("select distinct r.env from ConfigurationRevision r", String.class)
            .getResultList());
    return List.copyOf(envs);
  }
}
