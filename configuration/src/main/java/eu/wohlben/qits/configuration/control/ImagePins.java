package eu.wohlben.qits.configuration.control;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE IMAGE PINS NOBODY HAS DECLARED YET — the residual list, and it is meant to shrink.
 *
 * <p>A pin says: when this docker image is released, its version becomes the value of this key on
 * this application — an ordinary configuration entry, which the deployer expands into an environment
 * variable and the application starts its next container from. <b>No image and no pin today:
 * {@link #AUTHORED} is empty.</b>
 *
 * <p><b>It was four until 2026-09-16, two until 2026-09-17, and none by the end of that day</b>, and
 * every row that left is the shape of where this list was always going: the consumer stopped taking
 * the image version from configuration and started pinning it as a maven dependency whose version
 * <em>is</em> the image tag. qits-workspaces went first, taking the workspace and editor rows with
 * it; qits-projects followed with the refinement container, which pins {@code
 * eu.wohlben.qits:qits-workspace-daemon-protocol}, and then with the project agent, which pins
 * {@code eu.wohlben.qits:qits-projects-daemon-protocol} — each gated by its own release request and
 * proven against the daemon it names by an integration test before it ships. The comment beside
 * {@link #AUTHORED} has the account of all four.
 *
 * <h2>AN EMPTY LIST IS NOT A RETIRED MECHANISM, AND MUST NOT BE READ AS ONE</h2>
 *
 * <p>Nothing here is special-cased on the list being empty and nothing may become so. Zero rows is
 * the campaign having finished its current wave, not this class having lost its job: it is still the
 * one place a pin the declaring side cannot express is written down, and the next consumer that
 * arrives before it can declare — a new image started per unit of work, on an application whose
 * {@code .config/qits/configuration.yml} does not carry the key — lands here and works with no other
 * edit. {@link #merge} is still where the declared half and this one meet, {@link #ORDERED} is still
 * what the report walks, and {@link #BY_IMAGE} is still what the listener matches against. Deleting
 * the class because its list is empty would take all three with it.
 *
 * <p><b>One image may still land on several applications</b>, and the list allows it: a pin is keyed
 * by the released image and nothing about the write assumes one entry per application, so a second
 * row naming the same image and a different application needs no change to the mechanism. There is
 * no live instance of it any more — {@code qits/workspace} was the worked example, on qits-workspaces
 * and qits-projects at once, and both halves of it have gone. The rule is written down rather than
 * demonstrated, which is the honest state of it.
 *
 * <p><b>The key spelling is the env override of the property the consumer reads</b>, not a name
 * invented here. SmallRye maps an env var onto a property by its own uppercase-and-underscore rule
 * and lets the env win over the committed default, so a key that does not transcribe an existing
 * property writes an entry the consumer never reads — and says nothing about it at any log level.
 * That is not a historical remark: it is the rule the next row typed below has to satisfy.
 *
 * <h2>Why it is here rather than beside the listener that writes it</h2>
 *
 * <p>Two paths read this list and they must never disagree. {@code bus/SoftwareReleaseListener}
 * matches a {@code SoftwareRelease} against {@link #BY_IMAGE} and WRITES the version; {@code
 * api/ImagePinsController} walks {@link #ORDERED}, resolves each mapping against the current entries
 * and REPORTS what is pinned. A private copy in either of them would be a second opinion about what
 * this platform launches — the pin mechanism and the pin report have to be the same list or the
 * report is fiction. Adding a pin is one more {@link Pin} in {@link #AUTHORED}; both views derive
 * from it, and nothing else changes.
 *
 * <h2>AND BOTH PATHS NOW ASK THE APPLICATIONS FIRST</h2>
 *
 * <p><b>This list is no longer the whole answer, and the direction of travel is out of it.</b> An
 * application that declares {@code packageVersion} keys in its own
 * {@code .config/qits/configuration.yml} says which package each key carries a version of, and that
 * declaration is a fact about the build rather than a mapping somebody remembered to add here. So
 * the listener matches a release against the DECLARED coordinates and this list, and the report is
 * the union of both — {@link #merge} is the one place the two are put together, so the write and the
 * report cannot disagree about which of them won.
 *
 * <p><b>{@link #AUTHORED} is therefore the residual: what is still true because nobody has declared
 * it.</b> A consumer that adopts declarations takes its own rows out of here in the same wave —
 * {@link #merge} makes that safe to do in either order, since a declaration already shadows the
 * authored row for the same (application, key) while it is still typed below. Do not add a pin here
 * that could be declared; the entry left in this list is the one whose application cannot say it
 * yet.
 *
 * <h2>What the report is for: qits-artifacts' garbage collection</h2>
 *
 * <p>qits-artifacts reads {@code GET /configuration/api/pins} as a <b>pin source</b> when it decides
 * which container images it may delete. A configured image version is one a container launch will
 * pull <i>cold</i> — the deployer expands the entry, the host has never seen the tag, and no access
 * timestamp on the registry says so, because nothing has accessed it yet. Age and last-access are
 * exactly the wrong evidence for this class of image, so the answer here is the evidence instead.
 *
 * <p>The other direction is just as load-bearing: an image OUTSIDE this list is not
 * launchable-by-configuration at all, so it needs no row and gets none. That is what keeps the answer
 * a short, checkable statement about what the platform starts rather than a listing of everything it
 * has ever built.
 */
public final class ImagePins {

  /**
   * Where a released version lands: the package it is a version of, the application, then the
   * env-var key.
   *
   * <p>{@code image} is the released package's NAME, which for every pin authored below is a docker
   * image and is spelled exactly as qits-ci publishes it. A pin derived from a declaration carries
   * the declared {@code package.name} in the same component, so a declared docker coordinate and an
   * authored one are the same shape and {@link #merge} can put them side by side. The component
   * keeps its name because the report's wire field is {@code image} and the collector that reads it
   * parses that word.
   */
  public record Pin(String image, String application, String key) {}

  /**
   * The {@code packageType} of every pin written down here, as the wire and the declaration grammar
   * both spell it.
   *
   * <p>It is a constant rather than a literal in two files because two readers need it: the listener
   * gates {@link #BY_IMAGE} on it (an authored pin is an IMAGE pin, so a maven artifact of the same
   * name must not move one) and the pin report selects the declared keys it can answer for. A
   * declared coordinate of any other type — {@code binary} today — is matched by the declaration and
   * is outside this list by construction.
   */
  public static final String DOCKER_TYPE = "docker";

  /**
   * THE ANSWER'S ORDER — image, then application, then key — and every list of pins this class hands
   * out is in it, whether it came from here or from a declaration. One comparator, because the sort
   * is the contract {@code /pins} is diffed under and two spellings of it would be two contracts.
   */
  private static final Comparator<Pin> ORDER =
      Comparator.comparing(Pin::image).thenComparing(Pin::application).thenComparing(Pin::key);

  /**
   * The pins as they are written down, in whatever order reads best. Nothing depends on this order —
   * {@link #ORDERED} sorts it — so an entry goes wherever its explanation belongs.
   *
   * <p><b>It is the residual list</b> (see the class javadoc): every row here is one whose
   * application has not declared the key yet, and each one leaves as its consumer does.
   */
  private static final List<Pin> AUTHORED = List.of();

  // FOUR ROWS HAVE LEFT, AND ALL FOUR LEFT THE OTHER WAY — not to a declaration, but because their
  // consumer stopped taking the version from configuration at all:
  //
  //   2026-09-16  qits/workspace        qits-workspaces  env.QITS_WORKSPACE_IMAGE_VERSION
  //   2026-09-16  qits/workspace-editor qits-workspaces  env.QITS_EDITOR_IMAGE_VERSION
  //   2026-09-17  qits/workspace        qits-projects    env.QITS_PROJECTS_REFINEMENT_IMAGE_VERSION
  //   2026-09-17  qits/project-agent    qits-projects    env.QITS_PROJECTS_AGENT_IMAGE_VERSION
  //
  // qits-workspaces went first and pins both its images as MAVEN DEPENDENCIES whose own version is
  // the image tag (eu.wohlben.qits:qits-workspace-daemon-protocol and :qits-workspace-editor-image),
  // so the version it starts a container from is a reviewed line in its own pom, gated by its own
  // release request and proven against the daemon by WorkspaceDaemonPinIT before it ships.
  //
  // qits-projects followed on the 17th, twice in one day and by exactly the same mechanism both
  // times. Its refinement containers (refinementhost/RefinementContainerFactory) start from the
  // workspace image and it pins :qits-workspace-daemon-protocol — the same artifact, whose version
  // IS the qits/workspace tag. Its project-agent containers (agenthost/AgentContainerFactory) start
  // from qits/project-agent and it pins :qits-projects-daemon-protocol, the artifact that carries
  // the wire contract the service and that daemon already had to share, whose ${project.version} IS
  // the image tag. Both are ordinary dependencies, gated by that repository's own release request,
  // and proven against the daemon they name — RefinementDaemonPinIT and ProjectAgentDaemonPinIT —
  // before they ship. The refinement row had already replaced qits-projects-service's
  // ci-event-upstream-workspace-daemon.yml, which carried the same follow by rewriting a property
  // and releasing the service; the pom pins replace both, and are the first version of this follow
  // that anything tests before it is used.
  //
  // Writing any of them from here was the same defect four times over: a newly released image
  // reached the next container with nothing having tested the pair, and the entry aged past what the
  // registry still held. SO NEITHER qits/workspace NOR qits/project-agent HAS A PIN HERE AT ALL NOW
  // — not a row on a different application, not a row under a different key.
  //
  // THIS IS NOT A DECLARATION AND `merge` DOES NOT COVER IT. A declared pin shadows an authored row
  // for the same (application, key) — that is the ordinary way a row leaves, and the report keeps
  // answering for the pair under the name the application itself gave it. These four leave with no
  // successor at all, because the fact moved out of configuration entirely and there is no pair
  // left to answer for, so removing them here is the whole of it on this side. The entries they
  // already wrote are not deleted by this (nothing here deletes an entry, by design): each consumer
  // renames or drops its override key so the residue stops being read — qits-projects' agent key is
  // `…_IMAGE_VERSION_OVERRIDE` now, and agenthost/RetiredImageVersionKeys WARNs at boot for as long
  // as the old entry is still in the store. THAT RENAME IS THE WHOLE OF WHAT STOPS THE RESIDUE
  // DECIDING. Removing a row here stops future WRITES and nothing else.
  //
  // DO NOT ADD ANY OF THEM BACK to "keep the pin report complete". The report is about what is
  // launchable-by-configuration, and none of these images is, any more. A row put back would start
  // rewriting an entry nobody reads, and qits-artifacts would be told to protect a tag on the
  // strength of it.

  /**
   * Every pin in the answer's order — image, then application, then key — sorted here rather than
   * trusted to how {@link #AUTHORED} happens to be typed. The report is read by a machine that
   * compares one run's answer with the last one's, so the order is part of the contract and must not
   * be a property of an editor's cursor.
   */
  public static final List<Pin> ORDERED = AUTHORED.stream().sorted(ORDER).toList();

  /**
   * The same pins keyed by the unqualified {@code packageName} qits-ci publishes, which is how a
   * release is matched: <b>whole and exact, never a prefix</b>.
   *
   * <p>That rule was arrived at because {@code qits/workspace} and {@code qits/workspace-editor}
   * share an opening and each had pins of its own, so a prefix match would have had a workspace
   * release quietly writing the editor's key. Nothing is pinned here any more, so <b>the rule can no
   * longer be exercised against this map at all</b> — an empty map answers nothing to a whole-name
   * lookup and to a prefix one alike, and a test asserting that would pass under either
   * implementation. That removes every instance and none of the rule: matching by prefix is wrong
   * whether or not two current images happen to collide, and the next pair that shares an opening
   * must not have to rediscover this.
   *
   * <p><b>Where it IS still exercised, and it has to be somewhere:</b> against the DECLARED half,
   * which is where every live pin is now. {@code ConfigurationService} matches a release against
   * declared coordinates by indexed equality, and the listener's tests hold a declared {@code
   * …-next} coordinate against the image it opens with. Read that as the same rule in its surviving
   * home rather than as a second one — {@link #merge} is what makes the two halves one answer, so a
   * prefix creeping into either is the same defect.
   */
  public static final Map<String, List<Pin>> BY_IMAGE = byImage();

  private ImagePins() {}

  /**
   * THE MERGE, and it is the only place a declared pin and an authored one meet.
   *
   * <p><b>A declaration wins per (application, key), and nothing else about the authored row
   * survives that.</b> The pair is the identity of the thing being written — one entry, in one
   * application's configuration — so an authored row naming the same pair is the same pin described
   * twice, and the description the application itself shipped is the one to keep. It matters most
   * when the two disagree about the IMAGE: an application that has renamed the image it starts says
   * so in its declaration, and a report that answered with both names would have qits-artifacts
   * protecting a tag nobody launches while the one it does launch ages out.
   *
   * <p>Shadowing is per pair rather than per application, because an application may declare one of
   * its keys and still be carried by hand on another — that is the ordinary state of a half-adopted
   * consumer, and it must not lose the pin it has not declared yet.
   *
   * <p>The answer is sorted (image, application, key) rather than left in either input's order. The
   * report is read by a machine that diffs one run's answer against the last one's, so the order is
   * part of the contract and must not be a property of which half a row came out of.
   */
  public static List<Pin> merge(List<Pin> declared, List<Pin> authored) {
    Map<String, Pin> byPair = new LinkedHashMap<>();
    for (Pin pin : authored) {
      byPair.put(pair(pin), pin);
    }
    // Second, so a declared pin REPLACES the authored one holding its pair rather than being
    // dropped as a duplicate of it.
    for (Pin pin : declared) {
      byPair.put(pair(pin), pin);
    }
    return byPair.values().stream().sorted(ORDER).toList();
  }

  /** The identity of a pin as a written entry: the application and the key, and not the image. */
  private static String pair(Pin pin) {
    // The key grammar admits no whitespace and an application name is a dns label, so a space
    // cannot appear inside either half and this is unambiguous without escaping.
    return pin.application() + " " + pin.key();
  }

  private static Map<String, List<Pin>> byImage() {
    Map<String, List<Pin>> grouped = new LinkedHashMap<>();
    for (Pin pin : ORDERED) {
      grouped.computeIfAbsent(pin.image(), image -> new ArrayList<>()).add(pin);
    }
    grouped.replaceAll((image, pins) -> List.copyOf(pins));
    return Map.copyOf(grouped);
  }
}
