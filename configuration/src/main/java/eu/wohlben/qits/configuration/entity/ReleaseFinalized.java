package eu.wohlben.qits.configuration.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * One finalized release: qits-projects said {@code FINALIZED} for this (repository, version).
 *
 * <p>The green light a published artifact of the same repository waits on before it may move a pin
 * — see {@code control/ReleaseLedger}. Written once per pair however often the state is announced;
 * the insert is {@code on conflict do nothing} against the unique constraint, not a read-then-write.
 */
@Entity
@Table(
    name = "configuration_release_finalized",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_configuration_release_finalized",
            columnNames = {"repository", "version"}))
public class ReleaseFinalized extends PanacheEntityBase {

  @Id public UUID id;

  /** qits-projects' repository row id, as {@code ReleaseRequestStateChanged.repoId} carries it. */
  @Column(nullable = false, length = 255)
  public String repository;

  @Column(nullable = false, length = 128)
  public String version;

  @Column(name = "finalized_at", nullable = false)
  public Instant finalizedAt;
}
