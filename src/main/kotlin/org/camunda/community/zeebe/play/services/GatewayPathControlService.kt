package org.camunda.community.zeebe.play.services

import com.fasterxml.jackson.databind.ObjectMapper
import io.camunda.client.CamundaClient
import io.camunda.zeebe.model.bpmn.Bpmn
import io.camunda.zeebe.model.bpmn.instance.Gateway
import io.camunda.zeebe.model.bpmn.instance.SequenceFlow
import io.zeebe.zeeqs.data.entity.Job
import io.zeebe.zeeqs.data.entity.JobState
import io.zeebe.zeeqs.data.repository.ElementInstanceRepository
import io.zeebe.zeeqs.data.repository.JobRepository
import io.zeebe.zeeqs.data.repository.ProcessRepository
import io.zeebe.zeeqs.data.service.VariableService
import org.camunda.feel.impl.parser.ExpressionVariableExtractor
import org.camunda.feel.impl.parser.FeelParser
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import scala.jdk.javaapi.CollectionConverters
import java.util.concurrent.ConcurrentHashMap

@Service
class GatewayPathControlService(
    private val zeebeClient: CamundaClient,
    private val jobRepository: JobRepository,
    private val processRepository: ProcessRepository,
    private val elementInstanceRepository: ElementInstanceRepository,
    private val variableService: VariableService
) {

    private val logger = LoggerFactory.getLogger(GatewayPathControlService::class.java)

    private val objectMapper = ObjectMapper()

    private val pendingJobs = ConcurrentHashMap.newKeySet<Long>()

    private val suppressedAutoContinue = ConcurrentHashMap<GatewayPathControlKey, Long>()

    @Scheduled(
        fixedDelayString = "\${zeebe-play.gateway-path-control.scan-delay-ms:500}",
        initialDelayString = "\${zeebe-play.gateway-path-control.initial-delay-ms:1000}"
    )
    fun autoContinueReadyGatewayPathControlJobs() {
        pruneExpiredSuppressions()

        jobRepository
            .findByStateInAndJobTypeIn(
                listOf(JobState.ACTIVATABLE),
                listOf(GatewayChoiceDeploymentEnhancer.GATEWAY_CHOICE_JOB_TYPE),
                PageRequest.of(0, MAX_JOBS_PER_SCAN)
            )
            .filter { pendingJobs.add(it.key) }
            .forEach { job ->
                try {
                    if (shouldAutoContinue(job)) {
                        completeGatewayPathControlJob(job)
                    }
                } catch (e: Exception) {
                    logger.debug("Failed to auto-continue gateway path control job '{}'.", job.key, e)
                } finally {
                    pendingJobs.remove(job.key)
                }
            }
    }

    fun suppressAutoContinue(processInstanceKey: Long, gatewayElementId: String) {
        suppressedAutoContinue[GatewayPathControlKey(processInstanceKey, gatewayElementId)] =
            System.currentTimeMillis() + SUPPRESSION_TTL_MILLIS
    }

    fun status(jobKey: Long): GatewayPathControlStatus {
        val job = jobRepository.findByIdOrNull(jobKey)
            ?: return GatewayPathControlStatus(inspected = false)

        return status(job)
    }

    fun status(job: Job): GatewayPathControlStatus {
        val gateway = getGateway(job) ?: return GatewayPathControlStatus(inspected = false)
        val requiredVariables = getRequiredVariables(gateway)
            ?: return GatewayPathControlStatus(inspected = false)
        val missingVariables = findMissingVariables(job, requiredVariables)

        return GatewayPathControlStatus(
            inspected = true,
            autoContinueSuppressed = isAutoContinueSuppressed(job, gateway.id),
            requiredVariables = requiredVariables.map { it.name }.sorted(),
            missingVariables = missingVariables.map { it.name }.sorted()
        )
    }

    fun shouldAutoContinue(job: Job): Boolean {
        val status = status(job)

        return status.inspected &&
            !status.autoContinueSuppressed &&
            status.missingVariables.isEmpty()
    }

    private fun completeGatewayPathControlJob(job: Job) {
        zeebeClient
            .newCompleteCommand(job.key)
            .variables("{}")
            .send()
            .join()

        logger.debug("Auto-continued gateway path control job '{}'.", job.key)
    }

    private fun getGateway(job: Job): Gateway? {
        val elementId = elementInstanceRepository.findByIdOrNull(job.elementInstanceKey)
            ?.elementId
            ?: return null

        val process = processRepository.findByIdOrNull(job.processDefinitionKey)
            ?: return null

        return Bpmn
            .readModelFromStream(process.bpmnXML.byteInputStream())
            .getModelElementById(elementId)
    }

    private fun getRequiredVariables(gateway: Gateway): Set<RequiredVariable>? {
        val requiredVariables = linkedSetOf<RequiredVariable>()

        gateway.outgoing
            .mapNotNull { getConditionExpression(it) }
            .forEach { conditionExpression ->
                val variables = getRequiredVariables(conditionExpression) ?: return null
                requiredVariables.addAll(variables)
            }

        return requiredVariables
    }

    private fun getConditionExpression(sequenceFlow: SequenceFlow): String? =
        sequenceFlow.conditionExpression
            ?.textContent
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun getRequiredVariables(conditionExpression: String): Set<RequiredVariable>? {
        val expression = normalizeConditionExpression(conditionExpression)
        val parsed = runCatching { FeelParser.parseExpression(expression) }.getOrNull()

        if (parsed?.isSuccess != true) {
            return null
        }

        val localAliases = getLocalAliases(expression)
        val references = CollectionConverters.asJava(
            ExpressionVariableExtractor.getVariableReferences(parsed.get().value())
        )

        return references
            .map { RequiredVariable(it.getFullQualifiedName()) }
            .filter { it.path.isNotEmpty() }
            .filterNot { localAliases.contains(it.path.first()) }
            .toSet()
    }

    private fun normalizeConditionExpression(conditionExpression: String): String {
        var expression = conditionExpression.trim()

        if (expression.startsWith("\${") && expression.endsWith("}")) {
            expression = expression
                .substring(2, expression.length - 1)
                .trim()
        }

        if (expression.startsWith("=")) {
            expression = expression.substring(1).trim()
        }

        return expression
    }

    private fun getLocalAliases(expression: String): Set<String> =
        LOCAL_ALIAS_REGEX
            .findAll(expression)
            .map { it.groupValues[1] }
            .toSet()

    private fun findMissingVariables(job: Job, requiredVariables: Set<RequiredVariable>): Set<RequiredVariable> {
        val visibleVariables = variableService
            .getVariables(
                job.elementInstanceKey,
                false,
                true
            )
            .associateBy { it.name }

        return requiredVariables
            .filterNot { requiredVariable ->
                val variable = visibleVariables[requiredVariable.path.first()] ?: return@filterNot false

                if (requiredVariable.path.size == 1) {
                    true
                } else {
                    hasJsonPath(variable.value, requiredVariable.path.drop(1))
                }
            }
            .toSet()
    }

    private fun hasJsonPath(rawValue: String, path: List<String>): Boolean {
        val root = runCatching { objectMapper.readTree(rawValue) }.getOrNull()
            ?: return false
        var current = root

        path.forEach { segment ->
            if (!current.has(segment)) {
                return false
            }

            current = current.get(segment)
        }

        return true
    }

    private fun isAutoContinueSuppressed(job: Job, gatewayElementId: String): Boolean {
        val key = GatewayPathControlKey(job.processInstanceKey, gatewayElementId)
        val expiresAt = suppressedAutoContinue[key] ?: return false

        if (expiresAt <= System.currentTimeMillis()) {
            suppressedAutoContinue.remove(key)
            return false
        }

        return true
    }

    private fun pruneExpiredSuppressions() {
        val now = System.currentTimeMillis()

        suppressedAutoContinue
            .filterValues { it <= now }
            .keys
            .forEach { suppressedAutoContinue.remove(it) }
    }

    data class GatewayPathControlStatus(
        val inspected: Boolean,
        val autoContinueSuppressed: Boolean = false,
        val requiredVariables: List<String> = emptyList(),
        val missingVariables: List<String> = emptyList()
    )

    private data class RequiredVariable(val path: List<String>) {
        val name = path.joinToString(".")
    }

    private data class GatewayPathControlKey(
        val processInstanceKey: Long,
        val gatewayElementId: String
    )

    companion object {
        private const val MAX_JOBS_PER_SCAN = 100
        private const val SUPPRESSION_TTL_MILLIS = 10 * 60 * 1000L

        private val LOCAL_ALIAS_REGEX =
            Regex("""\b(?:some|every|for)\s+([A-Za-z_][\w]*)\s+in\b""", RegexOption.IGNORE_CASE)
    }
}
