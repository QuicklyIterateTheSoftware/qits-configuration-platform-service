package eu.wohlben.qits.configuration.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.configuration.control.ImagePins.Pin;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The map itself, with no database and no Quarkus boot behind it: the two views have to be the same
 * list, and the reported order has to be the one the contract names.
 *
 * <p>And since the authored list stopped being the whole answer, {@link ImagePins#merge} is here too
 * — the one function that decides which of a declared pin and an authored one wins.
 *
 * <p><b>THE AUTHORED LIST IS EMPTY as of 2026-09-17, and that changed what these tests can be
 * written against.</b> They used to take the real authored rows as their fixture, which was the
 * better test while there were any: a real consumer adopting declarations one key at a time is the
 * case worth proving. There is no real row left to take, so the merge cases below are written
 * against SYNTHETIC authored pins passed straight into {@code merge}. That is not a weakening of
 * what is asserted — {@code merge} takes both halves as arguments and has never read {@link
 * ImagePins#ORDERED} itself — but it is the reason a reader will not find the platform's own names
 * in them. The one thing asserted about the real list is that it is empty and that both views agree
 * about it.
 */
class ImagePinsTest {

  /**
   * NOTHING IS PINNED BY HAND ANY MORE, and this is the assertion that says so out loud.
   *
   * <p>It is worth a test rather than a comment because an empty list is exactly what a careless
   * edit produces by accident, and because the opposite — a row quietly reappearing — is one of the
   * retired defects coming back: a release would start rewriting an entry its consumer renamed away
   * from, and qits-artifacts would be told to protect a tag on the strength of it. Adding a row is a
   * legitimate change (see the class javadoc: an empty list is not a retired mechanism), and this
   * test failing is how that change announces itself for review rather than landing unread.
   */
  @Test
  void nothingIsAuthoredByHandAndBothViewsSayTheSame() {
    assertEquals(
        List.of(),
        ImagePins.ORDERED,
        "every image pin on this platform is declared by its application now");
    assertEquals(Map.of(), ImagePins.BY_IMAGE, "and the listener has nothing to match against");
  }

  /**
   * The two views are one list. A pin the listener would write and the report would not mention —
   * or the other way round — is exactly the disagreement this class exists to make impossible.
   */
  @Test
  void everyPinIsReachableThroughBothViews() {
    assertEquals(
        ImagePins.ORDERED.size(),
        ImagePins.BY_IMAGE.values().stream().mapToInt(List::size).sum(),
        "grouping by image must lose nothing");
    for (Pin pin : ImagePins.ORDERED) {
      assertTrue(
          ImagePins.BY_IMAGE.get(pin.image()).contains(pin),
          () -> pin + " is reported but would never be written");
    }
  }

  /**
   * The absence of the images that have left, which is a different claim from the rule about how a
   * name is matched.
   *
   * <p><b>The whole-name rule is no longer exercisable here and the javadoc on {@link
   * ImagePins#BY_IMAGE} says where it lives now</b>: an empty map answers nothing to a prefix lookup
   * and to a whole-name one alike, so a test here would pass under either implementation. What is
   * still worth holding is that these four names find nothing — each one is a row that left because
   * its consumer pins the image in its own pom, and each one reappearing is the retired defect
   * coming back.
   */
  @Test
  void theImagesThatLeftArePinnedByNothingHere() {
    assertNull(
        ImagePins.BY_IMAGE.get("qits/project-agent"),
        "the agent image is qits-projects' pom's business now, proven by ProjectAgentDaemonPinIT");
    assertNull(
        ImagePins.BY_IMAGE.get("qits/workspace"),
        "the workspace image is pinned by nothing here; its last consumer pins it in its own pom");
    assertNull(
        ImagePins.BY_IMAGE.get("qits/workspace-editor"),
        "the editor's version is qits-workspaces' pom's business now, not configuration's");
    assertNull(ImagePins.BY_IMAGE.get("qits/qits-stt"), "an image we do not pin has no entry");
  }

  // ------------------------------------------------------------ the merge

  /**
   * The authored half of every merge case below. Synthetic, because {@link ImagePins#AUTHORED} is
   * empty — see the class javadoc for why that is the honest fixture rather than a shortcut. The
   * names are shaped like the rows that used to be here so the cases still read as the thing they
   * model: an application carried by hand while it adopts declarations one key at a time.
   */
  private static final List<Pin> AUTHORED_FIXTURE =
      List.of(new Pin("qits/sample-agent", "qits-sample", "env.QITS_SAMPLE_AGENT_IMAGE_VERSION"));

  /** With nothing declared, the answer is exactly the authored list — the platform as it was. */
  @Test
  void nothingDeclaredLeavesTheAuthoredListAsItIs() {
    assertEquals(List.of(), ImagePins.merge(List.of(), List.of()));
    assertEquals(AUTHORED_FIXTURE, ImagePins.merge(List.of(), AUTHORED_FIXTURE));
  }

  /**
   * And with the platform's real authored list — empty — a declared set is the whole answer,
   * untouched. That is the state every pin on this platform is in today, so it is asserted directly
   * rather than left to follow from the fixture cases.
   */
  @Test
  void withNothingAuthoredTheDeclaredSetIsTheWholeAnswer() {
    List<Pin> declared =
        List.of(new Pin("qits/stt", "qits-stt", "env.QITS_STT_VERSION"));

    assertEquals(declared, ImagePins.merge(declared, ImagePins.ORDERED));
  }

  /**
   * THE SHADOWING RULE: a declared pin REPLACES the authored row naming the same (application, key),
   * and the image it names is the one that survives.
   *
   * <p>The two disagreeing about the image is the case that matters. An application that has renamed
   * the image it starts says so in its declaration, and an answer carrying both names would have
   * qits-artifacts protecting a tag nobody launches while the one it does launch ages out.
   */
  @Test
  void aDeclaredPinShadowsTheAuthoredRowForItsOwnPair() {
    Pin declared =
        new Pin("qits/sample-agent-next", "qits-sample", "env.QITS_SAMPLE_AGENT_IMAGE_VERSION");

    List<Pin> merged = ImagePins.merge(List.of(declared), AUTHORED_FIXTURE);

    assertEquals(
        AUTHORED_FIXTURE.size(),
        merged.size(),
        "a declaration of a pair already authored is a replacement, not an addition");
    assertTrue(merged.contains(declared), "the declared row is the one that survives");
    assertFalse(
        merged.containsAll(AUTHORED_FIXTURE),
        "and the authored row it shadows is gone, image and all");
  }

  /**
   * Shadowing is per PAIR, not per application: a half-adopted consumer has declared one of its keys
   * and is still carried by hand on another, and the one it has not declared must not disappear with
   * the one it has.
   */
  @Test
  void anApplicationThatDeclaresOneKeyKeepsTheAuthoredRowForItsOther() {
    Pin declared = new Pin("qits/sample-box", "qits-sample", "env.QITS_SAMPLE_BOX_IMAGE_VERSION");

    List<Pin> merged = ImagePins.merge(List.of(declared), AUTHORED_FIXTURE);

    assertTrue(merged.contains(declared));
    assertTrue(
        merged.containsAll(AUTHORED_FIXTURE),
        "the same application's undeclared key is still pinned by hand");
  }

  /**
   * The answer is in the report's order whichever half a row came out of — the contract a machine
   * diffs one run against the last one under, and it must not be a property of which list won.
   */
  @Test
  void theMergedAnswerIsInTheReportsOrder() {
    List<Pin> declared =
        List.of(
            new Pin("qits/stt", "qits-stt", "env.QITS_STT_VERSION"),
            new Pin("qits/build-agent", "qits-ci", "env.QITS_CI_BUILD_AGENT_VERSION"));

    List<Pin> merged = ImagePins.merge(declared, AUTHORED_FIXTURE);

    assertEquals(
        merged.stream()
            .sorted(
                Comparator.comparing(Pin::image)
                    .thenComparing(Pin::application)
                    .thenComparing(Pin::key))
            .toList(),
        merged,
        "image, then application, then key — across both halves at once");
    assertEquals(AUTHORED_FIXTURE.size() + declared.size(), merged.size());
  }

  @Test
  void theViewsAreImmutable() {
    assertThrows(
        UnsupportedOperationException.class,
        () ->
            ImagePins.BY_IMAGE.put(
                "qits/anything", List.of(new Pin("qits/anything", "app", "env.A"))));
    assertThrows(
        UnsupportedOperationException.class,
        () -> ImagePins.ORDERED.add(new Pin("qits/anything", "app", "env.A")));
  }
}
