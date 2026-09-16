package eu.wohlben.qits.configuration.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.configuration.dto.ApplicationEnvSummaryDto;
import eu.wohlben.qits.configuration.dto.ApplicationSummaryDto;
import eu.wohlben.qits.configuration.dto.ConfigurationEntryDto;
import eu.wohlben.qits.configuration.dto.DeclarationDto;
import eu.wohlben.qits.configuration.dto.DeclarationSummaryDto;
import eu.wohlben.qits.configuration.dto.ImagePinDto;
import eu.wohlben.qits.configuration.dto.ImportSummaryDto;
import eu.wohlben.qits.configuration.dto.ResolvedConfigurationDto;
import eu.wohlben.qits.configuration.entity.ConfigurationEntry;
import eu.wohlben.qits.configuration.entity.ConfigurationRevision;
import eu.wohlben.qits.configuration.error.BadRequestException;
import eu.wohlben.qits.configuration.error.ConflictException;
import eu.wohlben.qits.configuration.error.DeclarationParseException;
import eu.wohlben.qits.configuration.error.NotFoundException;
import eu.wohlben.qits.configuration.error.UnprocessableEntityException;
import eu.wohlben.qits.configuration.persistence.ConfigurationEntryRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The write seam, against a real PostgreSQL — embedded, spawned from a Maven artifact, never a
 * container.
 *
 * <p>Every test uses an application name of its own. The suite shares one database across classes
 * (Flyway cleans at start, not between tests), so a shared name would make one test's rows another
 * test's surprise.
 *
 * <p><b>Every call names an env, because every method does.</b> {@link #ENV} is the suite's own, and
 * a value read back under another env is the thing {@link #anEnvIsPartOfTheIdentityOfAnEntry}
 * refuses.
 *
 * <p><b>ONE TRAP, MEASURED RATHER THAN FEARED: do not read a row through {@link
 * ConfigurationService#require} BEFORE writing it and then read it again after.</b> Every write here
 * runs in {@code DbRetry.inNewTx}, which gets a persistence context of its own; the reads run in the
 * test method's ambient one. So a read taken before the write leaves that instance in the ambient
 * first-level cache, and the read after it comes back from there — showing the value the write
 * replaced, while the row in the database is correct (confirmed against the raw row). Assert on what
 * the write RETURNED, or read the row through something transactional, as {@link
 * #anOperatorEditingAnImportedRowMakesItTheirs} does with the second import. Nothing about this is a
 * property of the service; it is a property of asking a session twice.
 */
@QuarkusTest
class ConfigurationServiceTest {

  /** The env this suite writes in. */
  private static final String ENV = "test";

  /** A second env, used only to prove that the first one's rows are not visible from it. */
  private static final String OTHER_ENV = "other";

  /**
   * The version the overlay tests declare under.
   *
   * <p>One literal for all of them, because a declaration is addressed by {@code (application,
   * version)} and every test here already owns an application of its own — so a shared version
   * collides with nothing, and a generated one would only make the assertions harder to read.
   */
  private static final String VERSION = "2026.907.1";

  @Inject ConfigurationService configuration;

  @Inject DeclarationService declarations;

  @Inject ConfigurationEntryRepository entries;

  @Test
  void aFirstWriteCreatesTheEntryAndOneRevision() {
    ConfigurationEntry entry =
        configuration.upsert(ENV, "app-first", "env.QITS_REGISTRY", "localhost:8081", "alice");

    assertEquals("app-first", entry.application);
    assertEquals("env.QITS_REGISTRY", entry.entryKey);
    assertEquals("localhost:8081", entry.entryValue);
    assertEquals(ConfigurationEntry.CLASS_PLAIN, entry.entryClass);
    assertEquals("alice", entry.updatedBy);

    List<ConfigurationRevision> history = configuration.history(ENV, "app-first");
    assertEquals(1, history.size());
    assertEquals("localhost:8081", history.get(0).entryValue);
    assertFalse(history.get(0).deleted);
    assertEquals(entry.headRevision, history.get(0).seq);
  }

  @Test
  void anIdenticalValueWritesNoRevision() {
    configuration.upsert(ENV, "app-idempotent", "env.A", "one", "alice");
    long afterFirst = configuration.resolve(ENV, "app-idempotent").headRevision();

    configuration.upsert(ENV, "app-idempotent", "env.A", "one", "bob");

    assertEquals(1, configuration.history(ENV, "app-idempotent").size());
    assertEquals(
        afterFirst,
        configuration.resolve(ENV, "app-idempotent").headRevision(),
        "an identical write must not move the head revision");
    assertEquals(
        "alice",
        configuration.require(ENV, "app-idempotent", "env.A").updatedBy,
        "an identical write must not re-attribute the entry either");
  }

  @Test
  void aChangedValueAppendsAndMovesTheHead() {
    ConfigurationEntry first = configuration.upsert(ENV, "app-change", "env.A", "one", "alice");
    ConfigurationEntry second = configuration.upsert(ENV, "app-change", "env.A", "two", "bob");

    assertTrue(second.headRevision > first.headRevision);
    assertEquals("two", second.entryValue);
    assertEquals("bob", second.updatedBy);

    List<ConfigurationRevision> history = configuration.history(ENV, "app-change");
    assertEquals(2, history.size(), "history is newest first");
    assertEquals("two", history.get(0).entryValue);
    assertEquals("one", history.get(1).entryValue);
  }

  @Test
  void aDeleteRemovesTheEntryAndKeepsTheHistory() {
    configuration.upsert(ENV, "app-delete", "env.A", "one", "alice");
    long beforeDelete = configuration.resolve(ENV, "app-delete").headRevision();

    configuration.delete(ENV, "app-delete", "env.A", "bob");

    assertThrows(NotFoundException.class, () -> configuration.require(ENV, "app-delete", "env.A"));
    assertTrue(configuration.entriesOf(ENV, "app-delete").isEmpty());

    List<ConfigurationRevision> history = configuration.history(ENV, "app-delete");
    assertEquals(2, history.size());
    assertTrue(history.get(0).deleted);
    assertNull(history.get(0).entryValue, "a deletion records no value; the previous one is above");
    assertEquals("bob", history.get(0).updatedBy);
    assertTrue(
        configuration.resolve(ENV, "app-delete").headRevision() > beforeDelete,
        "the head revision moves FORWARD on a delete — it comes from the log, not from the entries");
  }

  @Test
  void deletingWhatIsNotThereIsA404() {
    assertThrows(
        NotFoundException.class, () -> configuration.delete(ENV, "app-absent", "env.A", "alice"));
  }

  @Test
  void aResolvedReadIsTheFullyPrefixedPropertyMap() {
    configuration.upsert(ENV, "app-resolve", "env.QITS_A", "one", "alice");
    configuration.upsert(ENV, "app-resolve", "mounts[0]", "/data:/data", "alice");

    ResolvedConfigurationDto resolved = configuration.resolve(ENV, "app-resolve");

    assertEquals(2, resolved.properties().size());
    assertEquals(
        "one",
        resolved.properties().get("qits.platform.deployments.extras.app-resolve.env.QITS_A"));
    assertEquals(
        "/data:/data",
        resolved.properties().get("qits.platform.deployments.extras.app-resolve.mounts[0]"));
    assertTrue(resolved.headRevision() > 0);
  }

  @Test
  void anApplicationWithNothingStoredResolvesEmptyRatherThanFailing() {
    ResolvedConfigurationDto resolved = configuration.resolve(ENV, "app-unconfigured");

    assertEquals(0, resolved.headRevision());
    assertTrue(resolved.properties().isEmpty());
  }

  @Test
  void theListingKeepsAnApplicationWhoseEntriesHaveAllBeenDeleted() {
    configuration.upsert(ENV, "app-emptied", "env.A", "one", "alice");
    configuration.delete(ENV, "app-emptied", "env.A", "alice");

    ApplicationSummaryDto summary =
        configuration.applications().stream()
            .filter(each -> each.application().equals("app-emptied"))
            .findFirst()
            .orElseThrow();

    ApplicationEnvSummaryDto perEnv =
        summary.envs().stream()
            .filter(each -> each.env().equals(ENV))
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(
                        "the env the entries were deleted from must still be a row: the log is what"
                            + " the listing is built from"));

    assertEquals(0, perEnv.entries());
    assertTrue(perEnv.headRevision() > 0, "the history is still there and still says so");
  }

  /**
   * The claim the whole platform promotion rests on: two envs of one application are two entries,
   * two histories and two head revisions, and neither is visible from the other.
   *
   * <p>Written as a WRITE-THEN-MISS rather than as two writes compared, because the failure this
   * guards against is a query that forgot its env predicate — and such a query would pass any test
   * that only ever asserts what it just wrote.
   */
  @Test
  void anEnvIsPartOfTheIdentityOfAnEntry() {
    configuration.upsert(ENV, "app-two-envs", "env.A", "from-test", "alice");

    assertThrows(
        NotFoundException.class,
        () -> configuration.require(OTHER_ENV, "app-two-envs", "env.A"),
        "a value written in one env must not be readable from another");
    assertTrue(configuration.entriesOf(OTHER_ENV, "app-two-envs").isEmpty());
    assertEquals(
        0,
        configuration.resolve(OTHER_ENV, "app-two-envs").headRevision(),
        "an env with no history of this application is at revision 0, not at the other env's head");

    configuration.upsert(OTHER_ENV, "app-two-envs", "env.A", "from-other", "alice");

    assertEquals("from-test", configuration.require(ENV, "app-two-envs", "env.A").entryValue);
    assertEquals("from-other", configuration.require(OTHER_ENV, "app-two-envs", "env.A").entryValue);
    assertEquals(
        1,
        configuration.history(ENV, "app-two-envs").size(),
        "the second env's write is not in the first env's history");

    ApplicationSummaryDto summary =
        configuration.applications().stream()
            .filter(each -> each.application().equals("app-two-envs"))
            .findFirst()
            .orElseThrow();
    assertEquals(
        List.of(ENV, OTHER_ENV).stream().sorted().toList(),
        summary.envs().stream().map(ApplicationEnvSummaryDto::env).toList(),
        "the listing shows both envs of one application, sorted");
  }

  /**
   * The env vocabulary is the union of both tables, so an env that has only ever been deleted out of
   * still counts. That is the same doctrine the application listing follows, one level up, and it is
   * asserted here because the repository method is the only place it is implemented.
   */
  @Test
  void theEnvListingKeepsAnEnvWhoseEntriesHaveAllBeenDeleted() {
    configuration.upsert("gone", "app-env-gone", "env.A", "one", "alice");
    configuration.delete("gone", "app-env-gone", "env.A", "alice");

    assertTrue(
        entries.listDistinctEnvs().contains("gone"),
        "an env with no head rows left still has a history and is still an env");
    assertTrue(entries.listDistinctEnvs().contains(ENV));
  }

  /**
   * WHERE A RELEASE LANDS: every env this store knows anything about, and nothing else.
   *
   * <p>There used to be a floor under this — one configured legacy env, added whether the store knew
   * of it or not — because the fan-out replaced a writer that only ever wrote there. It left with
   * the property that named it. What it covered was a store nothing has been written into, where a
   * release now reaches nowhere; a platform imports its extras before it releases anything, so the
   * empty case is a store that is not in use rather than one being missed.
   */
  @Test
  void aReleaseFansOutOverEveryEnvTheStoreKnows() {
    configuration.upsert("pin-env-probe", "app-pin-envs", "env.A", "one", "alice");

    List<String> envs = configuration.pinEnvs();

    assertTrue(envs.contains("pin-env-probe"), "an env with rows is an env a release reaches");
    assertTrue(envs.contains(ENV), "and so is this suite's own, which has rows");
    assertEquals(envs.stream().sorted().distinct().toList(), envs, "sorted, and each env once");
  }

  @Test
  void theEnvGrammarIsEnforcedOnTheWritePath() {
    assertThrows(
        BadRequestException.class,
        () -> configuration.upsert("Not_An_Env", "app-env-guard", "env.A", "x", null));
    assertThrows(
        BadRequestException.class,
        () -> configuration.resolve("", "app-env-guard"),
        "a blank env is refused rather than silently meaning some default one — there is no env a"
            + " caller can omit any more");
  }

  @Test
  void anImportWritesTheLinesItRecognisesAndCountsTheRest() {
    ImportSummaryDto summary =
        configuration.importProperties(ENV, 
            """
            # the deployer's config volume
            qits.platform.deployments.orchestrator=swarm
            qits.platform.deployments.extras.app-import.env.QITS_A=one
            qits.platform.deployments.extras.app-import.aliases[0]=app.dev.localhost
            """,
            "alice");

    assertEquals(2, summary.imported());
    assertEquals(0, summary.unchanged());
    assertEquals(2, summary.ignored(), "the comment and the deployer's own unrelated key");
    assertEquals(2, configuration.entriesOf(ENV, "app-import").size());
  }

  @Test
  void reImportingTheSameFileWritesNothingAtAll() {
    String file =
        """
        qits.platform.deployments.extras.app-reimport.env.QITS_A=one
        qits.platform.deployments.extras.app-reimport.env.QITS_B=two
        """;
    configuration.importProperties(ENV, file, "alice");
    long afterFirst = configuration.resolve(ENV, "app-reimport").headRevision();

    ImportSummaryDto again = configuration.importProperties(ENV, file, "alice");

    assertEquals(0, again.imported());
    assertEquals(2, again.unchanged());
    assertEquals(
        afterFirst,
        configuration.resolve(ENV, "app-reimport").headRevision(),
        "an unchanged import leaves the log exactly as it found it");
    assertEquals(2, configuration.history(ENV, "app-reimport").size());
  }

  @Test
  void aMalformedLineLeavesTheWholeImportUnwritten() {
    assertThrows(
        BadRequestException.class,
        () ->
            configuration.importProperties(ENV, 
                """
                qits.platform.deployments.extras.app-atomic.env.QITS_A=one
                qits.platform.deployments.extras.app-atomic.volumes[0]=nope
                """,
                "alice"));

    assertTrue(
        configuration.entriesOf(ENV, "app-atomic").isEmpty(),
        "the good line ahead of the bad one must not have survived");
  }

  /**
   * The pin report, walked in one method on purpose: "nothing pinned is an empty answer" is a claim
   * about a store no pin has been written into, and this suite shares one database across classes —
   * so it is asserted before this test writes rather than from a second method that might run after
   * it. <b>The declared half is walked in the same method for the same reason</b>: a declaration is
   * store-wide state, and a second method posting one would decide this one's answer depending on
   * which ran first.
   *
   * <p>The application names here are the platform's real ones, because the authored list is a
   * compile-time constant and there is no pin on an invented application to write. Nothing else in
   * this module touches them.
   */
  @Test
  void thePinReportMergesWhatIsDeclaredWithWhatIsAuthoredAndOmitsWhatWasNeverReleased() {
    assertTrue(
        configuration.imagePins().isEmpty(),
        "an environment that has released nothing pins nothing — not a row per mapping with no version");

    configuration.upsert(ENV,
        "qits-projects", "env.QITS_PROJECTS_AGENT_IMAGE_VERSION", "2026.904.160152", "alice");
    configuration.upsert(ENV,
        "qits-projects", "env.QITS_PROJECTS_REFINEMENT_IMAGE_VERSION", "2026.904.160522", "alice");

    assertEquals(
        List.of(
            new ImagePinDto(
                "qits/project-agent",
                "2026.904.160152",
                "qits-projects",
                "env.QITS_PROJECTS_AGENT_IMAGE_VERSION"),
            new ImagePinDto(
                "qits/workspace",
                "2026.904.160522",
                "qits-projects",
                "env.QITS_PROJECTS_REFINEMENT_IMAGE_VERSION")),
        configuration.imagePins(),
        "both authored mappings, each with the version its entry holds");

    // A CONSUMER NOBODY HAS EVER WRITTEN A PIN FOR, arriving through its own declaration: an
    // application says which image its version key carries, and the report answers for it with
    // nothing added to ImagePins.
    declare(
        "app-declared-pin",
        ConfigurationKeys.TARGET_ENVIRONMENT,
        """
        keys:
          env.QITS_DECLARED_IMAGE_VERSION:
            type: packageVersion
            package: { type: docker, name: qits/declared }
          env.QITS_DECLARED_BINARY_VERSION:
            type: packageVersion
            package: { type: binary, name: qits-declared-cli }
        """);
    configuration.upsert(
        ENV, "app-declared-pin", "env.QITS_DECLARED_IMAGE_VERSION", "2026.905.1", "the-release");
    configuration.upsert(
        ENV, "app-declared-pin", "env.QITS_DECLARED_BINARY_VERSION", "2026.905.2", "the-release");

    assertEquals(
        List.of(
            new ImagePinDto(
                "qits/declared", "2026.905.1", "app-declared-pin", "env.QITS_DECLARED_IMAGE_VERSION"),
            new ImagePinDto(
                "qits/project-agent",
                "2026.904.160152",
                "qits-projects",
                "env.QITS_PROJECTS_AGENT_IMAGE_VERSION"),
            new ImagePinDto(
                "qits/workspace",
                "2026.904.160522",
                "qits-projects",
                "env.QITS_PROJECTS_REFINEMENT_IMAGE_VERSION")),
        configuration.imagePins(),
        "the declared image joins the authored ones in the one order the contract names — and the"
            + " binary coordinate stays out of a report about container images");

    // AND A CONSUMER ADOPTING DECLARATIONS FOR A KEY THAT IS ALREADY AUTHORED: qits-projects now
    // declares the agent key itself, naming a renamed image. The pair is written once, under the
    // name its own application gave it.
    declare(
        "qits-projects",
        ConfigurationKeys.TARGET_ENVIRONMENT,
        """
        keys:
          env.QITS_PROJECTS_AGENT_IMAGE_VERSION:
            type: packageVersion
            package: { type: docker, name: qits/project-agent-next }
        """);

    List<ImagePinDto> shadowed = configuration.imagePins();
    assertEquals(
        3, shadowed.size(), "a declaration of an authored pair replaces its row rather than adding one");
    assertTrue(
        shadowed.contains(
            new ImagePinDto(
                "qits/project-agent-next",
                "2026.904.160152",
                "qits-projects",
                "env.QITS_PROJECTS_AGENT_IMAGE_VERSION")),
        "the image the application declares is the one the collector is told to protect");
    assertTrue(
        shadowed.stream().noneMatch(pin -> "qits/project-agent".equals(pin.image())),
        "and the authored name it shadows is not reported beside it");

    // TWO TIERS, TWO VERSIONS, TWO ROWS. The report is the UNION over envs, because its consumer is
    // deciding what it may delete out of one registry the whole platform shares: a version running
    // in a tier this report did not look at is a tag collected out from under a running container.
    configuration.upsert(
        OTHER_ENV,
        "qits-projects",
        "env.QITS_PROJECTS_REFINEMENT_IMAGE_VERSION",
        "2026.905.9",
        "alice");

    List<ImagePinDto> across = configuration.imagePins();
    assertTrue(
        across.contains(
            new ImagePinDto(
                "qits/workspace",
                "2026.904.160522",
                "qits-projects",
                "env.QITS_PROJECTS_REFINEMENT_IMAGE_VERSION")),
        "the version one tier holds is kept");
    assertTrue(
        across.contains(
            new ImagePinDto(
                "qits/workspace",
                "2026.905.9",
                "qits-projects",
                "env.QITS_PROJECTS_REFINEMENT_IMAGE_VERSION")),
        "and so is the version the other tier holds");

    // The same version in both tiers is ONE fact about the registry, not two: the wire shape has no
    // env in it, so a duplicated row would say nothing and be counted twice.
    configuration.upsert(
        OTHER_ENV,
        "qits-projects",
        "env.QITS_PROJECTS_AGENT_IMAGE_VERSION",
        "2026.904.160152",
        "alice");
    assertEquals(
        1,
        configuration.imagePins().stream()
            .filter(pin -> "qits/project-agent-next".equals(pin.image()))
            .count(),
        "two tiers on one version report one row");
  }

  @Test
  void theKeyGrammarIsEnforcedOnTheWritePath() {
    assertThrows(
        BadRequestException.class, () -> configuration.upsert(ENV, "app-guard", "volumes[0]", "x", null));
    assertThrows(
        BadRequestException.class, () -> configuration.upsert(ENV, "Bad-App", "env.A", "x", null));
    assertTrue(configuration.entriesOf(ENV, "app-guard").isEmpty());
  }

  // ------------------------------------------------------------ declarations and the overlay

  /** Seed one declaration and return the version it was recorded under. */
  private String declare(String application, String target, String document) {
    declarations.declare(application, VERSION, target, document, "pipeline");
    return VERSION;
  }

  private String property(String application, String key) {
    return ExtrasProperties.propertyName(application, key);
  }

  /**
   * THE MERGE ORDER, asserted in one method because it is one claim: a declared default is the
   * FLOOR, and everything stored stands on top of it.
   *
   * <p>Both stored classes are exercised — an operator's {@code plain} row and the import's {@code
   * imported} row — because "the default lost" has to be true of both, and a merge that consulted
   * the class would be the beginning of a read-time precedence this service deliberately does not
   * have.
   */
  @Test
  void aStoredValueBeatsADeclaredDefaultWhicheverWroteIt() {
    String version =
        declare(
            "app-overlay",
            ConfigurationKeys.TARGET_ENVIRONMENT,
            """
            keys:
              env.QITS_UNSET:
                type: string
                default: from-the-declaration
              env.QITS_OPERATOR:
                type: string
                default: from-the-declaration
              env.QITS_IMPORTED:
                type: string
                default: from-the-declaration
            """);

    configuration.upsert(ENV, "app-overlay", "env.QITS_OPERATOR", "from-the-operator", "alice");
    configuration.importProperties(
        ENV,
        "qits.platform.deployments.extras.app-overlay.env.QITS_IMPORTED=from-the-file\n",
        "bootstrap");

    Map<String, String> properties =
        configuration.resolve(ENV, "app-overlay", Optional.of(version)).properties();

    assertEquals(
        "from-the-declaration",
        properties.get(property("app-overlay", "env.QITS_UNSET")),
        "a key nobody set falls back to what the application declared");
    assertEquals(
        "from-the-operator",
        properties.get(property("app-overlay", "env.QITS_OPERATOR")),
        "an operator's value beats the default");
    assertEquals(
        "from-the-file",
        properties.get(property("app-overlay", "env.QITS_IMPORTED")),
        "so does an imported one — the class orders the two WRITES, not the read");
  }

  @Test
  void aDeclaredKeyWithNoDefaultIsAbsentRatherThanEmpty() {
    String version =
        declare(
            "app-nodefault",
            ConfigurationKeys.TARGET_ENVIRONMENT,
            "keys:\n  env.QITS_A:\n    type: string\n");

    assertFalse(
        configuration
            .resolve(ENV, "app-nodefault", Optional.of(version))
            .properties()
            .containsKey(property("app-nodefault", "env.QITS_A")),
        "the container gets no variable rather than an empty one");
  }

  @Test
  void aResolvedReadWithNoVersionIsExactlyTheEntriesAndNeverA404() {
    declare(
        "app-versionless",
        ConfigurationKeys.TARGET_ENVIRONMENT,
        "keys:\n  env.QITS_A:\n    type: string\n    default: from-the-declaration\n");
    configuration.upsert(ENV, "app-versionless", "env.QITS_B", "stored", "alice");

    ResolvedConfigurationDto resolved = configuration.resolve(ENV, "app-versionless");

    assertEquals(
        1,
        resolved.properties().size(),
        "the deployer does not pass a version yet, and until it does this read must not change at"
            + " all — a default appearing unasked would be a value nobody could account for");
    assertEquals("stored", resolved.properties().get(property("app-versionless", "env.QITS_B")));
  }

  /**
   * THE UNMIGRATED ESTATE, which is most of it: an application with no declaration at all, asked for
   * with the version it is deploying.
   *
   * <p>This was a 404 for one day and it took the platform down with it — the deployer passes {@code
   * ?version=} on every extras read, so every deployment of every application without a {@code
   * .config/qits/configuration.yml} was refused at argv build. Entries-only is the same map the
   * version-absent read returns, and it is the honest answer: this version declared nothing.
   */
  @Test
  void anUnmigratedApplicationAskedWithAVersionResolvesEntriesOnly() {
    configuration.upsert(ENV, "app-unmigrated", "env.QITS_B", "stored", "alice");

    ResolvedConfigurationDto resolved =
        configuration.resolve(ENV, "app-unmigrated", Optional.of("2026.1.1"));

    assertEquals(
        configuration.resolve(ENV, "app-unmigrated").properties(),
        resolved.properties(),
        "a version naming no declaration is exactly the version-absent answer, never a 404");
    assertEquals("stored", resolved.properties().get(property("app-unmigrated", "env.QITS_B")));
  }

  /**
   * THE ROLLBACK: an application that HAS declared, asked for a version that did not.
   *
   * <p>Redeploying a migrated application at a pre-declaration tag is a real operation, and it must
   * not be the one deployment that cannot be made. The declaration at another version contributes
   * nothing here — the answer is the version's own, and this version declared nothing.
   */
  @Test
  void aDeclaringApplicationAskedForAnUndeclaredVersionResolvesEntriesOnly() {
    declare(
        "app-rolledback",
        ConfigurationKeys.TARGET_ENVIRONMENT,
        "keys:\n  env.QITS_A:\n    type: string\n    default: from-the-declaration\n");
    configuration.upsert(ENV, "app-rolledback", "env.QITS_B", "stored", "alice");

    Map<String, String> properties =
        configuration.resolve(ENV, "app-rolledback", Optional.of("2026.1.1")).properties();

    assertEquals(1, properties.size(), "no defaults from the version that DID declare");
    assertEquals("stored", properties.get(property("app-rolledback", "env.QITS_B")));
    assertEquals(
        "from-the-declaration",
        configuration
            .resolve(ENV, "app-rolledback", Optional.of(VERSION))
            .properties()
            .get(property("app-rolledback", "env.QITS_A")),
        "and the version that declared still overlays, which is what makes this per-version");
  }

  /** A MALFORMED version is still a refusal — only the well-formed-but-undeclared case changed. */
  @Test
  void aMalformedVersionIsStillRefused() {
    assertThrows(
        BadRequestException.class,
        () -> configuration.resolve(ENV, "app-badversion", Optional.of("not a version")));
  }

  /**
   * THE PLANE DECIDES THE HOSTNAME, and this is the test that pins which one.
   *
   * <p>A platform-plane service answers at its bare application name from every environment at once;
   * an environment-plane one answers at {@code <env>-<application>}. That is the deployer's
   * {@code PdNetworks.alias} and the reason the plane is a recorded fact rather than something
   * inferred from the name — nothing about the string {@code qits-events} says which side it is on.
   */
  @Test
  void aServiceAddressRendersAgainstTheADDRESSEDApplicationsOwnPlane() {
    declare("app-plane-platform", ConfigurationKeys.TARGET_PLATFORM, "keys: {}\n");
    declare("app-plane-env", ConfigurationKeys.TARGET_ENVIRONMENT, "keys: {}\n");
    String version =
        declare(
            "app-addresser",
            ConfigurationKeys.TARGET_ENVIRONMENT,
            """
            keys:
              env.QITS_PLATFORM_URL:
                type: serviceAddress
                service: app-plane-platform
                port: 8080
              env.QITS_TIER_URL:
                type: serviceAddress
                service: app-plane-env
                port: 9090
            """);

    Map<String, String> properties =
        configuration.resolve(ENV, "app-addresser", Optional.of(version)).properties();

    assertEquals(
        "http://app-plane-platform:8080",
        properties.get(property("app-addresser", "env.QITS_PLATFORM_URL")),
        "a platform-plane peer is reached at its bare alias from every environment");
    assertEquals(
        "http://" + ENV + "-app-plane-env:9090",
        properties.get(property("app-addresser", "env.QITS_TIER_URL")),
        "an environment-plane peer is reached at <env>-<application>");
    assertEquals(
        "http://other-app-plane-env:9090",
        configuration
            .resolve(OTHER_ENV, "app-addresser", Optional.of(version))
            .properties()
            .get(property("app-addresser", "env.QITS_TIER_URL")),
        "and the same declaration renders a different address in a different environment, which is"
            + " the whole reason the host is not stored");
  }

  @Test
  void aStoredValueOnAServiceAddressKeyIsIgnoredAndReported() {
    declare("app-rendered-target", ConfigurationKeys.TARGET_PLATFORM, "keys: {}\n");
    String version =
        declare(
            "app-rendered",
            ConfigurationKeys.TARGET_ENVIRONMENT,
            """
            keys:
              env.QITS_URL:
                type: serviceAddress
                service: app-rendered-target
                port: 8080
            """);

    // The row exists only because the import path does not run the PUT guard — the file is the
    // deployer's old config volume and it predates every declaration.
    configuration.importProperties(
        ENV,
        "qits.platform.deployments.extras.app-rendered.env.QITS_URL=http://somewhere-else:1\n",
        "bootstrap");

    assertEquals(
        "http://app-rendered-target:8080",
        configuration
            .resolve(ENV, "app-rendered", Optional.of(version))
            .properties()
            .get(property("app-rendered", "env.QITS_URL")),
        "the rendered address wins: a hand-pinned one is how a container survives a plane move by"
            + " pointing at where the service used to be");

    ConfigurationEntryDto view = configuration.entryViews(ENV, "app-rendered").get(0);
    assertTrue(
        view.orphaned(),
        "and the row says so, rather than sitting in the store looking like it is in effect");
  }

  @Test
  void anAddressIntoAnApplicationWithNoDeclaredPlaneIsRefusedRatherThanGuessed() {
    String version =
        declare(
            "app-void",
            ConfigurationKeys.TARGET_ENVIRONMENT,
            """
            keys:
              env.QITS_URL:
                type: serviceAddress
                service: app-never-declared
                port: 8080
            """);

    UnprocessableEntityException failure =
        assertThrows(
            UnprocessableEntityException.class,
            () -> configuration.resolve(ENV, "app-void", Optional.of(version)));
    assertEquals(422, failure.statusCode());
    assertTrue(
        failure.getMessage().contains("app-never-declared"),
        "the refusal names the application that has not declared: " + failure.getMessage());
    assertTrue(
        failure.getMessage().contains("deployment plane"),
        "and what is missing about it: " + failure.getMessage());
  }

  @Test
  void aPackageVersionIsOmittedUntilSomethingHasSetIt() {
    String version =
        declare(
            "app-package",
            ConfigurationKeys.TARGET_ENVIRONMENT,
            """
            keys:
              env.QITS_IMAGE_VERSION:
                type: packageVersion
                package:
                  type: docker
                  name: qits/workspace
            """);
    String property = property("app-package", "env.QITS_IMAGE_VERSION");

    assertFalse(
        configuration
            .resolve(ENV, "app-package", Optional.of(version))
            .properties()
            .containsKey(property),
        "a version nobody released is an omitted key, not an empty one");

    configuration.upsert(ENV, "app-package", "env.QITS_IMAGE_VERSION", "2026.907.1", "release");

    assertEquals(
        "2026.907.1",
        configuration
            .resolve(ENV, "app-package", Optional.of(version))
            .properties()
            .get(property),
        "a packageVersion key stays editable — pinning a version by hand is a real operation");
  }

  @Test
  void aServiceAddressKeyCannotBeSetByHand() {
    declare("app-guarded-target", ConfigurationKeys.TARGET_PLATFORM, "keys: {}\n");
    declare(
        "app-guarded",
        ConfigurationKeys.TARGET_ENVIRONMENT,
        """
        keys:
          env.QITS_URL:
            type: serviceAddress
            service: app-guarded-target
            port: 8080
          env.QITS_OTHER:
            type: string
        """);

    BadRequestException failure =
        assertThrows(
            BadRequestException.class,
            () -> configuration.upsert(ENV, "app-guarded", "env.QITS_URL", "http://mine:1", "alice"));
    assertTrue(
        failure.getMessage().contains("cannot be set by hand"),
        "and it says why rather than only that: " + failure.getMessage());
    assertTrue(configuration.entriesOf(ENV, "app-guarded").isEmpty());

    configuration.upsert(ENV, "app-guarded", "env.QITS_OTHER", "fine", "alice");
    assertEquals(1, configuration.entriesOf(ENV, "app-guarded").size(), "only that one key is shut");
  }

  @Test
  void anImportDoesNotOverwriteAnOperatorsValueAndSaysHowManyItKept() {
    configuration.upsert(ENV, "app-precedence", "env.QITS_A", "the-operators", "alice");

    ImportSummaryDto summary =
        configuration.importProperties(
            ENV,
            """
            qits.platform.deployments.extras.app-precedence.env.QITS_A=the-files
            qits.platform.deployments.extras.app-precedence.env.QITS_B=the-files
            """,
            "bootstrap");

    assertEquals(1, summary.imported());
    assertEquals(1, summary.kept(), "the operator's row is kept and counted, never overwritten");
    assertEquals(0, summary.unchanged());
    assertEquals(
        "the-operators",
        configuration.require(ENV, "app-precedence", "env.QITS_A").entryValue,
        "the bootstrap runs on every boot; a fix made by hand must survive the next one");
    assertEquals(
        ConfigurationEntry.CLASS_IMPORTED,
        configuration.require(ENV, "app-precedence", "env.QITS_B").entryClass,
        "what the import DID write carries its own class");
  }

  @Test
  void anOperatorEditingAnImportedRowMakesItTheirs() {
    String file = "qits.platform.deployments.extras.app-adopt.env.QITS_A=the-files\n";
    assertEquals(1, configuration.importProperties(ENV, file, "bootstrap").imported());

    assertEquals(
        ConfigurationEntry.CLASS_PLAIN,
        configuration.upsert(ENV, "app-adopt", "env.QITS_A", "the-operators", "alice").entryClass,
        "the class moves with the write, which is what stops the next import taking the row back");

    ImportSummaryDto again = configuration.importProperties(ENV, file, "bootstrap");
    assertEquals(
        1,
        again.kept(),
        "and this is the assertion that it really landed in the STORE rather than on one instance:"
            + " the import reads in a transaction of its own, so it is looking at the row");
    assertEquals(0, again.imported());
  }

  @Test
  void anEntryTheGoverningDeclarationDoesNotAccountForIsFlaggedAndNothingElseIs() {
    configuration.upsert(ENV, "app-orphan", "env.QITS_DECLARED", "one", "alice");
    configuration.upsert(ENV, "app-orphan", "env.QITS_STRAY", "two", "alice");

    assertTrue(
        configuration.entryViews(ENV, "app-orphan").stream().noneMatch(ConfigurationEntryDto::orphaned),
        "an application with no declaration has nothing orphaned: unknown is not unaccounted for");

    declare(
        "app-orphan",
        ConfigurationKeys.TARGET_ENVIRONMENT,
        "keys:\n  env.QITS_DECLARED:\n    type: string\n");

    Map<String, Boolean> orphaned =
        configuration.entryViews(ENV, "app-orphan").stream()
            .collect(Collectors.toMap(ConfigurationEntryDto::key, ConfigurationEntryDto::orphaned));
    assertEquals(Boolean.FALSE, orphaned.get("env.QITS_DECLARED"));
    assertEquals(Boolean.TRUE, orphaned.get("env.QITS_STRAY"));
    assertEquals(
        2,
        configuration.entriesOf(ENV, "app-orphan").size(),
        "and flagging wrote nothing and removed nothing");
  }

  // ------------------------------------------------------------ the declaration store itself

  @Test
  void anIdenticalDocumentIsANoOpAndADifferentOneUnderTheSameVersionIsAConflict() {
    String document = "keys:\n  env.QITS_A:\n    type: string\n";

    assertTrue(
        declarations
            .declare("app-intake", "1.0", ConfigurationKeys.TARGET_ENVIRONMENT, document, "ci")
            .created());
    assertFalse(
        declarations
            .declare("app-intake", "1.0", ConfigurationKeys.TARGET_ENVIRONMENT, document, "ci")
            .created(),
        "a pipeline step that retries is not an event");
    assertEquals(
        1,
        declarations.declarationsOf("app-intake").size(),
        "and it appended nothing — one version, one document");

    ConflictException failure =
        assertThrows(
            ConflictException.class,
            () ->
                declarations.declare(
                    "app-intake",
                    "1.0",
                    ConfigurationKeys.TARGET_ENVIRONMENT,
                    document + "  env.QITS_B:\n    type: string\n",
                    "ci"));
    assertEquals(409, failure.statusCode());
    assertTrue(
        failure.getMessage().contains(DeclarationParser.parse("app-intake", "1.0", document).contentHash()),
        "the refusal names the stored hash: " + failure.getMessage());
  }

  @Test
  void removingTheNewestDeclarationLetsThePreviousOneGovernAgain() {
    declarations.declare(
        "app-rollback",
        "1.0",
        ConfigurationKeys.TARGET_ENVIRONMENT,
        "keys:\n  env.QITS_A:\n    type: string\n    default: one\n",
        "ci");
    declarations.declare(
        "app-rollback",
        "2.0",
        ConfigurationKeys.TARGET_ENVIRONMENT,
        "keys:\n  env.QITS_A:\n    type: string\n    default: two\n",
        "ci");
    assertTrue(
        declarations.declarationsOf("app-rollback").stream()
            .anyMatch(each -> each.version().equals("2.0") && each.governing()));

    declarations.remove("app-rollback", "2.0", "operator");

    List<DeclarationSummaryDto> after = declarations.declarationsOf("app-rollback");
    assertEquals(1, after.size(), "the document is gone");
    assertTrue(
        after.get(0).version().equals("1.0") && after.get(0).governing(),
        "and the version before it governs again, with nobody re-posting an unchanged document");
    assertThrows(
        NotFoundException.class, () -> declarations.remove("app-rollback", "2.0", "operator"));
  }

  @Test
  void aDeclarationKeepsBothTheParsedKeysAndTheDocument() {
    String document =
        """
        # the comment is part of what was committed
        keys:
          env.QITS_A:
            type: number
            default: 3
            description: how many
        """;
    declarations.declare(
        "app-both", "1.0", ConfigurationKeys.TARGET_PLATFORM, document, "ci");

    DeclarationDto stored = declarations.declaration("app-both", "1.0");
    assertEquals(document, stored.raw());
    assertEquals(ConfigurationKeys.TARGET_PLATFORM, stored.deploymentTarget());
    assertEquals(1, stored.keys().size());
    assertEquals("env.QITS_A", stored.keys().get(0).key());
    assertEquals(DeclarationParser.TYPE_NUMBER, stored.keys().get(0).type());
    assertEquals("3", stored.keys().get(0).defaultValue());
    assertTrue(stored.governing());
  }

  @Test
  void aDocumentThatWillNotParseIsRefusedAndStoresNothing() {
    assertThrows(
        DeclarationParseException.class,
        () ->
            declarations.declare(
                "app-refused",
                "1.0",
                ConfigurationKeys.TARGET_ENVIRONMENT,
                "keys:\n  env.QITS_A:\n    type: integer\n",
                "ci"));
    assertTrue(declarations.declarationsOf("app-refused").isEmpty());
  }

  @Test
  void theDeploymentTargetVocabularyIsClosedAndRequired() {
    for (String refused : new String[] {null, "", "singleton", "PLATFORM"}) {
      assertThrows(
          BadRequestException.class,
          () -> declarations.declare("app-target", "1.0", refused, "keys: {}\n", "ci"),
          "deploymentTarget " + refused);
    }
    assertTrue(declarations.declarationsOf("app-target").isEmpty());
  }
}
