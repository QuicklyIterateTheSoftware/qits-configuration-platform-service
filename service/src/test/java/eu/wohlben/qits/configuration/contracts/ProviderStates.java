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

  /** Who the states write as. Recorded in {@code updatedBy}, so it must not vary. */
  static final String ACTOR = "golden-master";

  /** The env every state writes in. */
  static final String ENV = "test";

  /** The version every state declares, or that its consumer declares. */
  static final String VERSION = "2026.1010.1";

  static final String DECLARED_APP = "golden-declared-app";
  static final String UNDECLARED_APP = "golden-undeclared-app";

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
