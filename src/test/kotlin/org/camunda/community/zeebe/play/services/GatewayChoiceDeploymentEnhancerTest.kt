package org.camunda.community.zeebe.play.services

import io.camunda.zeebe.model.bpmn.Bpmn
import io.camunda.zeebe.model.bpmn.BpmnModelInstance
import io.camunda.zeebe.model.bpmn.instance.Gateway
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListener
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListenerEventType
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListeners
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class GatewayChoiceDeploymentEnhancerTest {

    @Test
    fun `should leave non-BPMN resources unchanged`() {
        val resource = "not a BPMN file".toByteArray()

        val enhanced = GatewayChoiceDeploymentEnhancer.enhance(resource, "notes.txt")

        assertThat(enhanced).isSameAs(resource)
    }

    @Test
    fun `should leave gateway without default flow unchanged`() {
        val resource = gatewayProcess(defaultFlow = null).toByteArray()

        val enhanced = GatewayChoiceDeploymentEnhancer.enhance(resource, "process.bpmn")

        assertThat(enhanced).isSameAs(resource)
    }

    @Test
    fun `should leave gateway without conditional route unchanged`() {
        val resource = gatewayProcess(
            defaultFlow = "default_flow",
            conditionExpression = null
        ).toByteArray()

        val enhanced = GatewayChoiceDeploymentEnhancer.enhance(resource, "process.bpmn")

        assertThat(enhanced).isSameAs(resource)
    }

    @Test
    fun `should add start execution listener to exclusive gateway with default and conditional routes`() {
        val model = enhanceModel(gatewayProcess())

        val listeners = gatewayChoiceListeners(model)

        assertThat(listeners).hasSize(1)
        assertThat(listeners.single().retries).isEqualTo("1")
    }

    @Test
    fun `should add start execution listener to inclusive gateway bpmn20 resource`() {
        val model = enhanceModel(
            gatewayProcess(gatewayType = "inclusiveGateway"),
            "process.bpmn20.xml"
        )

        val listeners = gatewayChoiceListeners(model)

        assertThat(listeners).hasSize(1)
    }

    @Test
    fun `should not duplicate gateway choice listeners`() {
        val firstEnhancement = GatewayChoiceDeploymentEnhancer.enhance(
            gatewayProcess().toByteArray(),
            "process.bpmn"
        )

        val model = Bpmn.readModelFromStream(
            ByteArrayInputStream(
                GatewayChoiceDeploymentEnhancer.enhance(firstEnhancement, "process.bpmn")
            )
        )

        val listeners = gatewayChoiceListeners(model)

        assertThat(listeners).hasSize(1)
    }

    private fun enhanceModel(xml: String, resourceName: String = "process.bpmn"): BpmnModelInstance =
        Bpmn.readModelFromStream(
            ByteArrayInputStream(
                GatewayChoiceDeploymentEnhancer.enhance(xml.toByteArray(), resourceName)
            )
        )

    private fun gatewayChoiceListeners(
        model: BpmnModelInstance,
        gatewayId: String = "gateway"
    ): List<ZeebeExecutionListener> =
        model
            .getModelElementById<Gateway>(gatewayId)
            .getSingleExtensionElement(ZeebeExecutionListeners::class.java)
            ?.executionListeners
            ?.filter {
                it.eventType == ZeebeExecutionListenerEventType.start &&
                    it.type == GatewayChoiceDeploymentEnhancer.GATEWAY_CHOICE_JOB_TYPE
            }
            ?: emptyList()

    private fun gatewayProcess(
        gatewayType: String = "exclusiveGateway",
        defaultFlow: String? = "default_flow",
        conditionExpression: String? = "= approved = true"
    ): String {
        val defaultAttribute = defaultFlow?.let { """ default="$it"""" } ?: ""
        val conditionalFlow = if (conditionExpression == null) {
            """<bpmn:sequenceFlow id="condition_flow" sourceRef="gateway" targetRef="approved_task"/>"""
        } else {
            """
            <bpmn:sequenceFlow id="condition_flow" name="Approved" sourceRef="gateway" targetRef="approved_task">
              <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">$conditionExpression</bpmn:conditionExpression>
            </bpmn:sequenceFlow>
            """.trimIndent()
        }

        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:zeebe="http://camunda.org/schema/zeebe/1.0"
                              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                              id="Definitions_1"
                              targetNamespace="http://camunda.org/examples">
              <bpmn:process id="process" isExecutable="true">
                <bpmn:startEvent id="start">
                  <bpmn:outgoing>flow_to_gateway</bpmn:outgoing>
                </bpmn:startEvent>
                <bpmn:$gatewayType id="gateway"$defaultAttribute>
                  <bpmn:incoming>flow_to_gateway</bpmn:incoming>
                  <bpmn:outgoing>condition_flow</bpmn:outgoing>
                  <bpmn:outgoing>default_flow</bpmn:outgoing>
                </bpmn:$gatewayType>
                <bpmn:sequenceFlow id="flow_to_gateway" sourceRef="start" targetRef="gateway"/>
                $conditionalFlow
                <bpmn:sequenceFlow id="default_flow" sourceRef="gateway" targetRef="fallback_task"/>
                <bpmn:serviceTask id="approved_task">
                  <bpmn:incoming>condition_flow</bpmn:incoming>
                  <bpmn:outgoing>approved_end_flow</bpmn:outgoing>
                </bpmn:serviceTask>
                <bpmn:serviceTask id="fallback_task">
                  <bpmn:incoming>default_flow</bpmn:incoming>
                  <bpmn:outgoing>fallback_end_flow</bpmn:outgoing>
                </bpmn:serviceTask>
                <bpmn:sequenceFlow id="approved_end_flow" sourceRef="approved_task" targetRef="end"/>
                <bpmn:sequenceFlow id="fallback_end_flow" sourceRef="fallback_task" targetRef="end"/>
                <bpmn:endEvent id="end">
                  <bpmn:incoming>approved_end_flow</bpmn:incoming>
                  <bpmn:incoming>fallback_end_flow</bpmn:incoming>
                </bpmn:endEvent>
              </bpmn:process>
            </bpmn:definitions>
        """.trimIndent().trim()
    }
}
