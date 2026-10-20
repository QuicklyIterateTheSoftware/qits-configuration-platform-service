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
 * One artifact qits-ci announced as released, and whether it has been ADOPTED yet.
 *
 * <p>A {@code SoftwareRelease} is recorded here on arrival and moves no pin by itself: it waits for a
 * {@link ReleaseFinalized} row of the same repository at or above its version. {@link #adoptedAt} is
 * set once that has happened and the pins are written (or deliberately left, because a higher version
 * of the same package was adopted first) — see {@code control/ReleaseLedger}.
 */
@Entity
@Table(
    name = "configuration_release_published",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_configuration_release_published",
            columnNames = {"repository", "version", "package_type", "package_name"}))
public class ReleasePublished extends PanacheEntityBase {

  @Id public UUID id;

  /** {@code SoftwareRelease.repository}: the same row id {@link ReleaseFinalized#repository} holds. */
  @Column(nullable = false, length = 255)
  public String repository;

  @Column(nullable = false, length = 128)
  public String version;

  @Column(name = "package_type", nullable = false, length = 32)
  public String packageType;

  @Column(name = "package_name", nullable = false, length = 255)
  public String packageName;

  @Column(name = "recorded_at", nullable = false)
  public Instant recordedAt;

  /** Null while the release waits for its finalization. */
  @Column(name = "adopted_at")
  public Instant adoptedAt;
}
