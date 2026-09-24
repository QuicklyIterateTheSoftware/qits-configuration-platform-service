package eu.wohlben.qits.configuration.control;

import eu.wohlben.qits.configuration.dto.ApplicationEnvSummaryDto;
import eu.wohlben.qits.configuration.dto.ApplicationSummaryDto;
import eu.wohlben.qits.configuration.dto.ConfigurationEntryDto;
import eu.wohlben.qits.configuration.dto.ImagePinDto;
import eu.wohlben.qits.configuration.dto.ImportSummaryDto;
import eu.wohlben.qits.configuration.dto.ResolvedConfigurationDto;
import eu.wohlben.qits.configuration.entity.ConfigurationDeclaration;
import eu.wohlben.qits.configuration.entity.ConfigurationDeclaredKey;
import eu.wohlben.qits.configuration.entity.ConfigurationEntry;
import eu.wohlben.qits.configuration.entity.ConfigurationRevision;
import eu.wohlben.qits.configuration.error.BadRequestException;
import eu.wohlben.qits.configuration.error.NotFoundException;
import eu.wohlben.qits.configuration.mapper.ConfigurationMapper;
import eu.wohlben.qits.configuration.persistence.ConfigurationDeclarationRepository;
import eu.wohlben.qits.configuration.persistence.ConfigurationEntryRepository;
import eu.wohlben.qits.configuration.persistence.ConfigurationRevisionRepository;
import eu.wohlben.qits.configuration.persistence.DeclaredKeyRepository;
import eu.wohlben.qits.db.DbRetry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The whole of what this service does: store a configuration entry, version it, and serve it.
 *
 * <p><b>THE DOCTRINE AMENDMENT, and it is one line wide.</b> "Store, do not parse" holds for entry
 * VALUES and holds completely — nothing in this class reads one, and what a mount or a published
 * port means is still qits-platform-deployments' {@code ServiceExtras}. What has changed is that
 * there is a SECOND document class in this context: the DECLARATION an application makes about its
 * own keys, parsed by the one strict parser ({@link DeclarationParser}). {@link #resolve} therefore
 * answers with more than it was told — declared defaults, and serviceAddress keys rendered per
 * environment — because those are answers only the service holding both halves can give. The entry
 * values it layers on top are still bytes it never looked at.
 *
 * <p><b>An entry is addressed by (env, application, key), and every method here takes the env
 * first.</b> One instance of this service holds every environment's configuration in one store, so
 * there is no "the" configuration of an application — there is dev's and there is
 * prod's, and a method that let a caller omit which one it meant would be the one place the two
 * could be confused. There is no overload here that omits it, and the env-less API routes that once
 * supplied one configured env on a caller's behalf are gone.
 *
 * <p><b>Every write goes through {@link #store} and there is no second door.</b> Appending the
 * revision and moving the head is one decision, and splitting it across two callers is how a head
 * ends up naming a revision that says something else. The import path calls the same method in a
 * loop rather than a bulk variant of its own.
 *
 * <p><b>An identical value writes nothing.</b> {@link #store} compares before it appends, so a
 * re-import of an unchanged file leaves the log exactly as it found it. That is what makes the
 * import safe to run from a bootstrap on every boot, and it keeps the history a record of changes
 * rather than of runs.
 *
 * <p><b>The write brackets are {@link DbRetry#inNewTx} and each body ends with a flush.</b> This
 * service is deployed beside the postgres it stores in and is redeployed by the component that
 * redeploys that postgres, so a connection dying mid-write is an ordinary event rather than an
 * exotic one. {@code inNewTx} owns the transaction boundary, which is the only way a retry can tell
 * "the body threw, so it certainly never committed" from "the transaction manager reported it"; the
 * flush is what keeps a lost connection on the body's side of that line, since an ORM would
 * otherwise put every statement on the far side of the undecidable round trip.
 *
 * <p>Reads are deliberately NOT wrapped. A read that fails is a 500 the caller retries; the deployer
 * pulling a resolved read has its own timeout and its own posture about an unreachable
 * configuration service, and a patience here would only make its deadline arrive with less
 * information.
 */
@ApplicationScoped
public class ConfigurationService {

  @Inject ConfigurationEntryRepository entries;

  @Inject ConfigurationRevisionRepository revisions;

  @Inject ConfigurationDeclarationRepository declarations;

  @Inject DeclaredKeyRepository declaredKeys;

  @Inject ConfigurationMapper mapper;

  /**
   * One application's governing declaration with its keys indexed — the shape every read that has to
   * compare stored rows against declared ones wants.
   *
   * <p>Held for the length of one call and never cached. A declaration arriving, or being rolled
   * back, changes the answer for rows nobody touched, and a cache would be the place that stays
   * right until it matters.
   */
  private record Governing(
      ConfigurationDeclaration declaration, Map<String, ConfigurationDeclaredKey> keys) {}

  // ---------------------------------------------------------------- reads

  /**
   * Every application this service knows about, by name, with one row per environment it is
   * configured in.
   *
   * <p>It is the UNION of the two tables rather than a listing of the head rows, so an application
   * whose entries have all been deleted still appears — with no entries and the revision seq that
   * deleted the last one. Dropping it would make the one case a person most wants to look at (where
   * did my configuration go) the one case the listing hides. <b>The doctrine holds per env now</b>,
   * which is the sharper form of it: an application emptied out in dev and untouched in prod shows
   * both facts, where a listing keyed by application alone would show neither clearly.
   */
  public List<ApplicationSummaryDto> applications() {
    // application -> env -> current entry count. Sorted maps, because this listing is read side by
    // side with the last one and an unordered answer makes that diff noise.
    Map<String, Map<String, Integer>> counts = new TreeMap<>();
    for (ConfigurationEntry entry : entries.listEverything()) {
      counts.computeIfAbsent(entry.application, name -> new TreeMap<>()).merge(entry.env, 1, Integer::sum);
    }
    // Every (application, env) the LOG has ever held, folded in at zero. This is what keeps an
    // emptied-out application in the answer.
    for (Object[] pair :
        revisions
            .getEntityManager()
            .createQuery(
                "select distinct r.application, r.env from ConfigurationRevision r", Object[].class)
            .getResultList()) {
      counts
          .computeIfAbsent((String) pair[0], name -> new TreeMap<>())
          .putIfAbsent((String) pair[1], 0);
    }
    List<ApplicationSummaryDto> summaries = new ArrayList<>(counts.size());
    counts.forEach(
        (application, perEnv) -> {
          List<ApplicationEnvSummaryDto> envs = new ArrayList<>(perEnv.size());
          perEnv.forEach(
              (env, entryCount) ->
                  envs.add(
                      new ApplicationEnvSummaryDto(
                          env, entryCount, revisions.headRevisionOf(env, application))));
          summaries.add(new ApplicationSummaryDto(application, List.copyOf(envs)));
        });
    return summaries;
  }

  /** One application's current entries in one env, by key. */
  public List<ConfigurationEntry> entriesOf(String env, String application) {
    return entries.listByApplication(
        ConfigurationKeys.requireEnv(env), ConfigurationKeys.requireApplication(application));
  }

  /**
   * One application's configuration in one env, as the property map a consumer layers verbatim.
   *
   * <p>An application with nothing stored is an empty map at revision 0, not a 404: "this
   * application has no extras" is a complete and useful answer, and a deployer that treated a 404 as
   * an error would refuse every deployment of an application nobody has configured. The same holds
   * one level up: an env nobody has written into resolves empty rather than announcing itself as
   * unknown, because an environment joining the platform has no rows yet and must still deploy.
   *
   * <p><b>The property names carry no env</b>, and must not. They are the deployer's own namespace,
   * {@code qits.platform.deployments.extras.<app>.<key>}, layered into one container's configuration
   * — and that container is in exactly one environment, the one named in the path of this read. An
   * env segment in the property name would have to be stripped by every consumer, which is a second
   * place for this service's addressing to be written down.
   */
  public ResolvedConfigurationDto resolve(String env, String application) {
    return resolve(env, application, Optional.empty());
  }

  /**
   * THE OVERLAY READ: the same map, with the application's declaration of version {@code version}
   * merged underneath the stored entries.
   *
   * <p><b>The precedence, lowest first: declared default, then an imported value, then an operator's
   * value.</b> Only the first of those three is decided here — the other two are one stored row,
   * ordered against each other at the WRITE (see {@link #importProperties}), so this method layers
   * exactly two things and does not have to reason about who wrote what.
   *
   * <p><b>An absent version is EXACTLY today's behaviour and never a 404.</b> That is not
   * politeness, it is the rollout: the deployer reads this route once per deployment and does not
   * pass a version yet, and a service that started requiring one would take the platform down for a
   * feature nobody was using. When it does pass one, the answer gets richer without the shape
   * changing.
   *
   * <p><b>A version that names no declaration resolves entries-only</b> — exactly the version-absent
   * answer, never a 404. This was a 404 once, on the reasoning that a resolution against a
   * declaration nobody seeded is a configuration missing every default the caller asked for with
   * nothing to say so. That danger is real and it is guarded somewhere else: the deployer refuses a
   * deployment whose declaration failed to seed (DECLARATION_REFUSED) at seed time, before this read
   * ever happens. What the 404 actually hit was the UNMIGRATED ESTATE — an application with no
   * {@code .config/qits/configuration.yml} at all, and a migrated application rolled back to a
   * pre-declaration tag — where the deployer passes the deployed version on every extras read and got
   * a 404 for it, refusing the deployment of every application that had not migrated yet. For both of
   * those, "this version declared nothing" is a complete and correct answer, and entries-only states
   * it: absent-means-not-yet-migrated, the doctrine this epic writes on the write side, applied on
   * the read side. Measured on qits-ci@2026.907.184918, 2026-09-07.
   *
   * <p>The version is still validated as a version: a MALFORMED one is refused as before. Only the
   * well-formed-but-undeclared case changed.
   *
   * <p><b>What each declared type contributes:</b>
   *
   * <ul>
   *   <li>{@code string}, {@code boolean}, {@code number} — the default, if it carries one, which a
   *       stored entry then overwrites. A key declared with no default and never set is absent from
   *       the answer, which is the honest shape: the container gets no variable rather than an empty
   *       one.
   *   <li>{@code serviceAddress} — rendered here and NOT overridable. A stored row on such a key is
   *       ignored for the value and reported orphaned by the entries read; the address is a fact
   *       about the platform's own topology, and letting an operator pin it by hand is how a
   *       container survives an address change by pointing at where the service used to be.
   *   <li>{@code packageVersion} — nothing unless an entry exists. There is no default by design:
   *       the version is whatever a release put there, and a fallback is a container started on a tag
   *       nobody shipped.
   * </ul>
   */
  public ResolvedConfigurationDto resolve(
      String env, String application, Optional<String> version) {
    String environment = ConfigurationKeys.requireEnv(env);
    String app = ConfigurationKeys.requireApplication(application);
    Map<String, String> properties = new LinkedHashMap<>();
    Set<String> renderedByThePlatform = new LinkedHashSet<>();

    if (version.isPresent()) {
      // Grammar first: a malformed version is still a refusal. An undeclared one is not — it
      // contributes no layer, and the entries below are the whole answer.
      String tag = ConfigurationKeys.requireDeclarationVersion(version.get());
      List<ConfigurationDeclaredKey> declaredAtVersion =
          declarations.find(app, tag).isPresent()
              ? declaredKeys.listOf(app, tag)
              : List.<ConfigurationDeclaredKey>of();
      for (ConfigurationDeclaredKey declared : declaredAtVersion) {
        String property = ExtrasProperties.propertyName(app, declared.declaredKey);
        switch (declared.declaredType) {
          case DeclarationParser.TYPE_SERVICE_ADDRESS -> {
            properties.put(property, renderAddress(environment, app, declared));
            renderedByThePlatform.add(declared.declaredKey);
          }
          case DeclarationParser.TYPE_PACKAGE_VERSION -> {
            // Nothing. An unset package version is an omitted key, not an empty one.
          }
          default -> {
            if (declared.defaultValue != null) {
              properties.put(property, declared.defaultValue);
            }
          }
        }
      }
    }

    for (ConfigurationEntry entry : entries.listByApplication(environment, app)) {
      if (renderedByThePlatform.contains(entry.entryKey)) {
        continue;
      }
      properties.put(ExtrasProperties.propertyName(app, entry.entryKey), entry.entryValue);
    }
    return new ResolvedConfigurationDto(revisions.headRevisionOf(environment, app), properties);
  }

  /**
   * One serviceAddress key, turned into the URL a container in {@code env} can actually dial.
   *
   * <p><b>The host is the deployer's WIRE ALIAS, and there is only one shape of it: {@code
   * <env>-<application>}.</b> That is {@code PdNetworks.alias} restated on this side, and it is now
   * a derivation rather than a lookup.
   *
   * <p><b>This used to ask the addressed application which PLANE it was on.</b> A platform-plane
   * application answered at its bare name from every environment at once, an environment-plane one
   * at {@code <env>-<application>} once per tier, and nothing about the string {@code qits-events}
   * said which — so the plane had to be a recorded fact, a target that had never declared one was a
   * 422 naming it, and this method could not answer without reading the target's governing
   * declaration. The plane is deleted: every application is an environment application in the one
   * tier, so the question has a single answer and the lookup, the refusal and the branch go with it.
   *
   * <p>{@code deploymentTarget} survives on {@link ConfigurationDeclaration} as a recorded fact that
   * nothing reads for addressing — the same RETIRED tolerance the deployer's spec parser keeps for
   * the key, and for the same reason: a declaration is posted at a BUILT version, so older senders
   * go on stating it forever.
   */
  private String renderAddress(
      String env, String application, ConfigurationDeclaredKey declared) {
    return "http://" + env + "-" + declared.serviceRef + ":" + declared.servicePort;
  }

  /**
   * One application's current entries in one env, as wire shapes, with {@code orphaned} decided
   * against the governing declaration.
   *
   * <p>Read-only in every sense: an orphan is reported and never cleaned up. A key the current
   * declaration does not account for is usually a key the NEXT deployment removes and sometimes a
   * key somebody set early for a version not released yet, and a store that deleted the second kind
   * to tidy up the first would be a store nobody could stage a change in.
   */
  public List<ConfigurationEntryDto> entryViews(String env, String application) {
    String environment = ConfigurationKeys.requireEnv(env);
    String app = ConfigurationKeys.requireApplication(application);
    Optional<Governing> governing = governing(app);
    return entries.listByApplication(environment, app).stream()
        .map(entry -> mapper.toDto(entry, isOrphaned(governing, entry.entryKey)))
        .toList();
  }

  /** One entry as a wire shape, with the same orphan verdict the listing gives it. */
  public ConfigurationEntryDto view(ConfigurationEntry entry) {
    return mapper.toDto(entry, isOrphaned(governing(entry.application), entry.entryKey));
  }

  /**
   * EVERY (application, key) A RELEASE OF ONE PACKAGE MOVES, as the applications themselves declare
   * it — the half of the match that is not written down in {@link ImagePins}.
   *
   * <p>Two narrowings and both are load-bearing. Only {@code packageVersion} keys are returned,
   * because they are the only ones that carry a version at all. And only the keys of the GOVERNING
   * declaration count: a superseded version's rows are still in the table — they are what a
   * deployment that resolved against it is answerable by — and acting on them would have a release
   * write a key an application stopped declaring two versions ago.
   *
   * <p><b>No docker gate here, deliberately.</b> The declaration names its own package type, so a
   * {@code binary} coordinate is matched exactly as a docker one is and by the same string
   * comparison. The gate belongs to {@link ImagePins#BY_IMAGE} alone, whose rows are images by
   * construction and have no type to name.
   *
   * <p>One indexed query plus one governing read per application that declared the coordinate —
   * which is one application in every case the platform has today, and grows with the consumers of a
   * package rather than with the log.
   *
   * <p><b>IT OPENS A TRANSACTION OF ITS OWN, unlike every other read here, and the reason is its one
   * caller.</b> {@code bus/SoftwareReleaseListener} is asked this from inside the durable funnel's
   * claiming transaction, which has already enlisted the EVENTSTREAM datasource to write the claim —
   * and two non-XA datasources cannot both join one transaction. Reading this store from in there
   * dies with {@code Unable to acquire JDBC Connection [Exception in association of connection to
   * existing transaction]}, which is measured rather than reasoned about: it is what {@code
   * ImageReleasePinIT} answered on 2026-09-07, on every frame, with the claim rolled back and the
   * release owed forever. So the read suspends the claim exactly as the pin's write always has.
   */
  public List<ImagePins.Pin> declaredPins(String packageType, String packageName) {
    return DbRetry.inNewTx(
        "read who declares " + packageType + " " + packageName,
        () -> governingPins(declaredKeys.listByPackage(packageType, packageName)));
  }

  /**
   * Every env a value the platform writes ITSELF has to reach: every env this store knows anything
   * about.
   *
   * <p><b>An entry is a per-env override and there is no default row to write</b>, so a pin that is
   * not written into an env is not "inherited" there — it is absent, and the container starts on
   * whatever its image's committed default says. That is why a release fans out over every env
   * rather than landing in one: the alternative is a platform where dev runs the version CI just
   * built and prod runs whatever was current the day it was configured, with nothing saying so.
   *
   * <p><b>It is exactly what the store knows, with no floor under it.</b> There used to be one — the
   * configured legacy env, added unconditionally — because the fan-out replaced a writer that only
   * ever wrote there and the floor is what made that change additive. It left with the property that
   * named it, as its own javadoc said it would. What the floor covered was a store that knows of no
   * env at all, where a release now writes nothing; that store is one nobody has imported into yet,
   * and a bootstrap imports before it releases.
   *
   * <p><b>The accepted residual: an env born AFTER a release has no row until the next one.</b>
   * Nothing backfills, because a backfill would be this service deciding that a tier joining the
   * platform should start whatever was last released — a decision, and one the image's own default
   * already answers more conservatively. The next release of that package closes the gap.
   *
   * <p>In a transaction of its own for the reason {@link #declaredPins} states — its caller asks it
   * from inside the durable funnel's claim, which belongs to another datasource.
   */
  public List<String> pinEnvs() {
    return DbRetry.inNewTx(
        "read the envs a release fans out over",
        () -> {
          Set<String> envs = new TreeSet<>(entries.listDistinctEnvs());
          return List.copyOf(envs);
        });
  }

  /**
   * THE PIN REPORT: every pinned (application, key) that currently has a stored version, in the
   * answer's fixed order.
   *
   * <p><b>It is a projection over two sources now</b>, put together by {@link ImagePins#merge}: the
   * {@code docker} {@code packageVersion} keys of every governing declaration, and the {@link
   * ImagePins#ORDERED} rows no declaration has claimed. One merge function, shared with {@code
   * bus/SoftwareReleaseListener}, because a report that disagreed with the writer about which of the
   * two won would be fiction in exactly the way this whole arrangement exists to prevent.
   *
   * <p><b>Declared coordinates of another type are not here.</b> The consumer is qits-artifacts'
   * IMAGE collector and the wire field is called {@code image}; a {@code binary} coordinate in this
   * answer would be a row it cannot act on, under a name it would try to parse as a tag.
   *
   * <p><b>It reads EVERY env, and {@code /pins} is env-less because its caller is.</b> qits-artifacts
   * asks "which image tags may I delete" about a registry the whole platform shares, so the answer
   * has no tier in it — but the question is answered by the UNION rather than by one env, which is
   * the only reading that is safe in the direction this answer is used. It used to read one
   * configured env, and what that cost was a version released into another tier and never into this
   * one: a tag still in use by a running container, missing from the keep-list. Two tiers on
   * different versions of one image now contribute a row each, and the collector keeps both, which
   * is what it should do with an image two containers are running.
   *
   * <p><b>An entry with nothing stored is omitted rather than answered blank.</b> No entry means the
   * image has never been released into this environment, so there is no version, and a row carrying
   * an empty one would name a tag that cannot exist. Every mapping missing is an empty list, which
   * is a complete answer and not an error — a platform that has released nothing pins nothing.
   *
   * <p>It reads the head rows one mapping at a time, which is a point-read on a unique key per
   * pinned pair. A listing filtered in memory would be shorter to write and would quietly grow with
   * the table instead of with the pins.
   *
   * <p>Not wrapped in a retry, like every read this service SERVES: the caller — qits-artifacts'
   * collector, deciding what it may delete — has its own posture about an unreachable configuration
   * service, and it is a fail-closed one. Patience here would only make its deadline arrive with
   * less information. The two bracketed reads above are not exceptions to that rule; they are the
   * bus consumer's, and their bracket is about a transaction rather than about patience.
   */
  public List<ImagePinDto> imagePins() {
    List<ImagePins.Pin> merged =
        ImagePins.merge(
            governingPins(declaredKeys.listByPackageType(ImagePins.DOCKER_TYPE)),
            ImagePins.ORDERED);
    List<ImagePinDto> pins = new ArrayList<>(merged.size());
    for (ImagePins.Pin pin : merged) {
      // One row per DISTINCT version, not per env: the wire shape has no env field and two tiers
      // holding the same version are one fact about the registry, not two.
      Set<String> versions = new LinkedHashSet<>();
      for (ConfigurationEntry entry : entries.listByKey(pin.application(), pin.key())) {
        if (entry.entryValue != null && !entry.entryValue.isBlank()) {
          versions.add(entry.entryValue);
        }
      }
      for (String version : versions) {
        pins.add(new ImagePinDto(pin.image(), version, pin.application(), pin.key()));
      }
    }
    return pins;
  }

  /** One application's history in one env, newest first. */
  public List<ConfigurationRevision> history(String env, String application) {
    return revisions.listByApplication(
        ConfigurationKeys.requireEnv(env), ConfigurationKeys.requireApplication(application));
  }

  /** One current entry, or a 404 naming it. */
  public ConfigurationEntry require(String env, String application, String key) {
    String environment = ConfigurationKeys.requireEnv(env);
    String app = ConfigurationKeys.requireApplication(application);
    String entryKey = ConfigurationKeys.requireKey(key);
    return entries
        .findEntry(environment, app, entryKey)
        .orElseThrow(
            () ->
                new NotFoundException(
                    "No entry " + entryKey + " for application " + app + " in env " + environment));
  }

  // ---------------------------------------------------------------- writes

  /**
   * Set one entry's value in one env, as an OPERATOR. New keys are created, existing ones moved; an
   * identical value writes no revision and returns the entry it found.
   *
   * <p>It writes {@link ConfigurationEntry#CLASS_PLAIN}, which is the top of the precedence — so a
   * value set here survives every later run of the bootstrap's import. That is the whole point of
   * the class column: an operator fixing a live environment must not be undone by the next boot.
   *
   * <p><b>One key it refuses: a {@code serviceAddress} in the governing declaration.</b> Those are
   * rendered by the platform at every resolved read and a stored row on one is ignored, so accepting
   * the write would be answering 200 to an edit that changes nothing a container will ever see. The
   * 400 says so. {@code packageVersion} keys stay editable — a version IS a stored value, and pinning
   * one by hand is a real operation.
   *
   * <p><b>THE REFUSAL IS READ INSIDE THE BRACKET, NOT AHEAD OF IT</b>, and that is a correctness
   * rule rather than tidiness. This method has a caller that is already in somebody else's
   * transaction — {@code bus/SoftwareReleaseListener}, inside the durable funnel's claim, which
   * belongs to the eventstream datasource — and a read taken before {@code inNewTx} runs in THAT
   * transaction, where this store cannot enlist at all. Measured on 2026-09-07: with the guard
   * outside, every release died on {@code Exception in association of connection to existing
   * transaction} and stayed owed forever, while every test that called this from a request stayed
   * green. Everything this method asks the database belongs on the far side of the suspension. The
   * grammar checks above it are pure and stay where they are, so a malformed key is still a 400 that
   * opens no transaction. A refusal thrown from inside is not retried: {@code DbRetry} retries
   * connection failures and nothing else.
   */
  public ConfigurationEntry upsert(
      String env, String application, String key, String value, String actor) {
    String environment = ConfigurationKeys.requireEnv(env);
    String app = ConfigurationKeys.requireApplication(application);
    String entryKey = ConfigurationKeys.requireKey(key);
    String entryValue = ConfigurationKeys.requireValue(value);
    return DbRetry.inNewTx(
        "set " + environment + "/" + ExtrasProperties.propertyName(app, entryKey),
        () -> {
          refuseIfRenderedByThePlatform(app, entryKey);
          ConfigurationEntry stored =
              store(
                  environment,
                  app,
                  entryKey,
                  entryValue,
                  ConfigurationEntry.CLASS_PLAIN,
                  actor);
          entries.flush();
          return stored;
        });
  }

  /**
   * Remove one entry. It appends a deleted revision and takes the head row away — the history keeps
   * the value that was removed, which is what makes an accidental delete answerable.
   */
  public void delete(String env, String application, String key, String actor) {
    String environment = ConfigurationKeys.requireEnv(env);
    String app = ConfigurationKeys.requireApplication(application);
    String entryKey = ConfigurationKeys.requireKey(key);
    DbRetry.runInNewTx(
        "remove " + environment + "/" + ExtrasProperties.propertyName(app, entryKey),
        () -> {
          ConfigurationEntry existing =
              entries
                  .findEntry(environment, app, entryKey)
                  .orElseThrow(
                      () ->
                          new NotFoundException(
                              "No entry "
                                  + entryKey
                                  + " for application "
                                  + app
                                  + " in env "
                                  + environment));
          append(environment, app, entryKey, null, true, actor);
          entries.delete(existing);
          entries.flush();
        });
  }

  /**
   * Bulk import of an extras properties file into ONE env, in the full prefixed spelling.
   *
   * <p><b>The env is the import's, not the file's.</b> The file is the deployer's own config volume,
   * which was always one environment's — it names applications and keys, and there is nowhere in its
   * grammar for a tier. So the caller asserts which env the file describes, once, for the whole
   * import; a file that could name several would be a file whose halves could disagree.
   *
   * <p>ONE TRANSACTION for the whole file, so a malformed line late in it leaves nothing behind —
   * an import that half-applied would be worse than one that failed, because the operator would
   * have to work out which half.
   *
   * <p>Idempotent by construction: it calls {@link #store}, which appends nothing when the value is
   * already what the line says.
   *
   * <p><b>IT WRITES THE {@code imported} CLASS AND WILL NOT OVERWRITE AN OPERATOR'S ROW.</b> The
   * precedence is declared default &lt; imported &lt; operator, and it is enforced HERE, at the
   * write, rather than at the read. Enforcing it at the read would mean keeping both values and
   * choosing between them on every deployment; enforcing it here means the store holds one value per
   * key and the answer to "what is set" is the row. The stake is concrete: this import runs from the
   * bootstrap on every boot, so without the rule an operator's fix to a live environment is silently
   * reverted the next time anything restarts — and reverted by a file, which is the hardest kind of
   * change to attribute afterwards.
   *
   * <p>Rows it declines are counted as {@code kept} rather than dropped quietly, because "the file
   * and the store disagree" is the one outcome of an import somebody should look at.
   */
  public ImportSummaryDto importProperties(String env, String text, String actor) {
    String environment = ConfigurationKeys.requireEnv(env);
    List<ExtrasProperties.Parsed> lines = ExtrasProperties.parse(text);
    int ignored = countLines(text) - lines.size();
    return DbRetry.inNewTx(
        "import " + lines.size() + " configuration entries into " + environment,
        () -> {
          int imported = 0;
          int unchanged = 0;
          int kept = 0;
          for (ExtrasProperties.Parsed line : lines) {
            String app = ConfigurationKeys.requireApplication(line.application());
            String key = ConfigurationKeys.requireKey(line.key());
            String value = ConfigurationKeys.requireValue(line.value());
            Optional<ConfigurationEntry> existing = entries.findEntry(environment, app, key);
            if (existing
                .map(entry -> ConfigurationEntry.CLASS_PLAIN.equals(entry.entryClass))
                .orElse(false)) {
              kept++;
            } else if (wouldChange(environment, app, key, value)) {
              store(environment, app, key, value, ConfigurationEntry.CLASS_IMPORTED, actor);
              imported++;
            } else {
              unchanged++;
            }
          }
          entries.flush();
          return new ImportSummaryDto(imported, unchanged, kept, ignored);
        });
  }

  // ---------------------------------------------------------------- the seam

  /**
   * THE ONE WRITE. It appends the revision and moves the head in the caller's transaction, and
   * nothing else in this class writes a row.
   *
   * <p>The revision is flushed before the head is written, because the head names the revision's
   * generated seq and an identity column has no value until the insert has run.
   *
   * <p><b>The CLASS comes from the caller.</b> This method is not in a position to know whether the
   * request behind it is a person fixing an environment or a script replaying a file, and the
   * precedence the import rests on would then rest on a guess. So every caller names its own class,
   * and the class MOVES on a write: an operator who edits an imported row makes it theirs, which is
   * what stops the next import from taking it back. An identical value changes nothing at all —
   * including the class — because it writes nothing at all.
   */
  private ConfigurationEntry store(
      String env, String application, String key, String value, String entryClass, String actor) {
    Optional<ConfigurationEntry> found = entries.findEntry(env, application, key);
    if (found.isPresent() && Objects.equals(found.get().entryValue, value)) {
      return found.get();
    }
    ConfigurationRevision revision = append(env, application, key, value, false, actor);
    ConfigurationEntry entry = found.orElseGet(ConfigurationEntry::new);
    if (found.isEmpty()) {
      entry.id = UUID.randomUUID();
      entry.env = env;
      entry.application = application;
      entry.entryKey = key;
    }
    entry.entryClass = entryClass;
    entry.entryValue = value;
    entry.headRevision = revision.seq;
    entry.updatedAt = revision.updatedAt;
    entry.updatedBy = actor;
    if (found.isEmpty()) {
      entries.persist(entry);
    }
    return entry;
  }

  private ConfigurationRevision append(
      String env, String application, String key, String value, boolean deleted, String actor) {
    ConfigurationRevision revision = new ConfigurationRevision();
    revision.env = env;
    revision.application = application;
    revision.entryKey = key;
    revision.entryValue = value;
    revision.deleted = deleted;
    revision.updatedBy = actor;
    // Truncated to the column's own precision, so a value read back equals the one written rather
    // than differing in digits the database never kept.
    revision.updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    revisions.persist(revision);
    revisions.flush();
    return revision;
  }

  /**
   * The {@code packageVersion} keys of that list which their own application still stands behind,
   * as pins.
   *
   * <p>The governing version is asked once per application rather than once per row: a declaration
   * with six package keys is one application making one statement, and six identical reads of the
   * intake log would be the same answer six times. Held for the length of this call only, for the
   * reason {@link Governing} gives — a declaration arriving changes the answer for rows nobody
   * touched.
   */
  private List<ImagePins.Pin> governingPins(List<ConfigurationDeclaredKey> declared) {
    Map<String, Optional<String>> governingVersions = new LinkedHashMap<>();
    List<ImagePins.Pin> pins = new ArrayList<>(declared.size());
    for (ConfigurationDeclaredKey key : declared) {
      if (!DeclarationParser.TYPE_PACKAGE_VERSION.equals(key.declaredType)
          || key.packageName == null) {
        continue;
      }
      Optional<String> governing =
          governingVersions.computeIfAbsent(
              key.application,
              application ->
                  declarations.governingOf(application).map(each -> each.version));
      if (governing.filter(version -> version.equals(key.version)).isPresent()) {
        pins.add(new ImagePins.Pin(key.packageName, key.application, key.declaredKey));
      }
    }
    return pins;
  }

  /** The governing declaration with its keys indexed, or empty when the application has none. */
  private Optional<Governing> governing(String application) {
    return declarations
        .governingOf(application)
        .map(
            declaration -> {
              Map<String, ConfigurationDeclaredKey> keys = new LinkedHashMap<>();
              for (ConfigurationDeclaredKey key :
                  declaredKeys.listOf(declaration.application, declaration.version)) {
                keys.put(key.declaredKey, key);
              }
              return new Governing(declaration, keys);
            });
  }

  /**
   * Whether a stored key is unaccounted for by the governing declaration.
   *
   * <p>Two ways to be: the declaration does not mention the key, or it declares it a {@code
   * serviceAddress}, whose stored row is ignored in favour of the rendered address. They are one
   * flag because they are one message to a person — this row is not reaching the container.
   *
   * <p><b>No declaration means nothing is orphaned.</b> Unknown is not the same as unaccounted for,
   * and flagging every row of every application that has not adopted declarations yet would make the
   * flag mean "this platform is mid-rollout" rather than anything about the row.
   */
  private static boolean isOrphaned(Optional<Governing> governing, String key) {
    return governing
        .map(
            found -> {
              ConfigurationDeclaredKey declared = found.keys().get(key);
              return declared == null
                  || DeclarationParser.TYPE_SERVICE_ADDRESS.equals(declared.declaredType);
            })
        .orElse(false);
  }

  /** The PUT guard: a key the platform renders is not a key a person may set. */
  private void refuseIfRenderedByThePlatform(String application, String key) {
    governing(application)
        .map(found -> found.keys().get(key))
        .filter(
            declared -> DeclarationParser.TYPE_SERVICE_ADDRESS.equals(declared.declaredType))
        .ifPresent(
            declared -> {
              throw new BadRequestException(
                  "The key "
                      + key
                      + " is declared a serviceAddress by application "
                      + application
                      + ": it is rendered by the platform; cannot be set by hand. It resolves to "
                      + declared.serviceRef
                      + " on port "
                      + declared.servicePort
                      + ", per environment.");
            });
  }

  private boolean wouldChange(String env, String application, String key, String value) {
    return entries
        .findEntry(env, application, key)
        .map(entry -> !Objects.equals(entry.entryValue, value))
        .orElse(true);
  }

  private static int countLines(String text) {
    return text == null || text.isBlank() ? 0 : (int) text.lines().count();
  }
}
