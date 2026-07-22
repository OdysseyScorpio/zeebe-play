package org.camunda.community.zeebe.play.zeebe

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import io.camunda.client.CamundaClient
import org.camunda.community.zeebe.play.rest.ZeebeServiceException
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

@Configuration
@ConditionalOnProperty(name = ["zeebe.engine"], havingValue = "remote")
@EnableConfigurationProperties(ZeebeClientProperties::class)
open class RemoteZeebeConfig {

    @Value(value = "\${zeebe.clock.endpoint}")
    private lateinit var zeebeClockEndpoint: String

    @Value(value = "\${zeebe.health.endpoint}")
    private lateinit var zeebeHealthEndpoint: String

    @Bean
    open fun remoteZeebeService(): ZeebeService {
        return RemoteZeebeService(
            clockEndpoint = zeebeClockEndpoint,
            healthEndpoint = zeebeHealthEndpoint
        )
    }

    @Bean
    fun zeebeClient(config: ZeebeClientProperties): CamundaClient {
        val builder = CamundaClient.newClientBuilder()
            .grpcAddress(config.broker.gatewayAddress.toGrpcUri(config.security.plaintext))
            .preferRestOverGrpc(false)

        config.security.caCertificatePath?.let { builder.caCertificatePath(it) }
        config.security.overrideAuthority?.let { builder.overrideAuthority(it) }

        return builder.build()
    }

    class RemoteZeebeService(
        val clockEndpoint: String,
        val healthEndpoint: String
    ) : ZeebeService {

        private val httpClient = HttpClient.newHttpClient()

        private val kotlinModule = KotlinModule.Builder().build()
        private val objectMapper = ObjectMapper().registerModule(kotlinModule)

        override fun start() {
            // the lifecycle is managed remotely
        }

        override fun stop() {
            // the lifecycle is managed remotely
        }

        override fun getCurrentTime(): Instant {
            val clockResponse = sendClockRequest(
                request = HttpRequest.newBuilder()
                    .uri(URI.create("http://$clockEndpoint"))
                    .header("Content-Type", "application/json")
                    .GET()
                    .build()
            )
            return Instant.parse(clockResponse.instant);
        }

        override fun increaseTime(duration: Duration): Long {
            val offsetMilli = duration.toMillis()
            val requestBody =
                HttpRequest.BodyPublishers.ofString("""{ "offsetMilli": $offsetMilli }""")

            val clockResponse = sendClockRequest(
                request = HttpRequest.newBuilder()
                    .uri(URI.create("http://$clockEndpoint/add"))
                    .header("Content-Type", "application/json")
                    .POST(requestBody)
                    .build()
            )
            return clockResponse.epochMilli;
        }

        private fun sendClockRequest(request: HttpRequest): ZeebeClockResponse {

            val response = httpClient
                .send(request, HttpResponse.BodyHandlers.ofString())

            val statusCode = response.statusCode()
            val responseBody = response.body()

            if (statusCode != 200) {
                throw ZeebeServiceException(
                    service = "time travel",
                    status = statusCode.toString(),
                    failureMessage = "$responseBody. Check if the clock endpoint is enabled (zeebe.clock.controlled = true)."
                )
            }

            return objectMapper.readValue<ZeebeClockResponse>(responseBody)
        }

        override fun isRunning(): Boolean {
            val health = sendHealthRequest(
                request = HttpRequest.newBuilder()
                    .uri(URI.create("http://$healthEndpoint"))
                    .header("Content-Type", "application/json")
                    .GET()
                    .build()
            )

            return health.status == "UP"
        }

        private fun sendHealthRequest(request: HttpRequest): ZeebeHealthResponse {

            val response = httpClient
                .send(request, HttpResponse.BodyHandlers.ofString())

            val statusCode = response.statusCode()
            val responseBody = response.body()

            if (statusCode != 200) {
                throw ZeebeServiceException(
                    service = "health",
                    status = statusCode.toString(),
                    failureMessage = "$responseBody. Check if the health endpoint is enabled."
                )
            }

            return objectMapper.readValue<ZeebeHealthResponse>(responseBody)
        }

        private data class ZeebeClockResponse(
            val epochMilli: Long,
            val instant: String
        )

        private data class ZeebeHealthResponse(
            val status: String
        )

    }

}

private fun String.toGrpcUri(plaintext: Boolean): URI {
    if (contains("://")) {
        return URI.create(this)
    }

    val scheme = if (plaintext) "http" else "https"
    return URI.create("$scheme://$this")
}

@ConfigurationProperties(prefix = "zeebe.client")
class ZeebeClientProperties {
    var broker = Broker()
    var security = Security()

    class Broker {
        var gatewayAddress: String = "127.0.0.1:26500"
    }

    class Security {
        var plaintext: Boolean = true
        var caCertificatePath: String? = null
        var overrideAuthority: String? = null
    }
}
