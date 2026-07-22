package org.camunda.community.zeebe.play

import io.camunda.zeebe.model.bpmn.Bpmn
import io.camunda.client.api.response.ActivatedJob
import io.camunda.connector.api.outbound.OutboundConnectorFunction
import io.camunda.connector.runtime.core.Keywords
import io.camunda.connector.runtime.core.config.OutboundConnectorConfiguration
import io.zeebe.zeeqs.data.entity.Process
import io.zeebe.zeeqs.data.repository.ProcessRepository
import org.assertj.core.api.Assertions.assertThat
import org.camunda.community.zeebe.play.connectors.ConnectorExecutionResult
import org.camunda.community.zeebe.play.connectors.ConnectorSecret
import org.camunda.community.zeebe.play.connectors.ConnectorSecretRepository
import org.camunda.community.zeebe.play.connectors.ConnectorService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import java.time.Instant
import java.time.Duration

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ConnectorServiceTest(
    @Autowired private val connectorService: ConnectorService,
    @Autowired private val processRepository: ProcessRepository,
    @Autowired private val connectorSecretRepository: ConnectorSecretRepository
) {

    private val processDefinitionKey: Long = 10

    private val bpmnModel = Bpmn.createExecutableProcess("process")
        .startEvent()
        .serviceTask(
            "A", {
                it.zeebeJobType("A")
                    .zeebeInput("secrets.X", "x")
            }
        ).serviceTask(
            "B",
            {
                it.zeebeJobType("B")
                    .zeebeInputExpression("""{y: "secrets.Y"}""", "y")
            }
        )
        .serviceTask(
            "C",
            {
                it.zeebeJobType("C")
                    .zeebeInputExpression(
                        """base + "/key/{{secrets.Z}}" """,
                        "z"
                    )
            }
        )
        .serviceTask(
            "D", {
                it.zeebeJobType("D")
                    .zeebeInput("secrets.D-1", "d")
            }
        )
        .done()

    @BeforeEach
    fun `set up process repository`() {
        processRepository.save(
            Process(
                key = processDefinitionKey,
                bpmnProcessId = "process",
                version = 1,
                bpmnXML = Bpmn.convertToString(
                    bpmnModel
                ),
                deployTime = Instant.now().toEpochMilli(),
                resourceName = "process.bpmn",
                checksum = "checksum"
            )
        )
    }

    @AfterEach
    fun `clean connector secrets repo`() {
        connectorSecretRepository.deleteAll()
    }

    @Test
    fun `should get referenced secrets of process`() {
        // given

        // when
        val secrets =
            connectorService.getReferencedConnectorSecrets(processDefinitionKey)

        // then
        assertThat(secrets).hasSize(4).contains("X", "Y", "Z", "D-1")
    }

    @Test
    fun `should get all secrets if no secrets are stored`() {
        // given

        // when
        val secrets =
            connectorService.getMissingConnectorSecrets(processDefinitionKey)

        // then
        assertThat(secrets).hasSize(4).contains("X", "Y", "Z", "D-1")
    }

    @Test
    fun `should get the missing secrets`() {
        // given
        connectorSecretRepository.saveAll(
            listOf(
                ConnectorSecret(name = "X", value = "xxx"),
                ConnectorSecret(name = "Z", value = "zzz"),
                ConnectorSecret(name = "D-1", value = "ddd"),
            )
        )

        // when
        val secrets =
            connectorService.getMissingConnectorSecrets(processDefinitionKey)

        // then
        assertThat(secrets).hasSize(1).contains("Y")
    }

    @Test
    fun `should get no secrets if all secrets are stored`() {
        // given
        connectorSecretRepository.saveAll(
            listOf(
                ConnectorSecret(name = "X", value = "xxx"),
                ConnectorSecret(name = "Y", value = "xxx"),
                ConnectorSecret(name = "Z", value = "zzz"),
                ConnectorSecret(name = "D-1", value = "ddd"),
            )
        )

        // when
        val secrets =
            connectorService.getMissingConnectorSecrets(processDefinitionKey)

        // then
        assertThat(secrets).isEmpty()
    }

    @Test
    fun `should fail connector job if retry backoff header is invalid`() {
        // given
        val job = activatedJob(
            retries = 3,
            customHeaders = mapOf(Keywords.RETRY_BACKOFF_KEYWORD to "not-a-duration")
        )

        // when
        val result = connectorService.executeConnectorJob(missingConnectorConfig(), job)

        // then
        assertThat(result).isInstanceOf(ConnectorExecutionResult.Fail::class.java)

        result as ConnectorExecutionResult.Fail
        assertThat(result.errorMessage)
            .isEqualTo("Failed to parse retry backoff header: not-a-duration")
        assertThat(result.retries).isZero()
        assertThat(result.retryBackoff).isNull()
        assertThat(result.variables).containsKey("error")
    }

    @Test
    fun `should fail connector job with decremented retries when connector type is unavailable`() {
        // given
        val job = activatedJob(
            retries = 4,
            customHeaders = mapOf(Keywords.RETRY_BACKOFF_KEYWORD to "PT3S")
        )

        // when
        val result = connectorService.executeConnectorJob(missingConnectorConfig(), job)

        // then
        assertThat(result).isInstanceOf(ConnectorExecutionResult.Fail::class.java)

        result as ConnectorExecutionResult.Fail
        assertThat(result.retries).isEqualTo(3)
        assertThat(result.retryBackoff).isEqualTo(Duration.ofSeconds(3))
        assertThat(result.errorMessage).isNotBlank()
        assertThat(result.variables).containsKey("error")
    }

    private fun activatedJob(
        retries: Int,
        customHeaders: Map<String, String>
    ): ActivatedJob {
        val job = Mockito.mock(ActivatedJob::class.java)
        Mockito.`when`(job.retries).thenReturn(retries)
        Mockito.`when`(job.customHeaders).thenReturn(customHeaders)
        return job
    }

    private fun missingConnectorConfig(): OutboundConnectorConfiguration =
        OutboundConnectorConfiguration(
            "Missing connector",
            emptyArray(),
            "missing-connector-type",
            java.util.function.Supplier<OutboundConnectorFunction> {
                throw AssertionError("Connector should be resolved by type before supplier is used")
            }
        )
}
