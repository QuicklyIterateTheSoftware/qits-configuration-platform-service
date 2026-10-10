package eu.wohlben.qits.configuration.contracts.idp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.pact.consumer.Trigger;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * <b>The consumer pact against qits-idp</b> (qits-1149): what the quarkus-oidc tenant reads at
 * startup. It reads the discovery document, then the JWKS its {@code jwks_uri} names.
 *
 * <p>The rows come from qits-idp's golden masters through qits-pact-consumer. The pact file binds
 * {@code issuer} exactly: the tenant compares every token's {@code iss} with it. {@code jwks_uri}
 * and {@code token_endpoint} are addresses, matched by type.
 */
class IdpConsumerPactTest {

  static {
    // pact-jvm reports usage metrics over the network unless told not to; a test never should.
    System.setProperty("pact_do_not_track", "true");
  }

  static final String STATE = "a published signing key";

  static final GoldenMasters IDP = GoldenMasters.of("qits-idp-service", "qits-idp");

  static final GoldenInteraction DISCOVERY =
      GoldenInteraction.of(Trigger.event("StartupEvent"), STATE, "getOpenIdConfiguration")
          .consumes("issuer", "jwks_uri", "token_endpoint")
          .exact("issuer");

  static final GoldenInteraction JWKS =
      GoldenInteraction.of(Trigger.event("StartupEvent"), STATE, "getJwks")
          .consumes("keys[].kid", "keys[].kty", "keys[].n", "keys[].e", "keys[].alg", "keys[].use");

  static final ConsumerPact PACT =
      ConsumerPact.of("qits-configuration-service", IDP, DISCOVERY, JWKS);

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @Test
  void readsTheDiscoveryDocument() {
    PACT.run(
        DISCOVERY,
        (url, recorded) -> {
          JsonNode doc = get(url + recorded.examplePath());
          assertFalse(doc.path("issuer").asText().isBlank(), "issuer");
          assertTrue(URI.create(doc.path("jwks_uri").asText()).isAbsolute(), "jwks_uri");
          assertTrue(URI.create(doc.path("token_endpoint").asText()).isAbsolute(), "token_endpoint");
        });
  }

  @Test
  void readsTheSigningKeys() {
    PACT.run(
        JWKS,
        (url, recorded) -> {
          JsonNode keys = get(url + recorded.examplePath()).path("keys");
          assertFalse(keys.isEmpty(), "at least one key");
          for (JsonNode key : keys) {
            assertEquals("RSA", key.path("kty").asText());
            assertEquals("sig", key.path("use").asText());
            assertEquals("RS256", key.path("alg").asText());
            assertFalse(key.path("kid").asText().isBlank(), "kid");
            // What the tenant does with a key: build an RSA public key from n and e.
            Base64.Decoder b64 = Base64.getUrlDecoder();
            RSAPublicKey rsa =
                (RSAPublicKey)
                    KeyFactory.getInstance("RSA")
                        .generatePublic(
                            new RSAPublicKeySpec(
                                new BigInteger(1, b64.decode(key.path("n").asText())),
                                new BigInteger(1, b64.decode(key.path("e").asText()))));
            assertTrue(rsa.getModulus().bitLength() >= 2048, "an RSA key of 2048 bits or more");
          }
        });
  }

  @Test
  void theCommittedPactIsWhatTheRowsWrite() {
    PACT.assertEveryInteractionCarriesBothReferences();
    PACT.compareOrWritePactFile();
  }

  private static JsonNode get(String url) throws Exception {
    HttpResponse<String> response =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/json").build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), url);
    return MAPPER.readTree(response.body());
  }
}
