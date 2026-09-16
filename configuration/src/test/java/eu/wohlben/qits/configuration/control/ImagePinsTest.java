package eu.wohlben.qits.configuration.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.configuration.control.ImagePins.Pin;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The map itself, with no database and no Quarkus boot behind it: the two views have to be the same
 * list, and the reported order has to be the one the contract names.
 *
 * <p>And since the authored list stopped being the whole answer, {@link ImagePins#merge} is here too
 * — the one function that decides which of a declared pin and an authored one wins. It is asserted
 * against the real authored list rather than a fixture, because the case worth proving is a real
 * consumer adopting declarations one key at a time.
 */
class ImagePinsTest {

  @Test
  void theOrderIsImageThenApplicationThenKey() {
    assertEquals(
        List.of(
            new Pin("qits/project-agent", "qits-projects", "env.QITS_PROJECTS_AGENT_IMAGE_VERSION"),
            new Pin(
                "qits/workspace", "qits-projects", "env.QITS_PROJECTS_REFINEMENT_IMAGE_VERSION")),
        ImagePins.ORDERED,
        "the answer's order is sorted, not the order the list happens to be typed in");
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
   * The match is a whole-name lookup, and the editor is what proves it is not a prefix one.
   *
   * <p>{@code qits/workspace-editor} opens with {@code qits/workspace} and has had no pin here since
   * qits-workspaces moved both image versions into its own pom (2026-09-16). Under a prefix match a
   * workspace release would therefore now write nothing extra and this would look fine; under the
   * whole-name match the editor is simply absent, which is the same answer an image nobody pins
   * gets. Asserting the absence keeps the rule under test after the collision that motivated it
   * stopped existing.
   */
  @Test
  void theWorkspaceImageMovesOnePinAndTheEditorImageNone() {
    assertEquals(1, ImagePins.BY_IMAGE.get("qits/workspace").size());
    assertNull(
        ImagePins.BY_IMAGE.get("qits/workspace-editor"),
        "the editor's version is qits-workspaces' pom's business now, not configuration's");
    assertNull(ImagePins.BY_IMAGE.get("qits/qits-stt"), "an image we do not pin has no entry");
  }

  // ------------------------------------------------------------ the merge

  /** With nothing declared, the answer is exactly the authored list — the platform as it was. */
  @Test
  void nothingDeclaredLeavesTheAuthoredListAsItIs() {
    assertEquals(List.of(), ImagePins.merge(List.of(), List.of()));
    assertEquals(ImagePins.ORDERED, ImagePins.merge(List.of(), ImagePins.ORDERED));
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
        new Pin("qits/project-agent-next", "qits-projects", "env.QITS_PROJECTS_AGENT_IMAGE_VERSION");

    List<Pin> merged = ImagePins.merge(List.of(declared), ImagePins.ORDERED);

    assertEquals(
        ImagePins.ORDERED.size(),
        merged.size(),
        "a declaration of a pair already authored is a replacement, not an addition");
    assertTrue(merged.contains(declared), "the declared row is the one that survives");
    assertFalse(
        merged.contains(
            new Pin(
                "qits/project-agent", "qits-projects", "env.QITS_PROJECTS_AGENT_IMAGE_VERSION")),
        "and the authored row it shadows is gone, image and all");
  }

  /**
   * Shadowing is per PAIR, not per application: a half-adopted consumer has declared one of its keys
   * and is still carried by hand on another, and the one it has not declared must not disappear with
   * the one it has.
   */
  @Test
  void anApplicationThatDeclaresOneKeyKeepsTheAuthoredRowForItsOther() {
    Pin declared =
        new Pin("qits/workspace", "qits-projects", "env.QITS_PROJECTS_REFINEMENT_IMAGE_VERSION");

    List<Pin> merged = ImagePins.merge(List.of(declared), ImagePins.ORDERED);

    assertTrue(merged.contains(declared));
    assertTrue(
        merged.contains(
            new Pin(
                "qits/project-agent", "qits-projects", "env.QITS_PROJECTS_AGENT_IMAGE_VERSION")),
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

    List<Pin> merged = ImagePins.merge(declared, ImagePins.ORDERED);

    assertEquals(
        merged.stream()
            .sorted(
                Comparator.comparing(Pin::image)
                    .thenComparing(Pin::application)
                    .thenComparing(Pin::key))
            .toList(),
        merged,
        "image, then application, then key — across both halves at once");
    assertEquals(ImagePins.ORDERED.size() + declared.size(), merged.size());
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
