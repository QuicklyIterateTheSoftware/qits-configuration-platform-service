package eu.wohlben.qits.configuration.api;

import eu.wohlben.qits.auth.MachineAuth;
import eu.wohlben.qits.configuration.control.DeclarationService;
import eu.wohlben.qits.configuration.dto.DeclarationDto;
import eu.wohlben.qits.configuration.dto.DeclarationSummaryDto;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * Declarations: what an application says its own configuration keys ARE, one document per version.
 *
 * <p>Served under {@code /configuration/api/applications/{application}/declarations} — the {@code
 * /configuration/api} prefix is {@code quarkus.rest.path} and is not spelled here.
 *
 * <p><b>THE FIRST SYSTEM-ONLY WRITES IN THIS SERVICE, and the reason is worth stating plainly.</b>
 * Every other route here takes {@code qits:admin} and {@code qits:system} together, because every
 * other route serves a person and a machine doing the same thing: an operator sets a value, the
 * bootstrap imports a file of them, and a guard for either would lock out the other. A declaration
 * is not that. It is an ASSERTED FACT about a build — this version of this application declares these
 * keys — and the only thing that can honestly assert it is the pipeline that built the version. A
 * person hand-posting a declaration would be recording, as a fact of record, a document that no
 * build produced, under a version that means something else in the registry; every later resolved
 * read would then be answered against it. So the writes take {@code qits:system} AND call {@link
 * MachineAuth#require()}, which re-asks the audience question the token already passed — the
 * annotation and the guard fail independently, and the guard follows the {@code
 * qits.auth.machine.required} rollout gate while the annotation does not.
 *
 * <p><b>The reads keep the pair.</b> "What does this version declare" is a question an operator
 * standing in front of a misbehaving deployment needs to be able to ask, and it changes nothing.
 *
 * <p><b>The deployment target is a QUERY PARAMETER and not a field in the document.</b> Which plane
 * an application deploys onto is qits-platform-deployments' fact, spelled {@code deployment_target}
 * in that application's own {@code .config/qits/deployments.yml}; the pipeline holds it and passes
 * it with the seed. A file asserting its own plane would be a second answer to a settled question,
 * and the two would diverge the first time a service is promoted — with a peer's rendered address
 * pointing at a name that no longer resolves.
 *
 * <p><b>The body is the document, raw.</b> {@code application/yaml} or {@code text/plain}, taken
 * verbatim: the hash is over exactly these bytes, so anything that re-encoded the body on the way in
 * would make "the same document" a statement about this service's plumbing rather than about the
 * file the application committed.
 */
@Path("/applications/{application}/declarations")
@Produces(MediaType.APPLICATION_JSON)
public class DeclarationsController {

  /** What a pipeline posts a yml as. {@code text/plain} is accepted beside it, for curl. */
  private static final String APPLICATION_YAML = "application/yaml";

  @Inject DeclarationService declarations;

  @Inject MachineAuth machineAuth;

  @Inject SecurityIdentity identity;

  public record ListDeclarationsResponse(List<DeclarationSummaryDto> declarations) {}

  public record DeclareResponse(DeclarationDto declaration) {}

  /**
   * Take one declaration document in under {@code (application, version)}.
   *
   * <p>201 the first time a version is seen. <b>200 when the exact same document is posted again</b>
   * — no revision, no re-attribution, nothing appended: a pipeline step that retries is not an
   * event, and this is the same idempotency the entry import rests on. <b>409 when a DIFFERENT
   * document arrives under a version already taken</b>, naming both hashes, because a version is a
   * fixed point and two builds disagreeing about what it declared is a question rather than an
   * update. 422 when the document will not parse, naming the key.
   *
   * @param deploymentTarget {@code platform} or {@code environment} — required, and the deployer's
   *     fact rather than the document's
   */
  @POST
  @Path("/{version}")
  @Consumes({APPLICATION_YAML, MediaType.TEXT_PLAIN})
  @Operation(
      operationId = "declareKeys",
      summary = "Record one version's declaration of its configuration keys")
  @APIResponse(responseCode = "200", description = "This exact document was already recorded")
  @APIResponse(responseCode = "201", description = "Recorded")
  @APIResponse(
      responseCode = "400",
      description = "The application, version or deploymentTarget is not valid")
  @APIResponse(responseCode = "401", description = "Gate on and no machine token presented")
  @APIResponse(responseCode = "403", description = "Gate on and the token is for another service")
  @APIResponse(
      responseCode = "409",
      description = "A different document is already stored under this version")
  @APIResponse(responseCode = "422", description = "The document is not a valid declaration")
  @RolesAllowed("qits:system")
  public Response declare(
      @PathParam("application") String application,
      @PathParam("version") String version,
      @QueryParam("deploymentTarget") String deploymentTarget,
      String body) {
    machineAuth.require();
    DeclarationService.Intake intake =
        declarations.declare(application, version, deploymentTarget, body, actor());
    return Response.status(intake.created() ? Response.Status.CREATED : Response.Status.OK)
        .entity(new DeclareResponse(declarations.declaration(application, version)))
        .build();
  }

  /**
   * Remove one declaration and its keys, keeping the record that it was here.
   *
   * <p><b>THE TAG-RECOVERY DOOR, and that is the whole of what it is for.</b> A build that published
   * a declaration and was then re-cut under the same version has no other way past the 409 above —
   * which is the conflict doing its job. This is the deliberate, attributed, logged way to say "that
   * version is being re-published". Afterwards the newest surviving version governs again, so a
   * rollback needs nobody to re-post a document that has not changed.
   *
   * <p><b>It is not a cleanup path.</b> Nothing sweeps old declarations and nothing should: a
   * deployment that resolved against a version is answerable only while that version is still here.
   * Removing what is not there is a 404 rather than a courteous 204, for the same reason removing an
   * absent entry is: a delete that always succeeds cannot tell a caller it deleted the wrong thing.
   */
  @DELETE
  @Path("/{version}")
  @Operation(
      operationId = "removeDeclaration",
      summary = "Remove one version's declaration, keeping the record of it")
  @APIResponse(responseCode = "204", description = "Removed")
  @APIResponse(responseCode = "400", description = "The application or version is not valid")
  @APIResponse(responseCode = "401", description = "Gate on and no machine token presented")
  @APIResponse(responseCode = "403", description = "Gate on and the token is for another service")
  @APIResponse(responseCode = "404", description = "No such declaration")
  @RolesAllowed("qits:system")
  public Response remove(
      @PathParam("application") String application, @PathParam("version") String version) {
    machineAuth.require();
    declarations.remove(application, version, actor());
    return Response.noContent().build();
  }

  /**
   * Every version one application has declared, newest intake first, with the governing one flagged.
   *
   * <p>Read by a person as often as by a machine — "which document is this deployment being judged
   * against" is the first question of any argument about a resolved read — so it keeps the ordinary
   * pair of roles and calls no machine guard.
   */
  @GET
  @Operation(
      operationId = "listDeclarations",
      summary = "Every declaration of one application, newest first")
  @APIResponse(responseCode = "200", description = "The declarations")
  @APIResponse(responseCode = "400", description = "The application name is not valid")
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:system", "qits:agent"})
  public ListDeclarationsResponse list(@PathParam("application") String application) {
    return new ListDeclarationsResponse(declarations.declarationsOf(application));
  }

  /** One declaration in full: the keys this service parsed out of it, and the document itself. */
  @GET
  @Path("/{version}")
  @Operation(
      operationId = "getDeclaration",
      summary = "One declaration: its parsed keys and the document it came from")
  @APIResponse(responseCode = "200", description = "The declaration")
  @APIResponse(responseCode = "400", description = "The application or version is not valid")
  @APIResponse(responseCode = "404", description = "No such declaration")
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:system", "qits:agent"})
  public DeclarationDto get(
      @PathParam("application") String application, @PathParam("version") String version) {
    return declarations.declaration(application, version);
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
