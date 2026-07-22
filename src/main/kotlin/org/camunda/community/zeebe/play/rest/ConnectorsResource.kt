package org.camunda.community.zeebe.play.rest

import com.fasterxml.jackson.databind.ObjectMapper
import io.camunda.client.CamundaClient
import io.camunda.connector.runtime.core.config.OutboundConnectorConfiguration
import io.zeebe.zeeqs.data.entity.Job
import io.zeebe.zeeqs.data.entity.JobState
import io.zeebe.zeeqs.data.repository.ElementInstanceRepository
import io.zeebe.zeeqs.data.repository.JobRepository
import io.zeebe.zeeqs.data.repository.ProcessRepository
import io.zeebe.zeeqs.data.service.VariableService
import org.camunda.community.zeebe.play.connectors.ConnectorJobCommands
import org.camunda.community.zeebe.play.connectors.ConnectorService
import org.camunda.community.zeebe.play.connectors.StoredActivatedJob
import org.springframework.data.repository.findByIdOrNull
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RestController
@RequestMapping("/rest/connectors")
class ConnectorsResource(
    private val connectorService: ConnectorService,
    private val zeebeClient: CamundaClient,
    private val jobRepository: JobRepository,
    private val processRepository: ProcessRepository,
    private val elementInstanceRepository: ElementInstanceRepository,
    private val variableService: VariableService
) {

    companion object {
        private val objectMapper = ObjectMapper()
    }

    private val executor = Executors.newSingleThreadScheduledExecutor()

    private val keysOfPendingJobs = CopyOnWriteArrayList<Long>()

    @RequestMapping(method = [RequestMethod.GET])
    fun getAvailableConnectors(): ConnectorsDto {
        return ConnectorsDto(
            connectors = connectorService
                .findAvailableConnectors()
                .map { ConnectDto(name = it.name(), type = it.type()) }
        )
    }

    @RequestMapping(path = ["/{jobType}/execute/{jobKey}"], method = [RequestMethod.POST])
    fun executeJob(@PathVariable("jobType") jobType: String, @PathVariable("jobKey") jobKey: Long) {
        val connectorConfig = (connectorService.findAvailableConnectors()
            .find { it.type() == jobType }
            ?: throw RuntimeException("No connector found with job type '$jobType'."))

        jobRepository.findByIdOrNull(jobKey)
            ?.takeIf { it.state == JobState.ACTIVATABLE && !keysOfPendingJobs.contains(jobKey) }
            ?.let { job ->
                StoredActivatedJob(
                    job = job,
                    process = processRepository.findByIdOrNull(job.processDefinitionKey),
                    elementInstance = elementInstanceRepository.findByIdOrNull(job.elementInstanceKey),
                    variables = getJobVariables(job, connectorConfig),
                    objectMapper = objectMapper
                )
            }
            ?.let {
                // block the invocation of this job for the next 10 seconds
                keysOfPendingJobs.add(jobKey)

                val result = connectorService.executeConnectorJob(connectorConfig, it)
                ConnectorJobCommands.send(zeebeClient, it.key, result)

                executor.schedule({
                    keysOfPendingJobs.remove(jobKey)
                }, 10, TimeUnit.SECONDS)
            }
            ?: throw RuntimeException("No job found with key '$jobKey'.")
    }

    private fun getJobVariables(
        job: Job,
        connectorConfig: OutboundConnectorConfiguration
    ): String {
        val allVariables = variableService.getVariables(
            elementInstanceKey = job.elementInstanceKey,
            localOnly = false,
            shadowing = true
        )
        val filteredVariables =
            allVariables.filter { connectorConfig.inputVariables().contains(it.name) }

        return filteredVariables.joinToString(
            separator = ",",
            prefix = "{",
            postfix = "}"
        ) { "\"${it.name}\": ${it.value}" }
    }

    data class ConnectorsDto(
        val connectors: List<ConnectDto>
    )

    data class ConnectDto(
        val name: String,
        val type: String
    )
}
