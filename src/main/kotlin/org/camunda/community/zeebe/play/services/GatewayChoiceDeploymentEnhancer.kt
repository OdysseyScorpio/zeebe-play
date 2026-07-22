package org.camunda.community.zeebe.play.services

import io.camunda.zeebe.model.bpmn.Bpmn
import io.camunda.zeebe.model.bpmn.instance.ExclusiveGateway
import io.camunda.zeebe.model.bpmn.instance.Gateway
import io.camunda.zeebe.model.bpmn.instance.InclusiveGateway
import io.camunda.zeebe.model.bpmn.instance.SequenceFlow
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListenerEventType
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListeners
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

object GatewayChoiceDeploymentEnhancer {

    const val GATEWAY_CHOICE_JOB_TYPE = "zeebe-play:gateway-choice"

    fun enhance(resourceBytes: ByteArray, resourceName: String?): ByteArray {
        if (!isBpmnResource(resourceName)) {
            return resourceBytes
        }

        val model = Bpmn.readModelFromStream(ByteArrayInputStream(resourceBytes))
        val enhanced = enhanceGatewayChoices(model.getModelElementsByType(ExclusiveGateway::class.java)) or
            enhanceGatewayChoices(model.getModelElementsByType(InclusiveGateway::class.java))

        if (!enhanced) {
            return resourceBytes
        }

        return ByteArrayOutputStream().use { output ->
            Bpmn.writeModelToStream(output, model)
            output.toByteArray()
        }
    }

    private fun isBpmnResource(resourceName: String?): Boolean {
        val lowerCaseResourceName = resourceName?.lowercase() ?: return false
        return lowerCaseResourceName.endsWith(".bpmn") ||
            lowerCaseResourceName.endsWith(".bpmn20.xml")
    }

    private fun enhanceGatewayChoices(gateways: Collection<Gateway>): Boolean {
        var enhanced = false

        gateways
            .filter { shouldEnhance(it) }
            .forEach {
                it.builder().zeebeStartExecutionListener(GATEWAY_CHOICE_JOB_TYPE, "1")
                enhanced = true
            }

        return enhanced
    }

    private fun shouldEnhance(gateway: Gateway): Boolean {
        return !hasGatewayChoiceListener(gateway) &&
            gateway.outgoing.size > 1 &&
            gateway.outgoing.any { hasConditionExpression(it) }
    }

    private fun hasConditionExpression(sequenceFlow: SequenceFlow): Boolean =
        sequenceFlow.conditionExpression
            ?.textContent
            ?.trim()
            ?.isNotEmpty()
            ?: false

    private fun hasGatewayChoiceListener(gateway: Gateway): Boolean =
        gateway
            .getSingleExtensionElement(ZeebeExecutionListeners::class.java)
            ?.executionListeners
            ?.any {
                it.eventType == ZeebeExecutionListenerEventType.start &&
                    it.type == GATEWAY_CHOICE_JOB_TYPE
            }
            ?: false
}
