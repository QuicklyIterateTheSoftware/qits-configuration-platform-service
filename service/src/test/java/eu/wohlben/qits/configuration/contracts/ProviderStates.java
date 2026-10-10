package eu.wohlben.qits.configuration.contracts;

import eu.wohlben.qits.configuration.control.ConfigurationService;
import eu.wohlben.qits.configuration.control.DeclarationService;
import eu.wohlben.qits.configuration.error.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * <b>qits-configuration's provider states</b> (qits-1149): each seeds what one consumer situation
 * needs and hands back its parameters.
 *
 * <p>Two callers. {@link GoldenMasterRecordingTest} runs a state before recording each operation
 * that names it, and {@link ConsumerPactVerificationTest}'s {@code @State} methods delegate here.
 * Both call {@link #cleanUp()} afterwards: the store is shared by every test in the run.
 *
 * <p>Each state writes under an application name of its own, so no other suite's rows reach its
 * answers. The writes go through the same services the routes use, as the actor {@value #ACTOR}.
 */
@ApplicationScoped
public class ProviderStates {

  public static final String A_DECLARED_APPLICATION_WITH_ENTRIES =
      "a declared application with entries";
  public static final String AN_APPLICATION_WITH_NO_DECLARATION =
      "an application with no declaration";
  public static final String AN_APPLICATION_WITH_STORED_ENTRIES_AND_NO_DECLARATION =
      "an application with stored entries and no declaration";
  public static final String AN_APPLICATION_WITH_AN_ENTRY_IN_AN_ENVIRONMENT =
      "an application with an entry in an environment";
  public static final String AN_APPLICATION_WITH_NO_CONFIGURATION =
      "an application with no configuration";
  public static final String ENTRIES_OF_A_RETIRED_CONFIGURATION_KEY =
      "entries of a retired configuration key";

  /** Who the states write as. Recorded in {@code updatedBy}, so it must not vary. */
  static final String ACTOR = "golden-master";

  /** The env every state writes in. */
  static final String ENV = "test";

  /** The version every state declares, or that its consumer declares. */
  static final String VERSION = "2026.1010.1";

  static final String DECLARED_APP = "golden-declared-app";
  static final String UNDECLARED_APP = "golden-undeclared-app";
  static final String ENTRIES_ONLY_APP = "golden-entries-only-app";
  static final String IMPORTED_APP = "golden-imported-app";

  /**
   * The application qits-projects-service reads its agent MCP credentials from. Its client names
   * the application in code, so the state must use this exact name.
   */
  static final String AGENT_MCP_APP = "qits-agent-mcp";

  /** The credential key {@link #AN_APPLICATION_WITH_AN_ENTRY_IN_AN_ENVIRONMENT} stores. */
  static final String AGENT_MCP_KEY = "env.EXAMPLE_TOKEN";

  /** The key {@link #AN_APPLICATION_WITH_NO_CONFIGURATION}'s import writes. */
  static final String IMPORTED_KEY = "env.QITS_GREETING";

  /**
   * The properties file a consumer imports in {@link #AN_APPLICATION_WITH_NO_CONFIGURATION}: one
   * comment (ignored) and one extras line (imported).
   */
  static final String IMPORT =
      """
      # golden-master import
      qits.platform.deployments.extras.golden-imported-app.env.QITS_GREETING=hello from the file
      """;

  /**
   * The application and versions of {@link #ENTRIES_OF_A_RETIRED_CONFIGURATION_KEY}. They are the
   * ones qits-deployments-service's golden {@code listPins} answer names (state "an application
   * deployed in an environment"), so a consumer can embed that answer verbatim as the request's
   * {@code deployments}.
   */
  static final String RETIRING_APP = "contract-app-00000001";

  static final String RETIRED_VERSION = "2026.101.110000";
  static final String PINNED_VERSION = "2026.101.120000";
  static final String RETIRED_KEY = "env.QITS_RETIRED";

  /** The request {@link #ENTRIES_OF_A_RETIRED_CONFIGURATION_KEY}'s consumer sends. */
  static final String COLLECT_ENTRIES =
      """
      {"dryRun":false,"deployments":{"pins":[{"applicationName":"contract-app-00000001",\
      "shas":["2026.101.120000"]}]}}""";

  /**
   * The document {@link #A_DECLARED_APPLICATION_WITH_ENTRIES} declares — one key of each kind a
   * consumer meets in a resolved answer: a defaulted string, a platform-rendered service address,
   * and an image version.
   */
  static final String DECLARATION =
      """
      keys:
        env.QITS_GREETING:
          type: string
          default: hello
        env.QITS_PEER_URL:
          type: serviceAddress
          service: golden-peer
          port: 8080
        env.QITS_IMAGE_VERSION:
          type: packageVersion
          package:
            type: docker
            name: qits/golden-image
      """;

  /** What a state hands back: its parameters, keys sorted, and its unique tokens (none here). */
  public record Setup(Map<String, String> params, List<String> uniqueTokens) {}

  @Inject ConfigurationService configuration;

  @Inject DeclarationService declarations;

  private final Map<String, Supplier<Setup>> states = new LinkedHashMap<>();

  /** {application, env, key} triples and {application, version} pairs written since clean-up. */
  private final List<String[]> entries = Collections.synchronizedList(new ArrayList<>());

  private final List<String[]> declared = Collections.synchronizedList(new ArrayList<>());

  public ProviderStates() {
    states.put(A_DECLARED_APPLICATION_WITH_ENTRIES, this::aDeclaredApplicationWithEntries);
    states.put(AN_APPLICATION_WITH_NO_DECLARATION, this::anApplicationWithNoDeclaration);
    states.put(
        AN_APPLICATION_WITH_STORED_ENTRIES_AND_NO_DECLARATION,
        this::anApplicationWithStoredEntriesAndNoDeclaration);
    states.put(
        AN_APPLICATION_WITH_AN_ENTRY_IN_AN_ENVIRONMENT, this::anApplicationWithAnEntryInAnEnvironment);
    states.put(AN_APPLICATION_WITH_NO_CONFIGURATION, this::anApplicationWithNoConfiguration);
    states.put(
        ENTRIES_OF_A_RETIRED_CONFIGURATION_KEY, this::entriesOfARetiredConfigurationKey);
  }

  /** Every state name this provider answers for. */
  public Set<String> names() {
    return Collections.unmodifiableSet(states.keySet());
  }

  /** Runs the named state; an unknown name is a programming error, not an empty state. */
  public Setup setUp(String state) {
    Supplier<Setup> setup = states.get(state);
    if (setup == null) {
      throw new IllegalArgumentException(
          "No provider state '" + state + "' — this provider answers for " + states.keySet());
    }
    return setup.get();
  }

  /** {@link #setUp} for a pact {@code @State} method, which returns only the params. */
  public Map<String, String> params(String state) {
    return setUp(state).params();
  }

  /**
   * Removes every entry and declaration a state wrote, and the declaration a consumer's write
   * made in {@link #AN_APPLICATION_WITH_NO_DECLARATION}. Absent rows are not an error.
   */
  public void cleanUp() {
    List<String[]> entryRows;
    List<String[]> declarationRows;
    synchronized (entries) {
      entryRows = List.copyOf(entries);
      entries.clear();
    }
    synchronized (declared) {
      declarationRows = List.copyOf(declared);
      declared.clear();
    }
    for (String[] e : entryRows) {
      try {
        configuration.delete(ENV, e[0], e[1], ACTOR);
      } catch (NotFoundException gone) {
        // already removed
      }
    }
    for (String[] d : declarationRows) {
      try {
        declarations.remove(d[0], d[1], ACTOR);
      } catch (NotFoundException gone) {
        // never written, or already removed
      }
    }
  }

  /** The state's slug: lower-cased, every run of non-alphanumerics replaced by {@code -}. */
  public static String slug(String state) {
    return state.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
  }

  // --- the states ------------------------------------------------------------------------------

  /**
   * One application that declared {@link #DECLARATION} at {@link #VERSION}, with two entries in
   * {@link #ENV}: an override of the defaulted string and the image version.
   */
  private Setup aDeclaredApplicationWithEntries() {
    declarations.declare(DECLARED_APP, VERSION, "environment", DECLARATION, ACTOR);
    declared.add(new String[] {DECLARED_APP, VERSION});
    put(DECLARED_APP, "env.QITS_GREETING", "hello from the store");
    put(DECLARED_APP, "env.QITS_IMAGE_VERSION", "2026.1010.120000");
    return new Setup(paramsOf(DECLARED_APP), List.of());
  }

  /**
   * An application with nothing stored: no entries, no declaration. A consumer that declares in
   * it gets a fresh 201; the clean-up removes what it declared.
   */
  private Setup anApplicationWithNoDeclaration() {
    declared.add(new String[] {UNDECLARED_APP, VERSION});
    return new Setup(paramsOf(UNDECLARED_APP), List.of());
  }

  /** One application with an entry and no declaration at any version. */
  private Setup anApplicationWithStoredEntriesAndNoDeclaration() {
    put(ENTRIES_ONLY_APP, "env.QITS_GREETING", "hello from the store");
    Map<String, String> params = new TreeMap<>();
    params.put("application", ENTRIES_ONLY_APP);
    params.put("env", ENV);
    return new Setup(params, List.of());
  }

  /**
   * qits-projects-service's agent MCP application with one credential entry in {@link #ENV}. The
   * entry is not declared: the credentials are set by hand.
   */
  private Setup anApplicationWithAnEntryInAnEnvironment() {
    put(AGENT_MCP_APP, AGENT_MCP_KEY, "golden-token-value");
    Map<String, String> params = new TreeMap<>();
    params.put("application", AGENT_MCP_APP);
    params.put("env", ENV);
    params.put("key", AGENT_MCP_KEY);
    return new Setup(params, List.of());
  }

  /**
   * An application with no entry and no declaration. A consumer imports {@link #IMPORT} into it;
   * the clean-up removes the entry the import wrote.
   */
  private Setup anApplicationWithNoConfiguration() {
    entries.add(new String[] {IMPORTED_APP, IMPORTED_KEY});
    Map<String, String> params = new TreeMap<>();
    params.put("application", IMPORTED_APP);
    params.put("env", ENV);
    return new Setup(params, List.of());
  }

  /**
   * One application whose key {@link #RETIRED_KEY} only an older version declares. In order:
   * {@link #RETIRED_VERSION} declares the retired key and a greeting, both get entries, then
   * {@link #PINNED_VERSION} declares the greeting alone. With {@link #PINNED_VERSION} pinned, the
   * entry collector removes the retired entry and keeps the greeting as pinned. The order matters:
   * an entry written after the newest declaration is kept as staged.
   */
  private Setup entriesOfARetiredConfigurationKey() {
    declarations.declare(
        RETIRING_APP,
        RETIRED_VERSION,
        "environment",
        """
        keys:
          env.QITS_GREETING:
            type: string
          env.QITS_RETIRED:
            type: string
        """,
        ACTOR);
    declared.add(new String[] {RETIRING_APP, RETIRED_VERSION});
    put(RETIRING_APP, "env.QITS_GREETING", "hello from the store");
    put(RETIRING_APP, RETIRED_KEY, "no longer read");
    declarations.declare(
        RETIRING_APP,
        PINNED_VERSION,
        "environment",
        """
        keys:
          env.QITS_GREETING:
            type: string
        """,
        ACTOR);
    declared.add(new String[] {RETIRING_APP, PINNED_VERSION});
    Map<String, String> params = new TreeMap<>();
    params.put("application", RETIRING_APP);
    params.put("env", ENV);
    params.put("version", PINNED_VERSION);
    return new Setup(params, List.of());
  }

  private void put(String application, String key, String value) {
    configuration.upsert(ENV, application, key, value, ACTOR);
    entries.add(new String[] {application, key});
  }

  private static Map<String, String> paramsOf(String application) {
    Map<String, String> params = new TreeMap<>();
    params.put("application", application);
    params.put("env", ENV);
    params.put("version", VERSION);
    return params;
  }
}
