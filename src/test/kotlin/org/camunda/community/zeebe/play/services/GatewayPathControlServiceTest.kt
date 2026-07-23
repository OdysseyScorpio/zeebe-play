package org.camunda.community.zeebe.play.services

import io.camunda.client.CamundaClient
import io.camunda.client.api.CamundaFuture
import io.camunda.client.api.command.CompleteJobCommandStep1
import io.camunda.client.api.response.CompleteJobResponse
import io.zeebe.zeeqs.data.entity.BpmnElementType
import io.zeebe.zeeqs.data.entity.ElementInstance
import io.zeebe.zeeqs.data.entity.Job
import io.zeebe.zeeqs.data.entity.JobState
import io.zeebe.zeeqs.data.entity.Process
import io.zeebe.zeeqs.data.entity.Variable
import io.zeebe.zeeqs.data.repository.ElementInstanceRepository
import io.zeebe.zeeqs.data.repository.JobRepository
import io.zeebe.zeeqs.data.repository.ProcessRepository
import io.zeebe.zeeqs.data.service.VariableService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.data.domain.PageRequest
import java.util.Optional

class GatewayPathControlServiceTest {

    private val zeebeClient = Mockito.mock(CamundaClient::class.java)
    private val jobRepository = Mockito.mock(JobRepository::class.java)
    private val processRepository = Mockito.mock(ProcessRepository::class.java)
    private val elementInstanceRepository = Mockito.mock(ElementInstanceRepository::class.java)
    private val variableService = Mockito.mock(VariableService::class.java)

    private val service = GatewayPathControlService(
        zeebeClient = zeebeClient,
        jobRepository = jobRepository,
        processRepository = processRepository,
        elementInstanceRepository = elementInstanceRepository,
        variableService = variableService
    )

    @BeforeEach
    fun setUp() {
        Mockito.reset(
            zeebeClient,
            jobRepository,
            processRepository,
            elementInstanceRepository,
            variableService
        )
    }

    @Test
    fun `should auto continue gateway path control when required variable is visible`() {
        val job = gatewayJob()
        givenGateway(conditionExpression = "= approved = true")
        givenVariables(variable("approved", "true"))

        val status = service.status(job)

        assertThat(status.inspected).isTrue()
        assertThat(status.requiredVariables).containsExactly("approved")
        assertThat(status.missingVariables).isEmpty()
        assertThat(service.shouldAutoContinue(job)).isTrue()
    }

    @Test
    fun `should pause gateway path control when required variable is missing`() {
        val job = gatewayJob()
        givenGateway(conditionExpression = "= approved = true")
        givenVariables()

        val status = service.status(job)

        assertThat(status.inspected).isTrue()
        assertThat(status.requiredVariables).containsExactly("approved")
        assertThat(status.missingVariables).containsExactly("approved")
        assertThat(service.shouldAutoContinue(job)).isFalse()
    }

    @Test
    fun `should require nested variable paths`() {
        val job = gatewayJob()
        givenGateway(conditionExpression = "= customer.age >= 18")
        givenVariables(variable("customer", """{"name":"Ari"}"""))

        val status = service.status(job)

        assertThat(status.requiredVariables).containsExactly("customer.age")
        assertThat(status.missingVariables).containsExactly("customer.age")
        assertThat(service.shouldAutoContinue(job)).isFalse()
    }

    @Test
    fun `should accept existing nested variable paths`() {
        val job = gatewayJob()
        givenGateway(conditionExpression = "= customer.age >= 18")
        givenVariables(variable("customer", """{"age":18}"""))

        val status = service.status(job)

        assertThat(status.requiredVariables).containsExactly("customer.age")
        assertThat(status.missingVariables).isEmpty()
        assertThat(service.shouldAutoContinue(job)).isTrue()
    }

    @Test
    fun `should ignore FEEL iterator aliases`() {
        val job = gatewayJob()
        givenGateway(
            conditionExpression = "= some item in cargo_items satisfies not(item.is_legal)"
        )
        givenVariables(variable("cargo_items", """[{"is_legal":false}]"""))

        val status = service.status(job)

        assertThat(status.requiredVariables).containsExactly("cargo_items")
        assertThat(status.missingVariables).isEmpty()
        assertThat(service.shouldAutoContinue(job)).isTrue()
    }

    @Test
    fun `should not auto continue suppressed rewound gateway`() {
        val job = gatewayJob()
        givenGateway(conditionExpression = "= approved = true")
        givenVariables(variable("approved", "true"))

        service.suppressAutoContinue(PROCESS_INSTANCE_KEY, GATEWAY_ELEMENT_ID)

        val status = service.status(job)

        assertThat(status.autoContinueSuppressed).isTrue()
        assertThat(status.missingVariables).isEmpty()
        assertThat(service.shouldAutoContinue(job)).isFalse()
    }

    @Test
    fun `should complete activatable gateway path control job when required variables are visible`() {
        val job = gatewayJob()
        givenGateway(conditionExpression = "= approved = true")
        givenVariables(variable("approved", "true"))
        givenActivatableGatewayJobs(job)
        val command = givenCompleteCommand()

        service.autoContinueReadyGatewayPathControlJobs()

        Mockito.verify(zeebeClient).newCompleteCommand(JOB_KEY)
        Mockito.verify(command).variables("{}")
        Mockito.verify(command).send()
    }

    @Test
    fun `should leave activatable gateway path control job paused when required variables are missing`() {
        val job = gatewayJob()
        givenGateway(conditionExpression = "= approved = true")
        givenVariables()
        givenActivatableGatewayJobs(job)

        service.autoContinueReadyGatewayPathControlJobs()

        Mockito.verify(zeebeClient, Mockito.never()).newCompleteCommand(Mockito.anyLong())
    }

    private fun givenGateway(conditionExpression: String) {
        Mockito.`when`(elementInstanceRepository.findById(ELEMENT_INSTANCE_KEY))
            .thenReturn(
                Optional.of(
                    ElementInstance(
                        ELEMENT_INSTANCE_KEY,
                        1L,
                        GATEWAY_ELEMENT_ID,
                        BpmnElementType.EXCLUSIVE_GATEWAY,
                        PROCESS_INSTANCE_KEY,
                        PROCESS_DEFINITION_KEY,
                        null
                    )
                )
            )
        Mockito.`when`(processRepository.findById(PROCESS_DEFINITION_KEY))
            .thenReturn(
                Optional.of(
                    Process(
                        PROCESS_DEFINITION_KEY,
                        "process",
                        1,
                        gatewayProcess(conditionExpression),
                        1L,
                        "process.bpmn",
                        "checksum"
                    )
                )
            )
    }

    private fun givenVariables(vararg variables: Variable) {
        Mockito.`when`(
            variableService.getVariables(
                ELEMENT_INSTANCE_KEY,
                false,
                true
            )
        ).thenReturn(variables.toList())
    }

    private fun givenActivatableGatewayJobs(vararg jobs: Job) {
        Mockito.`when`(
            jobRepository.findByStateInAndJobTypeIn(
                listOf(JobState.ACTIVATABLE),
                listOf(GatewayChoiceDeploymentEnhancer.GATEWAY_CHOICE_JOB_TYPE),
                PageRequest.of(0, 100)
            )
        ).thenReturn(jobs.toList())
    }

    @Suppress("UNCHECKED_CAST")
    private fun givenCompleteCommand(): CompleteJobCommandStep1 {
        val command = Mockito.mock(CompleteJobCommandStep1::class.java)
        val future = Mockito.mock(CamundaFuture::class.java) as CamundaFuture<CompleteJobResponse>

        Mockito.`when`(zeebeClient.newCompleteCommand(JOB_KEY)).thenReturn(command)
        Mockito.`when`(command.variables("{}")).thenReturn(command)
        Mockito.`when`(command.send()).thenReturn(future)
        Mockito.`when`(future.join()).thenReturn(Mockito.mock(CompleteJobResponse::class.java))

        return command
    }

    private fun gatewayJob() =
        Job(
            JOB_KEY,
            1L,
            GatewayChoiceDeploymentEnhancer.GATEWAY_CHOICE_JOB_TYPE,
            PROCESS_INSTANCE_KEY,
            ELEMENT_INSTANCE_KEY,
            PROCESS_DEFINITION_KEY
        ).also { it.state = JobState.ACTIVATABLE }

    private fun variable(name: String, value: String) =
        Variable(
            name.hashCode().toLong(),
            1L,
            name,
            PROCESS_INSTANCE_KEY,
            PROCESS_DEFINITION_KEY,
            PROCESS_INSTANCE_KEY,
            value,
            1L
        )

    private fun gatewayProcess(conditionExpression: String): String =
        """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                              id="Definitions_1"
                              targetNamespace="http://camunda.org/examples">
              <bpmn:process id="process" isExecutable="true">
                <bpmn:startEvent id="start">
                  <bpmn:outgoing>flow_to_gateway</bpmn:outgoing>
                </bpmn:startEvent>
                <bpmn:exclusiveGateway id="$GATEWAY_ELEMENT_ID" default="default_flow">
                  <bpmn:incoming>flow_to_gateway</bpmn:incoming>
                  <bpmn:outgoing>condition_flow</bpmn:outgoing>
                  <bpmn:outgoing>default_flow</bpmn:outgoing>
                </bpmn:exclusiveGateway>
                <bpmn:sequenceFlow id="flow_to_gateway" sourceRef="start" targetRef="$GATEWAY_ELEMENT_ID"/>
                <bpmn:sequenceFlow id="condition_flow" name="Condition" sourceRef="$GATEWAY_ELEMENT_ID" targetRef="condition_task">
                  <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">$conditionExpression</bpmn:conditionExpression>
                </bpmn:sequenceFlow>
                <bpmn:sequenceFlow id="default_flow" sourceRef="$GATEWAY_ELEMENT_ID" targetRef="fallback_task"/>
                <bpmn:serviceTask id="condition_task"/>
                <bpmn:serviceTask id="fallback_task"/>
              </bpmn:process>
            </bpmn:definitions>
        """.trimIndent().trim()

    companion object {
        private const val JOB_KEY = 10L
        private const val PROCESS_INSTANCE_KEY = 100L
        private const val ELEMENT_INSTANCE_KEY = 200L
        private const val PROCESS_DEFINITION_KEY = 300L
        private const val GATEWAY_ELEMENT_ID = "gateway"
    }
}
