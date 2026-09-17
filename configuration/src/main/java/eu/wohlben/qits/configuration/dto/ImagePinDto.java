package eu.wohlben.qits.configuration.dto;

/**
 * One configured container-image version: the image, the version stored for it, and the entry that
 * holds it.
 *
 * <p>{@code image} is the unqualified name qits-ci releases under ({@code qits/workspace}), so a
 * consumer composing a tag writes {@code image + ":" + version} and nothing else. {@code
 * application} and {@code key} name the entry the version was read from — the same pair a person can
 * open in the editor — so a row is answerable rather than merely asserted.
 *
 * <p><b>One row per (image &rarr; application+key) mapping</b>, which is why an image <i>can</i>
 * appear twice: two applications may start the same image under a key of each one's own, and
 * collapsing them would lose which entry is behind the version. No mapping does that today —
 * {@code qits/workspace} was the case, on qits-workspaces and qits-projects at once, until both
 * moved the version into their own poms in September 2026 — but the shape is the mapping's and not
 * the estate's, and an image also appears twice when two envs hold two versions of it.
 */
public record ImagePinDto(String image, String version, String application, String key) {}
