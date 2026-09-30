package eu.wohlben.qits.configuration.api;

import eu.wohlben.qits.configuration.dto.ApplicationEnvSummaryDto;
import eu.wohlben.qits.configuration.dto.ApplicationSummaryDto;
import eu.wohlben.qits.configuration.dto.ConfigurationEntryDto;
import eu.wohlben.qits.configuration.dto.ConfigurationRevisionDto;
import eu.wohlben.qits.configuration.dto.DeclarationDto;
import eu.wohlben.qits.configuration.dto.DeclarationSummaryDto;
import eu.wohlben.qits.configuration.dto.DeclaredKeyDto;
import eu.wohlben.qits.configuration.dto.EntryCollectionReportDto;
import eu.wohlben.qits.configuration.dto.ImagePinDto;
import eu.wohlben.qits.configuration.dto.ImportSummaryDto;
import eu.wohlben.qits.configuration.dto.ResolvedConfigurationDto;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Native-image reflection registration for every type Jackson touches on this API.
 *
 * <p>{@code ConfigurationController.set} returns {@code Response.entity(...)}, which hides the
 * entity type from the build-time analysis — so in the native binary serialization fails at runtime
 * with a 500 while every JVM test stays green. Measured on a sibling, not theoretical:
 * qits-serviceregistry's first live {@code PUT /services/{name}} answered 500 on exactly this.
 *
 * <p>Some of these types happen to be reachable today through a declared return type; they are all
 * listed anyway, because which ones the analysis finds is an implementation detail no test guards.
 *
 * <p><b>A new response type joins this list in the commit that adds it.</b>
 */
@RegisterForReflection(
    targets = {
      ConfigurationController.ListApplicationsResponse.class,
      ConfigurationController.ListEntriesResponse.class,
      ConfigurationController.ListHistoryResponse.class,
      ConfigurationController.SetEntryRequest.class,
      ConfigurationController.SetEntryRequest.Response.class,
      ImagePinsController.ListPinsResponse.class,
      DeclarationsController.ListDeclarationsResponse.class,
      DeclarationsController.DeclareResponse.class,
      GcController.CollectEntriesRequest.class,
      GcController.CollectEntriesRequest.Deployments.class,
      GcController.CollectEntriesRequest.Pin.class,
      EntryCollectionReportDto.class,
      EntryCollectionReportDto.Removed.class,
      EntryCollectionReportDto.Kept.class,
      EntryCollectionReportDto.Failure.class,
      ApplicationSummaryDto.class,
      // NESTED INSIDE ApplicationSummaryDto AND STILL LISTED. The application listing is a record
      // holding a list of these, and a type reached only through a generic type argument is exactly
      // the kind the build-time analysis is least reliable about — the same class of failure the
      // Response.entity() note above describes, one level deeper.
      ApplicationEnvSummaryDto.class,
      ConfigurationEntryDto.class,
      ConfigurationRevisionDto.class,
      ResolvedConfigurationDto.class,
      ImagePinDto.class,
      ImportSummaryDto.class,
      DeclarationSummaryDto.class,
      DeclarationDto.class,
      // NESTED INSIDE DeclarationDto AND STILL LISTED, for the reason the note above gives about
      // ApplicationEnvSummaryDto: a type reached only through a generic type argument is the kind
      // the build-time analysis is least reliable about.
      DeclaredKeyDto.class
    })
final class ApiWireReflection {

  private ApiWireReflection() {}
}
