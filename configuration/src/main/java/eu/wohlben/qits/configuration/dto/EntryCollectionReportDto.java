package eu.wohlben.qits.configuration.dto;

import java.util.List;

/**
 * What one run of the entry collector judged and did.
 *
 * <p><b>No value appears anywhere in it, by construction</b>: an entry is addressed by (application,
 * env, key) and those three are all a receipt needs. Entries carry credentials, and a report that
 * quoted what it deleted would be a report nobody could file.
 *
 * <p>{@code examined} is the number of entries that got a verdict, so it equals {@code removed} plus
 * every {@code kept} count. An application whose declarations could not be read gets no verdict at
 * all — its entries are kept, counted nowhere, and named in {@code errors} — because counting them
 * under a reason would be claiming a judgement that was never made.
 */
public record EntryCollectionReportDto(
    boolean dryRun, int examined, List<Removed> removed, Kept kept, List<Failure> errors) {

  /** One entry collected (or, on a dry run, that would be). */
  public record Removed(
      String application, String env, String key, String reason, String lastDeclaredBy) {}

  /** Entries kept, by the first reason that held. See {@code RetiredEntryCollector}. */
  public record Kept(
      int unpinned,
      int undeclaredPinnedVersion,
      int pinned,
      int inFlight,
      int neverDeclared,
      int staged) {}

  /** One application whose entries were not judged, or one deletion that did not happen. */
  public record Failure(String application, String message) {}
}
