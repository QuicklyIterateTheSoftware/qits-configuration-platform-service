package eu.wohlben.qits.configuration.control;

import eu.wohlben.qits.configuration.dto.EntryCollectionReportDto;
import eu.wohlben.qits.configuration.entity.ConfigurationDeclaration;
import eu.wohlben.qits.configuration.entity.ConfigurationDeclarationRevision;
import eu.wohlben.qits.configuration.entity.ConfigurationDeclaredKey;
import eu.wohlben.qits.configuration.persistence.ConfigurationDeclarationRepository;
import eu.wohlben.qits.configuration.persistence.ConfigurationEntryRepository;
import eu.wohlben.qits.configuration.persistence.DeclarationRevisionRepository;
import eu.wohlben.qits.configuration.persistence.DeclaredKeyRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.jboss.logging.Logger;

/**
 * THE ENTRY COLLECTOR: which stored entries belong to a key no version anybody could run still
 * declares, and their removal.
 *
 * <p><b>It is not the {@code orphaned} flag, and must not become it.</b> That flag is judged against
 * the governing declaration alone and is a question for a person — a row it marks may be a key set
 * early for a version not released yet, or one the version being rolled back to still reads. This
 * rule is judged against every version the platform is RUNNING OR COULD ROLL BACK TO, which it cannot
 * know itself: the caller hands it the deployer's pins ({@code GET /deployments/api/pins}, verbatim),
 * on the pattern every collector on the platform follows — the component holding a credential for
 * every peer reads the evidence and passes it in.
 *
 * <p><b>The rule, and the first reason that holds is the one counted.</b> With P the pinned versions
 * of the entry's application, an entry is KEPT when:
 *
 * <ol>
 *   <li>{@code unpinned} — P is empty. Nothing is known about what runs, so nothing is judged.
 *   <li>{@code undeclaredPinnedVersion} — some version in P has no stored declaration. That version
 *       resolves entries-only, so every entry of the application may be one it reads.
 *   <li>{@code pinned} — a declaration of a version in P states the key, whatever its type.
 *   <li>{@code inFlight} — a declaration received AFTER the newest pinned one states it: a version
 *       seeded and not yet active, whose entries were set ahead of it.
 *   <li>{@code neverDeclared} — no declaration of any version ever stated it. This rule collects keys
 *       that were declared and then retired; a key nobody ever declared is the flag's question.
 *   <li>{@code staged} — the entry was written after the newest declaration arrived, so it may be
 *       staged for a version whose declaration has not been posted yet.
 * </ol>
 *
 * Otherwise it is collected, {@code retired}, naming the newest version that declared it.
 *
 * <p><b>"Received after" is the intake log's order</b>, the one {@link
 * ConfigurationDeclarationRepository#governingOf} walks: this service has no opinion about how version
 * strings compare, so a declaration's place is the seq of its newest live intake.
 *
 * <p><b>It fails closed, per application.</b> An application whose declarations cannot be read keeps
 * every entry and is named in the report, and the others are judged as usual. Each deletion is its
 * own transaction through {@link ConfigurationService#deleteIfUnchanged} — the ordinary delete, with
 * the author recorded and the value kept in the history — so one failure rolls nothing else back.
 *
 * <p>No value is read, logged or reported: entries carry credentials, and a key is enough to say
 * what was removed.
 */
@ApplicationScoped
public class RetiredEntryCollector {

  private static final Logger LOG = Logger.getLogger(RetiredEntryCollector.class);

  static final String RETIRED = "retired";

  @Inject ConfigurationEntryRepository entries;

  @Inject ConfigurationDeclarationRepository declarations;

  @Inject DeclarationRevisionRepository intake;

  @Inject DeclaredKeyRepository declaredKeys;

  @Inject ConfigurationService configuration;

  /** One stored entry, as much of it as the rule reads — deliberately not its value. */
  record Stored(
      String application, String env, String key, Instant updatedAt, long headRevision) {}

  /**
   * One live declaration of an application: its version, its place in the intake log, when that
   * intake arrived, and the keys it states.
   */
  record Declared(String version, long received, Instant receivedAt, Set<String> keys) {}

  /** Where the rule reads an application's declarations from; a throw fails that application. */
  @FunctionalInterface
  interface DeclarationSource {
    List<Declared> of(String application);
  }

  enum Reason {
    UNPINNED,
    UNDECLARED_PINNED_VERSION,
    PINNED,
    IN_FLIGHT,
    NEVER_DECLARED,
    STAGED,
    RETIRED
  }

  record Verdict(Stored entry, Reason reason, String lastDeclaredBy) {}

  /** The whole judgement, before anything is deleted. */
  record Judgement(List<Verdict> verdicts, List<EntryCollectionReportDto.Failure> errors) {}

  /**
   * Judge every stored entry and, unless this is a dry run, delete the retired ones.
   *
   * @param pins application name to its pinned versions, the union of every pins item naming it
   * @param actor the principal recorded as the author of each deletion
   */
  public EntryCollectionReportDto collect(
      Map<String, Set<String>> pins, boolean dryRun, String actor) {
    List<Stored> stored =
        entries.listEverything().stream()
            .map(
                entry ->
                    new Stored(
                        entry.application,
                        entry.env,
                        entry.entryKey,
                        entry.updatedAt,
                        entry.headRevision))
            .toList();
    Judgement judgement = judge(stored, pins, this::declaredOf);

    int[] kept = new int[Reason.values().length];
    List<EntryCollectionReportDto.Removed> removed = new ArrayList<>();
    List<EntryCollectionReportDto.Failure> errors = new ArrayList<>(judgement.errors());
    for (Verdict verdict : judgement.verdicts()) {
      if (verdict.reason() != Reason.RETIRED) {
        kept[verdict.reason().ordinal()]++;
        continue;
      }
      Stored entry = verdict.entry();
      if (!dryRun) {
        try {
          if (!configuration.deleteIfUnchanged(
              entry.env(), entry.application(), entry.key(), entry.headRevision(), actor)) {
            errors.add(
                new EntryCollectionReportDto.Failure(
                    entry.application(),
                    entry.env() + "/" + entry.key() + " changed after it was judged; kept"));
            continue;
          }
        } catch (RuntimeException failed) {
          errors.add(
              new EntryCollectionReportDto.Failure(
                  entry.application(),
                  entry.env() + "/" + entry.key() + " was not removed: " + describe(failed)));
          continue;
        }
      }
      LOG.infof(
          "%s retired entry %s/%s/%s, last declared by %s",
          dryRun ? "Would collect" : "Collected",
          entry.application(),
          entry.env(),
          entry.key(),
          verdict.lastDeclaredBy());
      removed.add(
          new EntryCollectionReportDto.Removed(
              entry.application(), entry.env(), entry.key(), RETIRED, verdict.lastDeclaredBy()));
    }
    EntryCollectionReportDto report =
        new EntryCollectionReportDto(
            dryRun,
            judgement.verdicts().size(),
            List.copyOf(removed),
            new EntryCollectionReportDto.Kept(
                kept[Reason.UNPINNED.ordinal()],
                kept[Reason.UNDECLARED_PINNED_VERSION.ordinal()],
                kept[Reason.PINNED.ordinal()],
                kept[Reason.IN_FLIGHT.ordinal()],
                kept[Reason.NEVER_DECLARED.ordinal()],
                kept[Reason.STAGED.ordinal()]),
            List.copyOf(errors));
    LOG.infof(
        "Entry collection%s: examined %d, removed %d, errors %d",
        dryRun ? " (dry run)" : "",
        report.examined(),
        report.removed().size(),
        report.errors().size());
    return report;
  }

  // ---------------------------------------------------------------- the rule

  /**
   * Every entry's verdict. Declarations are read once per application, and only for applications
   * that are pinned at all — an unpinned one is kept before its declarations matter.
   */
  static Judgement judge(
      List<Stored> stored, Map<String, Set<String>> pins, DeclarationSource source) {
    Map<String, List<Stored>> byApplication = new LinkedHashMap<>();
    for (Stored entry : stored) {
      byApplication.computeIfAbsent(entry.application(), name -> new ArrayList<>()).add(entry);
    }
    List<Verdict> verdicts = new ArrayList<>(stored.size());
    List<EntryCollectionReportDto.Failure> errors = new ArrayList<>();
    byApplication.forEach(
        (application, rows) -> {
          Set<String> pinned = pins.getOrDefault(application, Set.of());
          List<Declared> declared = List.of();
          if (!pinned.isEmpty()) {
            try {
              declared = source.of(application);
            } catch (RuntimeException failed) {
              // Fail closed: no verdict for any of this application's entries, and none deleted.
              errors.add(
                  new EntryCollectionReportDto.Failure(
                      application,
                      "declarations could not be read, every entry kept: " + describe(failed)));
              return;
            }
          }
          for (Stored entry : rows) {
            verdicts.add(verdict(entry, pinned, declared));
          }
        });
    return new Judgement(List.copyOf(verdicts), List.copyOf(errors));
  }

  /** One entry against its application's pins and declarations, in the order the class states. */
  static Verdict verdict(Stored entry, Set<String> pinned, List<Declared> declared) {
    if (pinned.isEmpty()) {
      return new Verdict(entry, Reason.UNPINNED, null);
    }
    Map<String, Declared> byVersion = new HashMap<>();
    for (Declared each : declared) {
      byVersion.put(each.version(), each);
    }
    if (!byVersion.keySet().containsAll(pinned)) {
      return new Verdict(entry, Reason.UNDECLARED_PINNED_VERSION, null);
    }
    long newestPinned = Long.MIN_VALUE;
    for (String version : pinned) {
      Declared each = byVersion.get(version);
      if (each.keys().contains(entry.key())) {
        return new Verdict(entry, Reason.PINNED, null);
      }
      newestPinned = Math.max(newestPinned, each.received());
    }
    long pinnedHorizon = newestPinned;
    if (declared.stream()
        .anyMatch(each -> each.received() > pinnedHorizon && each.keys().contains(entry.key()))) {
      return new Verdict(entry, Reason.IN_FLIGHT, null);
    }
    Optional<Declared> lastStating =
        declared.stream()
            .filter(each -> each.keys().contains(entry.key()))
            .max(Comparator.comparingLong(Declared::received));
    if (lastStating.isEmpty()) {
      return new Verdict(entry, Reason.NEVER_DECLARED, null);
    }
    Declared newest = declared.stream().max(Comparator.comparingLong(Declared::received)).get();
    if (entry.updatedAt().isAfter(newest.receivedAt())) {
      return new Verdict(entry, Reason.STAGED, null);
    }
    return new Verdict(entry, Reason.RETIRED, lastStating.get().version());
  }

  // ---------------------------------------------------------------- the reads

  /**
   * One application's live declarations with their intake place and keys: three reads, whatever the
   * number of versions.
   *
   * <p>A declaration with no live intake row cannot be placed in the order the rule depends on, so it
   * fails the application rather than being guessed at.
   */
  private List<Declared> declaredOf(String application) {
    Map<String, ConfigurationDeclarationRevision> newestIntake = new HashMap<>();
    for (ConfigurationDeclarationRevision revision : intake.listByApplication(application)) {
      if (!revision.deleted) {
        newestIntake.merge(
            revision.version, revision, (a, b) -> a.seq >= b.seq ? a : b);
      }
    }
    Map<String, Set<String>> keysByVersion = new HashMap<>();
    for (ConfigurationDeclaredKey key : declaredKeys.listByApplication(application)) {
      keysByVersion.computeIfAbsent(key.version, version -> new TreeSet<>()).add(key.declaredKey);
    }
    List<Declared> declared = new ArrayList<>();
    for (ConfigurationDeclaration declaration : declarations.listByApplication(application)) {
      ConfigurationDeclarationRevision received = newestIntake.get(declaration.version);
      if (received == null) {
        throw new IllegalStateException(
            "declaration " + application + "@" + declaration.version + " has no intake record");
      }
      declared.add(
          new Declared(
              declaration.version,
              received.seq,
              received.receivedAt,
              Set.copyOf(keysByVersion.getOrDefault(declaration.version, Set.of()))));
    }
    return declared;
  }

  private static String describe(RuntimeException failed) {
    String message = failed.getMessage();
    return failed.getClass().getSimpleName()
        + (message == null || message.isBlank() ? "" : ": " + message);
  }
}
