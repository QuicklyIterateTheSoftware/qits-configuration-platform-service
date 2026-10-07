package eu.wohlben.qits.configuration.api;

import eu.wohlben.qits.configuration.control.RetiredEntryCollector;
import eu.wohlben.qits.configuration.dto.EntryCollectionReportDto;
import eu.wohlben.qits.configuration.error.BadRequestException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The orchestrator's door into entry cleanup: {@code POST /configuration/api/gc/entries} collects the
 * entries of keys that no serving or rollback version declares any more.
 *
 * <p><b>The pins are an input, not a lookup</b>, on the pattern the platform's gc process already
 * runs on: qits-platform-orchestrator holds a credential for every peer, reads qits-deployments'
 * {@code GET /deployments/api/pins} and embeds that body verbatim as {@code deployments}. Its
 * {@code shas} are released VERSIONS — the name is historical. A body without them is a 400 that
 * deletes nothing: an absent pin list is not "nothing is running", and reading it that way would
 * make every declared key look retired.
 *
 * <p><b>What may be collected is decided in {@link RetiredEntryCollector}</b>, and the rule lives
 * there rather than here so no caller shapes a request around it. A dry run judges exactly as a real
 * one does and deletes nothing.
 *
 * <p>{@code qits:system} beside {@code qits:admin}, and no {@code MachineAuth.require()}: the
 * scheduled caller is a machine, and a person may run the same collection by hand — the reasoning
 * {@link ConfigurationController} gives for every route that serves both.
 */
@Path("/gc")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GcController {

  @Inject RetiredEntryCollector collector;

  @Inject SecurityIdentity identity;

  /** The request; unknown fields at any level are tolerated, which is Quarkus' Jackson default. */
  public record CollectEntriesRequest(boolean dryRun, Deployments deployments) {

    /** qits-deployments' pins answer, as it was read. */
    public record Deployments(List<Pin> pins) {}

    /** One application's pinned versions. */
    public record Pin(String applicationName, List<String> shas) {}
  }

  /**
   * Judge every stored entry against the pins and remove the retired ones — or, on a dry run, only
   * say which they are. The report names entries by (application, env, key) and never by value.
   */
  @POST
  @Path("/entries")
  @Operation(summary = "Collect the entries of retired keys that no pinned version declares")
  @APIResponse(responseCode = "200", description = "What was judged, removed, kept and failed")
  @APIResponse(responseCode = "400", description = "The deployments pins are missing or malformed")
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:system"})
  public EntryCollectionReportDto collectEntries(CollectEntriesRequest request) {
    return collector.collect(pinsOf(request), request.dryRun(), actor());
  }

  /** The pins by application, the union of every item naming it; refused whole if any is malformed. */
  private static Map<String, Set<String>> pinsOf(CollectEntriesRequest request) {
    if (request == null || request.deployments() == null || request.deployments().pins() == null) {
      throw new BadRequestException(
          "deployments.pins is required: embed the body of GET /deployments/api/pins");
    }
    Map<String, Set<String>> pins = new LinkedHashMap<>();
    for (CollectEntriesRequest.Pin pin : request.deployments().pins()) {
      if (pin == null
          || pin.applicationName() == null
          || pin.applicationName().isBlank()
          || pin.shas() == null
          || pin.shas().stream().anyMatch(version -> version == null || version.isBlank())) {
        throw new BadRequestException(
            "every deployments.pins item needs an applicationName and a list of shas");
      }
      pins.computeIfAbsent(pin.applicationName(), name -> new LinkedHashSet<>())
          .addAll(pin.shas());
    }
    return pins;
  }

  /** See the note on {@code ConfigurationController.actor()}: null is "no name worth recording". */
  private String actor() {
    if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null) {
      return null;
    }
    String name = identity.getPrincipal().getName();
    return name == null || name.isBlank() ? null : name;
  }
}
