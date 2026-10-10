package eu.wohlben.qits.configuration.api;

import eu.wohlben.qits.configuration.control.ConfigurationService;
import eu.wohlben.qits.configuration.dto.ApplicationSummaryDto;
import eu.wohlben.qits.configuration.dto.ConfigurationEntryDto;
import eu.wohlben.qits.configuration.dto.ConfigurationRevisionDto;
import eu.wohlben.qits.configuration.dto.ResolvedConfigurationDto;
import eu.wohlben.qits.configuration.mapper.ConfigurationMapper;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * Deployment configuration, by application and environment.
 *
 * <p>Served under {@code /configuration/api/applications} — the {@code /configuration/api} prefix is
 * {@code quarkus.rest.path}, not spelled here, so this class carries only its own noun.
 *
 * <p><b>THE ENV IS A PATH SEGMENT.</b> This service runs on the platform plane and holds every
 * environment's configuration in one store, so the address of a value is {@code
 * /applications/<application>/envs/<env>/...}. That is the whole promotion, expressed where a caller
 * cannot miss it: an edit names the env it edits, and a read names the env it reads. The env-less
 * spellings that carried callers across the plane move are gone — they answered for one configured
 * legacy env, and the last caller of one stopped asking.
 *
 * <p><b>Every route accepts the same pair of roles</b>, {@code qits:admin} (a person, through the
 * gateway's forward-auth headers) and {@code qits:system} (a machine, through a bearer validated
 * against qits-platform-idp). The reads are pulled by the deployer once per deployment and read by
 * an operator in a browser; the writes are made by an operator and by the bootstrap's import. A
 * machine-only guard on either would lock out the other half, which is why none of these calls
 * {@code MachineAuth.require()}. There is no anonymous route here.
 *
 * <p>Request and response shapes are nested records, the platform's controller idiom: the wire
 * contract for one operation lives beside the method that serves it.
 */
@Path("/applications")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ConfigurationController {

  @Inject ConfigurationService configuration;

  @Inject ConfigurationMapper mapper;

  @Inject SecurityIdentity identity;

  public record ListApplicationsResponse(List<ApplicationSummaryDto> applications) {}

  public record ListEntriesResponse(List<ConfigurationEntryDto> entries) {}

  public record ListHistoryResponse(List<ConfigurationRevisionDto> revisions) {}

  public record SetEntryRequest(String value) {

    public record Response(ConfigurationEntryDto entry) {}
  }

  /**
   * Every application this service holds configuration for, with one row per environment it is
   * configured in — that environment's entry count and how far its history has run.
   *
   * <p><b>It aggregates across envs rather than taking one</b>, because on a platform instance the
   * useful shape of this question is comparative: which tiers is this application configured in, and
   * do they look alike. A per-env listing would answer a question the entries route already answers
   * better.
   *
   * <p>An application whose entries have all been deleted is still listed, at zero entries in the
   * env it was deleted from: "where did my configuration go" is the question this listing most needs
   * to be able to answer.
   */
  @GET
  @Operation(
      operationId = "listApplications",
      summary = "Every configured application, with per-environment counts and revisions")
  @APIResponse(responseCode = "200", description = "The applications")
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:system", "qits:agent"})
  public ListApplicationsResponse applications() {
    return new ListApplicationsResponse(configuration.applications());
  }

  // ------------------------------------------------------------ env-addressed

  /**
   * THE DEPLOYER'S READ: one application's configuration in one environment, as a flat property map,
   * at the full prefixed spelling {@code qits.platform.deployments.extras.<app>.<key>}.
   *
   * <p>The names are complete on purpose — a consumer layers this map as a configuration source
   * verbatim, with no prefix to re-assemble and no second place for the deployer's namespace to be
   * written down. <b>They carry no env</b>: the container being configured is in exactly one, the one
   * named in this path, so an env inside the property names would be a segment every consumer had to
   * strip.
   *
   * <p>{@code headRevision} is what the caller records to say which configuration it deployed with.
   * It comes from the append-only log and is scoped to this env, so it moves forward on a delete as
   * well as on a write, and a write in another environment does not move it at all.
   *
   * <p>An application with nothing stored is an empty map at revision 0, never a 404: a deployer
   * that read a 404 as an error would refuse every deployment of an application nobody has
   * configured. The same holds for an env nobody has written into yet.
   *
   * <p><b>{@code ?version=} is what turns this into the OVERLAY read.</b> Given one, the answer is
   * that version's declaration merged underneath the stored entries: declared defaults for keys
   * nobody has set, and {@code serviceAddress} keys rendered for THIS environment. Omitted, the
   * answer is exactly what it has always been — entries and nothing else — which is not politeness
   * but the rollout: the deployer reads this route once per deployment and does not pass a version
   * yet, so requiring one would take the platform down for a feature nobody was using.
   *
   * <p>A version that names no declaration is a 404, unlike an application with no entries. The
   * asymmetry is the point: absent means "not asking about declarations", present means "resolve me
   * against this document", and answering the second with a bare entry map would hand back a
   * configuration missing every default the caller asked for, with nothing to say so.
   *
   * @param version the declaration to overlay, or absent for the entries alone
   */
  @GET
  @Path("/{application}/envs/{env}/resolved")
  @Operation(
      operationId = "resolveConfiguration",
      summary = "One application's configuration in one environment, fully prefixed")
  @APIResponse(responseCode = "200", description = "The resolved properties and the head revision")
  @APIResponse(responseCode = "400", description = "The environment or application name is invalid")
  @APIResponse(responseCode = "404", description = "The named declaration version does not exist")
  @APIResponse(
      responseCode = "422",
      description = "A serviceAddress addresses an application that has not declared its plane")
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:system", "qits:agent"})
  public ResolvedConfigurationDto resolvedIn(
      @PathParam("application") String application,
      @PathParam("env") String env,
      @QueryParam("version") String version) {
    return configuration.resolve(env, application, Optional.ofNullable(version));
  }

  /**
   * One application's current entries in one environment, by key.
   *
   * <p><b>Each row carries {@code orphaned}</b>, decided against the application's governing
   * declaration: true when that declaration does not account for the key, or declares it a
   * serviceAddress whose stored value is ignored in favour of the rendered address. It is computed
   * at read time and is a question for a person, never a reason to delete — an orphan is usually a
   * key the next deployment drops and sometimes a key somebody set early for a version not released
   * yet, and a store that tidied away the second kind would be a store nobody could stage a change
   * in. The strict subset that IS collected — retired keys no serving or rollback version declares —
   * goes through {@link GcController}, on the deployer's pins.
   */
  @GET
  @Path("/{application}/envs/{env}/entries")
  @Operation(
      operationId = "listEntries",
      summary = "One application's current entries in one environment")
  @APIResponse(responseCode = "200", description = "The entries")
  @APIResponse(responseCode = "400", description = "The environment or application name is invalid")
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:system", "qits:agent"})
  public ListEntriesResponse entriesIn(
      @PathParam("application") String application, @PathParam("env") String env) {
    return new ListEntriesResponse(configuration.entryViews(env, application));
  }

  /**
   * Set one entry's value in one environment.
   *
   * <p>201 the first time a key is seen IN THAT ENV, 200 afterwards — the same key in another
   * environment is another entry and is created on its own. <b>An identical value writes no
   * revision</b> and answers 200 with the entry unchanged, which is what makes a re-run of a seeding
   * script free and what keeps the history a record of changes rather than of runs.
   *
   * <p>The key is the extras grammar after the application segment. Its SHAPE is checked here; what
   * the value means is not this service's question — qits-platform-deployments' {@code
   * ServiceExtras} stays the single parser of a mount, a publish or an alias.
   */
  @PUT
  @Path("/{application}/envs/{env}/entries/{key}")
  @Operation(operationId = "setEntry", summary = "Set one entry's value in one environment")
  @APIResponse(responseCode = "200", description = "The entry, already present")
  @APIResponse(responseCode = "201", description = "The entry, newly created")
  @APIResponse(responseCode = "400", description = "The env, application, key or value is invalid")
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:system"})
  public Response setIn(
      @PathParam("application") String application,
      @PathParam("env") String env,
      @PathParam("key") String key,
      SetEntryRequest request) {
    return write(env, application, key, request);
  }

  /**
   * Remove one entry from one environment.
   *
   * <p>The value is not lost: a deleted revision is appended and the history keeps what was removed,
   * which is what makes an accidental delete answerable rather than merely regrettable. Nothing in
   * another environment is touched.
   */
  @DELETE
  @Path("/{application}/envs/{env}/entries/{key}")
  @Operation(
      operationId = "removeEntry",
      summary = "Remove one entry from one environment, keeping it in the history")
  @APIResponse(responseCode = "204", description = "Removed")
  @APIResponse(responseCode = "400", description = "The env, application or key is not valid")
  @APIResponse(responseCode = "404", description = "No such entry in that environment")
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:system"})
  public Response removeIn(
      @PathParam("application") String application,
      @PathParam("env") String env,
      @PathParam("key") String key) {
    configuration.delete(env, application, key, actor());
    return Response.noContent().build();
  }

  /**
   * One application's whole history in one environment, newest first. Deletions are in it, with a
   * null value.
   *
   * <p>The {@code seq} numbers are global to the log, so a gap between two rows here is another
   * environment's write and not a missing one.
   */
  @GET
  @Path("/{application}/envs/{env}/history")
  @Operation(
      operationId = "listHistory",
      summary = "One application's write history in one environment, newest first")
  @APIResponse(responseCode = "200", description = "The revisions")
  @APIResponse(responseCode = "400", description = "The environment or application name is invalid")
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:system", "qits:agent"})
  public ListHistoryResponse historyIn(
      @PathParam("application") String application, @PathParam("env") String env) {
    return new ListHistoryResponse(
        configuration.history(env, application).stream().map(mapper::toDto).toList());
  }

  // ------------------------------------------------------------ internals

  /** The write behind the PUT route, so the 201/200 rule is decided in one place. */
  private Response write(String env, String application, String key, SetEntryRequest request) {
    boolean existed = exists(env, application, key);
    ConfigurationEntryDto entry =
        configuration.view(
            configuration.upsert(
                env, application, key, request == null ? null : request.value(), actor()));
    return Response.status(existed ? Response.Status.OK : Response.Status.CREATED)
        .entity(new SetEntryRequest.Response(entry))
        .build();
  }

  /**
   * Whether the key is already there IN THAT ENV, asked before the write so the answer can be 201 or
   * 200.
   *
   * <p>It is a second read rather than a flag out of the service, and that is deliberate: the write
   * seam's job is to keep the revision and the head in step, and returning "did I create it" would
   * make the created/updated distinction part of a contract that has no other use for it. A racing
   * pair of first writes answers 201 twice, which costs a caller nothing.
   */
  private boolean exists(String env, String application, String key) {
    try {
      configuration.require(env, application, key);
      return true;
    } catch (eu.wohlben.qits.configuration.error.NotFoundException absent) {
      return false;
    }
  }

  /**
   * Who to record as the writer: the resolved principal's name.
   *
   * <p>Anonymous is not a security state here — every route is {@code @RolesAllowed}, so nothing
   * unauthenticated reaches this method — it is only the case where there is no name worth
   * recording, and null is the honest answer rather than a fabricated one.
   */
  private String actor() {
    if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null) {
      return null;
    }
    String name = identity.getPrincipal().getName();
    return name == null || name.isBlank() ? null : name;
  }
}
