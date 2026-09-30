package eu.wohlben.qits.configuration.persistence;

import eu.wohlben.qits.configuration.entity.ConfigurationDeclaredKey;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.UUID;

/** The parsed keys of the declaration documents, one row per (application, version, key). */
@ApplicationScoped
public class DeclaredKeyRepository
    implements PanacheRepositoryBase<ConfigurationDeclaredKey, UUID> {

  /** Every key one version declares, by key — the order a resolved read merges them in. */
  public List<ConfigurationDeclaredKey> listOf(String application, String version) {
    return list(
        "application = ?1 and version = ?2 order by declaredKey", application, version);
  }

  /**
   * Every key every live version of one application declares — the entry collector's read, which
   * judges a key against all of an application's declarations at once and would otherwise ask this
   * once per version.
   */
  public List<ConfigurationDeclaredKey> listByApplication(String application) {
    return list("application = ?1 order by version, declaredKey", application);
  }

  /**
   * THE REVERSE QUESTION: every declared key that carries a version of one package.
   *
   * <p>It is the question qits-artifacts' collector asks — "who runs a version of {@code
   * qits/workspace}, so which tags may I not delete" — and the reason this table has an index on the
   * pair. Today that answer comes from {@code control/ImagePins}, a hand-maintained list of four
   * mappings that a fifth consumer joins by somebody remembering to edit it. This method is the same
   * answer derived from what the applications themselves declared, for every application at once.
   *
   * <p><b>It is the listener's match now</b>, and that is the wave the index was cut for: a
   * {@code SoftwareRelease} names a {@code (packageType, packageName)} and this is the question
   * "which application asked to be told about that". The caller keeps only the keys of the GOVERNING
   * declaration — a version this application no longer stands behind still has its rows here, and
   * they are history rather than instructions.
   */
  public List<ConfigurationDeclaredKey> listByPackage(String packageType, String packageName) {
    return list(
        "packageType = ?1 and packageName = ?2 order by application, version, declaredKey",
        packageType,
        packageName);
  }

  /**
   * THE SAME QUESTION WITHOUT A NAME: every declared key carrying a version of ANY package of one
   * type.
   *
   * <p>The pin report asks it, because {@code GET /pins} answers about every image at once and has
   * no name to narrow by — where the listener arrives holding the coordinate a release just named.
   * It reads the same index: {@code package_type} leads it, so this is a range over one type's rows
   * rather than a scan of every key ever declared.
   *
   * <p>Rows of another type are excluded rather than reported with an empty image, which is what
   * keeps the report a statement about container images: a {@code binary} coordinate is a real
   * declaration and a thing qits-artifacts' image collector cannot act on.
   */
  public List<ConfigurationDeclaredKey> listByPackageType(String packageType) {
    return list(
        "packageType = ?1 order by application, version, declaredKey", packageType);
  }

  /** Remove every key of one version, as its declaration is removed. */
  public long deleteOf(String application, String version) {
    return delete("application = ?1 and version = ?2", application, version);
  }
}
