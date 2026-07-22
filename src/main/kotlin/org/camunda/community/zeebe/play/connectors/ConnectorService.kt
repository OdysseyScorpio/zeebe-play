package org.camunda.community.zeebe.play.connectors

import com.fasterxml.jackson.databind.ObjectMapper
import io.camunda.client.api.response.ActivatedJob
import io.camunda.connector.api.error.ConnectorException
import io.camunda.connector.api.error.ConnectorInputException
import io.camunda.connector.api.error.ConnectorRetryException
import io.camunda.connector.api.outbound.OutboundConnectorFunction
import io.camunda.connector.api.outbound.OutboundConnectorProvider
import io.camunda.connector.document.jackson.JacksonModuleDocumentDeserializer
import io.camunda.connector.document.jackson.JacksonModuleDocumentSerializer
import io.camunda.connector.feel.LocalFeelExpressionEvaluator
import io.camunda.connector.feel.jackson.JacksonModuleFeelFunction
import io.camunda.connector.jackson.ConnectorsObjectMapperSupplier
import io.camunda.connector.runtime.core.ConnectorResultHandler
import io.camunda.connector.runtime.core.Keywords
import io.camunda.connector.runtime.core.config.OutboundConnectorConfiguration
import io.camunda.connector.runtime.core.document.DocumentFactoryImpl
import io.camunda.connector.runtime.core.document.store.InMemoryDocumentStore
import io.camunda.connector.runtime.core.error.BpmnError
import io.camunda.connector.runtime.core.error.IgnoreError
import io.camunda.connector.runtime.core.error.JobError
import io.camunda.connector.runtime.core.intrinsic.DefaultIntrinsicFunctionExecutor
import io.camunda.connector.runtime.core.outbound.DefaultOutboundConnectorFactory
import io.camunda.connector.runtime.core.outbound.ErrorExpressionJobContext
import io.camunda.connector.runtime.core.outbound.JobHandlerContext
import io.camunda.connector.runtime.core.validation.ValidationUtil
import io.camunda.zeebe.model.bpmn.Bpmn
import io.camunda.zeebe.model.bpmn.BpmnModelInstance
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeInput
import io.zeebe.zeeqs.data.repository.ProcessRepository
import org.springframework.cache.annotation.Cacheable
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Controller
import java.time.Duration
import java.time.format.DateTimeParseException

@Controller
class ConnectorService(
    private val processRepository: ProcessRepository,
    private val connectorSecretRepository: ConnectorSecretRepository,
    private val connectorsSecretProvider: ConnectorsSecretProvider
) {

    // Connector secrets can be references in a string or in placeholder syntax
    // 1) { x: "secrets.MY_API_KEY"}
    // 2) "https://" + baseUrl + "/{{secrets.MY_API_KEY}}"
    private val secretRegex = Regex(pattern = "[\"|{]?secrets\\.((\\w|-)+)[\"|}]?")

    private val documentFactory = DocumentFactoryImpl(InMemoryDocumentStore.INSTANCE)

    private val objectMapper = connectorObjectMapper(documentFactory)

    private val validationProvider =
        ValidationUtil.discoverDefaultValidationProviderImplementation()

    private val connectorResultHandler = ConnectorResultHandler(objectMapper)

    private val connectorFactory by lazy {
        DefaultOutboundConnectorFactory(
            objectMapper,
            validationProvider,
            emptyList<OutboundConnectorFunction>(),
            emptyList<OutboundConnectorProvider>()
        ) { null }
    }

    fun getMissingConnectorSecrets(processDefinitionKey: Long): List<String> {
        val referencedSecrets =
            getReferencedConnectorSecrets(processDefinitionKey = processDefinitionKey)

        val existingSecrets = connectorSecretRepository.findAll().map { it.name }

        return referencedSecrets.filterNot { existingSecrets.contains(it) }
    }

    @Cacheable(cacheNames = ["processConnectorSecrets"])
    fun getReferencedConnectorSecrets(processDefinitionKey: Long): List<String> {
        return getBpmnModel(processDefinitionKey)
            ?.getModelElementsByType(ZeebeInput::class.java)
            ?.map { it.source }
            ?.flatMap { findReferencedSecrets(it) }
            ?.distinct()
            ?: emptyList()
    }

    private fun getBpmnModel(processDefinitionKey: Long): BpmnModelInstance? {
        return processRepository.findByIdOrNull(processDefinitionKey)
            ?.bpmnXML
            ?.byteInputStream()
            ?.let { Bpmn.readModelFromStream(it) }
    }

    private fun findReferencedSecrets(text: String): List<String> {
        return secretRegex
            .findAll(text)
            .map { it.groupValues[1] }
            .toList()
    }

    fun findAvailableConnectors(): List<OutboundConnectorConfiguration> {
        return connectorFactory.configurations.toList()
    }

    fun executeConnectorJob(
        connectorConfig: OutboundConnectorConfiguration,
        job: ActivatedJob
    ): ConnectorExecutionResult {
        val retryBackoff = try {
            retryBackoff(job)
        } catch (e: DateTimeParseException) {
            return ConnectorExecutionResult.Fail(
                errorMessage = "Failed to parse retry backoff header: ${e.parsedString}",
                retries = 0,
                retryBackoff = null,
                variables = exceptionVariables(e)
            )
        }

        return try {
            val connector = connectorFactory.getInstance(connectorConfig.type())
            val context = JobHandlerContext(
                job,
                connectorsSecretProvider,
                validationProvider,
                documentFactory,
                objectMapper
            )
            val response = connector.execute(context)
            toExecutionResult(response, job)
        } catch (e: Exception) {
            toFailureResult(e, job, retryBackoff)
        }
    }

    private fun toExecutionResult(response: Any?, job: ActivatedJob): ConnectorExecutionResult {
        val headers = job.customHeaders
        val outputVariables = connectorResultHandler.createOutputVariables(
            response,
            headers[Keywords.RESULT_VARIABLE_KEYWORD],
            headers[Keywords.RESULT_EXPRESSION_KEYWORD]
        )
        val responseForErrorExpression = outputVariables.takeIf { it.isNotEmpty() } ?: response
        val error = connectorResultHandler.examineErrorExpression(
            responseForErrorExpression,
            headers,
            ErrorExpressionJobContext(
                ErrorExpressionJobContext.ErrorExpressionJob(job.retries)
            )
        ).orElse(null)

        return when (error) {
            is BpmnError -> ConnectorExecutionResult.ThrowBpmnError(
                errorCode = error.errorCode(),
                errorMessage = error.errorMessage(),
                variables = error.variables() ?: emptyMap()
            )
            is JobError -> ConnectorExecutionResult.Fail(
                errorMessage = error.errorMessage(),
                retries = error.retries() ?: maxOf(job.retries - 1, 0),
                retryBackoff = error.retryBackoff(),
                variables = error.variablesWithErrorMessage()
            )
            is IgnoreError -> ConnectorExecutionResult.Complete(
                variables = error.variables() ?: emptyMap()
            )
            null -> ConnectorExecutionResult.Complete(outputVariables)
            else -> ConnectorExecutionResult.Fail(
                errorMessage = "Unsupported connector error: ${error.javaClass.simpleName}",
                retries = maxOf(job.retries - 1, 0),
                retryBackoff = null,
                variables = emptyMap()
            )
        }
    }

    private fun toFailureResult(
        exception: Exception,
        job: ActivatedJob,
        retryBackoff: Duration?
    ): ConnectorExecutionResult {
        val retries = when (exception) {
            is ConnectorInputException -> 0
            is ConnectorRetryException -> exception.retries ?: maxOf(job.retries - 1, 0)
            else -> maxOf(job.retries - 1, 0)
        }

        return ConnectorExecutionResult.Fail(
            errorMessage = truncate(exception.message ?: exception.javaClass.simpleName),
            retries = retries,
            retryBackoff = (exception as? ConnectorRetryException)?.backoffDuration ?: retryBackoff,
            variables = exceptionVariables(exception)
        )
    }

    private fun retryBackoff(job: ActivatedJob): Duration? =
        job.customHeaders[Keywords.RETRY_BACKOFF_KEYWORD]
            ?.let { Duration.parse(it) }

    private fun exceptionVariables(exception: Exception): Map<String, Any> {
        val error = linkedMapOf<String, Any>(
            "type" to exception.javaClass.name
        )

        exception.message?.let { error["message"] = truncate(it) }

        if (exception is ConnectorException) {
            exception.errorCode?.let { error["code"] = it }
            exception.errorVariables?.let { error["variables"] = it }
        }

        return mapOf("error" to error)
    }

    private fun truncate(message: String): String =
        message.take(MAX_ERROR_MESSAGE_LENGTH)

    private fun connectorObjectMapper(documentFactory: DocumentFactoryImpl): ObjectMapper {
        val mapper = ConnectorsObjectMapperSupplier.getCopy()
        val functionExecutor = DefaultIntrinsicFunctionExecutor(mapper)

        return mapper.registerModules(
            JacksonModuleDocumentDeserializer(
                documentFactory,
                functionExecutor,
                JacksonModuleDocumentDeserializer.DocumentModuleSettings.create()
            ),
            JacksonModuleFeelFunction(false, LocalFeelExpressionEvaluator()),
            JacksonModuleDocumentSerializer()
        )
    }

    companion object {
        private const val MAX_ERROR_MESSAGE_LENGTH = 6000
    }
}

sealed class ConnectorExecutionResult {
    data class Complete(val variables: Map<String, Any>) : ConnectorExecutionResult()

    data class ThrowBpmnError(
        val errorCode: String,
        val errorMessage: String?,
        val variables: Map<String, Any>
    ) : ConnectorExecutionResult()

    data class Fail(
        val errorMessage: String,
        val retries: Int,
        val retryBackoff: Duration?,
        val variables: Map<String, Any>
    ) : ConnectorExecutionResult()
}
