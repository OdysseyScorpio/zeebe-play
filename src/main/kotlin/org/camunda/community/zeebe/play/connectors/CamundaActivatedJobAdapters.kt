package org.camunda.community.zeebe.play.connectors

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.camunda.client.api.response.ActivatedJob
import io.camunda.client.api.response.DocumentReferenceResponse
import io.camunda.client.api.response.UserTaskProperties
import io.camunda.client.api.search.enums.JobKind
import io.camunda.client.api.search.enums.ListenerEventType
import io.camunda.zeebe.model.bpmn.Bpmn
import io.camunda.zeebe.model.bpmn.instance.FlowElement
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskHeaders
import io.zeebe.zeeqs.data.entity.ElementInstance
import io.zeebe.zeeqs.data.entity.Job
import io.zeebe.zeeqs.data.entity.Process

class StoredActivatedJob(
    private val job: Job,
    private val process: Process?,
    private val elementInstance: ElementInstance?,
    private val variables: String,
    private val objectMapper: ObjectMapper = ObjectMapper()
) : ActivatedJob {

    override fun getKey(): Long = job.key

    override fun getType(): String = job.jobType

    override fun getProcessInstanceKey(): Long = job.processInstanceKey

    override fun getBpmnProcessId(): String = process?.bpmnProcessId ?: "?"

    override fun getProcessDefinitionVersion(): Int = process?.version ?: -1

    override fun getProcessDefinitionKey(): Long = job.processDefinitionKey

    override fun getElementId(): String = elementInstance?.elementId ?: "?"

    override fun getElementInstanceKey(): Long = job.elementInstanceKey

    override fun getCustomHeaders(): Map<String, String> {
        val bpmnXml = process?.bpmnXML ?: return emptyMap()
        val model = Bpmn.readModelFromStream(bpmnXml.byteInputStream())
        val element = model.getModelElementById<FlowElement>(elementId) ?: return emptyMap()

        return element
            .getSingleExtensionElement(ZeebeTaskHeaders::class.java)
            ?.headers
            ?.associate { it.key to it.value }
            ?: emptyMap()
    }

    override fun getWorker(): String = job.worker ?: ""

    override fun getRetries(): Int = job.retries ?: -1

    override fun getDeadline(): Long = -1

    override fun getVariables(): String = variables

    override fun getVariablesAsMap(): Map<String, Any> {
        val typeRef = object : TypeReference<Map<String, Any>>() {}
        return objectMapper.readValue(variables, typeRef)
    }

    override fun <T> getVariablesAsType(variableType: Class<T>): T =
        objectMapper.readValue(variables, variableType)

    override fun getVariable(name: String): Any? = variablesAsMap[name]

    override fun getUserTask(): UserTaskProperties? = null

    override fun getKind(): JobKind = JobKind.BPMN_ELEMENT

    override fun getListenerEventType(): ListenerEventType = ListenerEventType.UNSPECIFIED

    override fun toJson(): String =
        objectMapper.writeValueAsString(
            mapOf(
                "key" to key,
                "type" to type,
                "processInstanceKey" to processInstanceKey,
                "bpmnProcessId" to bpmnProcessId,
                "processDefinitionVersion" to processDefinitionVersion,
                "processDefinitionKey" to processDefinitionKey,
                "elementId" to elementId,
                "elementInstanceKey" to elementInstanceKey,
                "customHeaders" to customHeaders,
                "worker" to worker,
                "retries" to retries,
                "deadline" to deadline,
                "variables" to variablesAsMap,
                "tenantId" to tenantId
            )
        )

    override fun getTenantId(): String = "<default>"

    override fun getDocumentReferences(name: String): List<DocumentReferenceResponse> = emptyList()

    override fun getTags(): Set<String> = emptySet()

    override fun getRootProcessInstanceKey(): Long = processInstanceKey
}
