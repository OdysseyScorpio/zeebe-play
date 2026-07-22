package org.camunda.community.zeebe.play.connectors

import com.fasterxml.jackson.databind.ObjectMapper
import io.camunda.client.api.search.enums.JobKind
import io.camunda.client.api.search.enums.ListenerEventType
import io.zeebe.zeeqs.data.entity.BpmnElementType
import io.zeebe.zeeqs.data.entity.ElementInstance
import io.zeebe.zeeqs.data.entity.Job
import io.zeebe.zeeqs.data.entity.Process
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class CamundaActivatedJobAdaptersTest {

    private val objectMapper = ObjectMapper()

    @Test
    fun `stored job should expose ZeeQS job state as Camunda activated job`() {
        val storedJob = job().apply {
            retries = 3
            worker = "connector-worker"
        }
        val activatedJob = StoredActivatedJob(
            job = storedJob,
            process = process(),
            elementInstance = elementInstance(),
            variables = """{"approved":true,"customer":{"id":42}}""",
            objectMapper = objectMapper
        )

        assertThat(activatedJob.key).isEqualTo(100L)
        assertThat(activatedJob.type).isEqualTo("io.camunda:http-json:1")
        assertThat(activatedJob.processInstanceKey).isEqualTo(300L)
        assertThat(activatedJob.bpmnProcessId).isEqualTo("order-process")
        assertThat(activatedJob.processDefinitionVersion).isEqualTo(7)
        assertThat(activatedJob.processDefinitionKey).isEqualTo(500L)
        assertThat(activatedJob.elementId).isEqualTo("connector_task")
        assertThat(activatedJob.elementInstanceKey).isEqualTo(400L)
        assertThat(activatedJob.worker).isEqualTo("connector-worker")
        assertThat(activatedJob.retries).isEqualTo(3)
        assertThat(activatedJob.deadline).isEqualTo(-1)
        assertThat(activatedJob.tenantId).isEqualTo("<default>")
        assertThat(activatedJob.kind).isEqualTo(JobKind.BPMN_ELEMENT)
        assertThat(activatedJob.listenerEventType).isEqualTo(ListenerEventType.UNSPECIFIED)
        assertThat(activatedJob.rootProcessInstanceKey).isEqualTo(300L)
        assertThat(activatedJob.userTask).isNull()
        assertThat(activatedJob.getDocumentReferences("invoice")).isEmpty()
        assertThat(activatedJob.tags).isEmpty()
    }

    @Test
    fun `stored job should read variables and BPMN custom headers`() {
        val activatedJob = StoredActivatedJob(
            job = job(),
            process = process(),
            elementInstance = elementInstance(),
            variables = """{"approved":true,"amount":42}""",
            objectMapper = objectMapper
        )

        assertThat(activatedJob.variables).isEqualTo("""{"approved":true,"amount":42}""")
        assertThat(activatedJob.variablesAsMap).containsEntry("approved", true)
        assertThat(activatedJob.getVariable("amount")).isEqualTo(42)
        assertThat(activatedJob.customHeaders)
            .containsEntry("resultVariable", "response")
            .containsEntry("retryBackoff", "PT5S")

        val asJson = objectMapper.readValue(activatedJob.toJson(), Map::class.java)
        assertThat(asJson["elementId"]).isEqualTo("connector_task")
        assertThat(asJson["customHeaders"]).isEqualTo(
            mapOf("resultVariable" to "response", "retryBackoff" to "PT5S")
        )
    }

    @Test
    fun `stored job should use fallback values when process metadata is missing`() {
        val activatedJob = StoredActivatedJob(
            job = job(),
            process = null,
            elementInstance = null,
            variables = "{}",
            objectMapper = objectMapper
        )

        assertThat(activatedJob.bpmnProcessId).isEqualTo("?")
        assertThat(activatedJob.processDefinitionVersion).isEqualTo(-1)
        assertThat(activatedJob.elementId).isEqualTo("?")
        assertThat(activatedJob.customHeaders).isEmpty()
        assertThat(activatedJob.worker).isEmpty()
        assertThat(activatedJob.retries).isEqualTo(-1)
    }

    private fun job(): Job =
        Job(
            100L,
            200L,
            "io.camunda:http-json:1",
            300L,
            400L,
            500L
        )

    private fun elementInstance(): ElementInstance =
        ElementInstance(
            400L,
            201L,
            "connector_task",
            BpmnElementType.SERVICE_TASK,
            300L,
            500L,
            null
        )

    private fun process(): Process =
        Process(
            500L,
            "order-process",
            7,
            connectorProcessXml(),
            Instant.now().toEpochMilli(),
            "order-process.bpmn",
            "checksum"
        )

    private fun connectorProcessXml(): String =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                          xmlns:zeebe="http://camunda.org/schema/zeebe/1.0"
                          id="Definitions_1"
                          targetNamespace="http://camunda.org/examples">
          <bpmn:process id="order-process" isExecutable="true">
            <bpmn:startEvent id="start">
              <bpmn:outgoing>flow_to_task</bpmn:outgoing>
            </bpmn:startEvent>
            <bpmn:sequenceFlow id="flow_to_task" sourceRef="start" targetRef="connector_task"/>
            <bpmn:serviceTask id="connector_task">
              <bpmn:extensionElements>
                <zeebe:taskDefinition type="io.camunda:http-json:1"/>
                <zeebe:taskHeaders>
                  <zeebe:header key="resultVariable" value="response"/>
                  <zeebe:header key="retryBackoff" value="PT5S"/>
                </zeebe:taskHeaders>
              </bpmn:extensionElements>
              <bpmn:incoming>flow_to_task</bpmn:incoming>
              <bpmn:outgoing>flow_to_end</bpmn:outgoing>
            </bpmn:serviceTask>
            <bpmn:sequenceFlow id="flow_to_end" sourceRef="connector_task" targetRef="end"/>
            <bpmn:endEvent id="end">
              <bpmn:incoming>flow_to_end</bpmn:incoming>
            </bpmn:endEvent>
          </bpmn:process>
        </bpmn:definitions>
        """.trimIndent().trim()
}
