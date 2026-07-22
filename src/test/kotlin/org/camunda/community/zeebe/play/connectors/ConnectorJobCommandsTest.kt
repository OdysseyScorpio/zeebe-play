package org.camunda.community.zeebe.play.connectors

import io.camunda.client.CamundaClient
import io.camunda.client.api.CamundaFuture
import io.camunda.client.api.command.CompleteJobCommandStep1
import io.camunda.client.api.command.FailJobCommandStep1
import io.camunda.client.api.command.ThrowErrorCommandStep1
import io.camunda.client.api.response.CompleteJobResponse
import io.camunda.client.api.response.FailJobResponse
import io.camunda.client.api.response.ThrowErrorResponse
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Duration

class ConnectorJobCommandsTest {

    @Test
    fun `should send complete command with variables`() {
        val zeebeClient = Mockito.mock(CamundaClient::class.java)
        val command = Mockito.mock(CompleteJobCommandStep1::class.java)
        val future = completedFuture<CompleteJobResponse>()
        val variables = mapOf<String, Any>("result" to "ok")

        Mockito.`when`(zeebeClient.newCompleteCommand(10L)).thenReturn(command)
        Mockito.`when`(command.variables(variables)).thenReturn(command)
        Mockito.`when`(command.send()).thenReturn(future)

        ConnectorJobCommands.send(
            zeebeClient,
            10L,
            ConnectorExecutionResult.Complete(variables)
        )

        Mockito.verify(command).variables(variables)
        Mockito.verify(command).send()
        Mockito.verify(future).join()
    }

    @Test
    fun `should send BPMN error command with variables`() {
        val zeebeClient = Mockito.mock(CamundaClient::class.java)
        val step1 = Mockito.mock(ThrowErrorCommandStep1::class.java)
        val step2 = Mockito.mock(ThrowErrorCommandStep1.ThrowErrorCommandStep2::class.java)
        val future = completedFuture<ThrowErrorResponse>()
        val variables = mapOf<String, Any>("reason" to "invalid")

        Mockito.`when`(zeebeClient.newThrowErrorCommand(11L)).thenReturn(step1)
        Mockito.`when`(step1.errorCode("INVALID_ORDER")).thenReturn(step2)
        Mockito.`when`(step2.errorMessage("Invalid order")).thenReturn(step2)
        Mockito.`when`(step2.variables(variables)).thenReturn(step2)
        Mockito.`when`(step2.send()).thenReturn(future)

        ConnectorJobCommands.send(
            zeebeClient,
            11L,
            ConnectorExecutionResult.ThrowBpmnError(
                errorCode = "INVALID_ORDER",
                errorMessage = "Invalid order",
                variables = variables
            )
        )

        Mockito.verify(step1).errorCode("INVALID_ORDER")
        Mockito.verify(step2).errorMessage("Invalid order")
        Mockito.verify(step2).variables(variables)
        Mockito.verify(future).join()
    }

    @Test
    fun `should send empty message for BPMN error without message`() {
        val zeebeClient = Mockito.mock(CamundaClient::class.java)
        val step1 = Mockito.mock(ThrowErrorCommandStep1::class.java)
        val step2 = Mockito.mock(ThrowErrorCommandStep1.ThrowErrorCommandStep2::class.java)
        val future = completedFuture<ThrowErrorResponse>()

        Mockito.`when`(zeebeClient.newThrowErrorCommand(12L)).thenReturn(step1)
        Mockito.`when`(step1.errorCode("INVALID_ORDER")).thenReturn(step2)
        Mockito.`when`(step2.errorMessage("")).thenReturn(step2)
        Mockito.`when`(step2.send()).thenReturn(future)

        ConnectorJobCommands.send(
            zeebeClient,
            12L,
            ConnectorExecutionResult.ThrowBpmnError(
                errorCode = "INVALID_ORDER",
                errorMessage = null,
                variables = emptyMap()
            )
        )

        Mockito.verify(step2).errorMessage("")
        Mockito.verify(step2, Mockito.never()).variables(Mockito.anyMap<String, Any>())
        Mockito.verify(future).join()
    }

    @Test
    fun `should send fail command with retry backoff and variables`() {
        val zeebeClient = Mockito.mock(CamundaClient::class.java)
        val step1 = Mockito.mock(FailJobCommandStep1::class.java)
        val step2 = Mockito.mock(FailJobCommandStep1.FailJobCommandStep2::class.java)
        val future = completedFuture<FailJobResponse>()
        val retryBackoff = Duration.ofSeconds(5)
        val variables = mapOf<String, Any>("error" to mapOf("message" to "bad"))

        Mockito.`when`(zeebeClient.newFailCommand(13L)).thenReturn(step1)
        Mockito.`when`(step1.retries(2)).thenReturn(step2)
        Mockito.`when`(step2.errorMessage("bad")).thenReturn(step2)
        Mockito.`when`(step2.retryBackoff(retryBackoff)).thenReturn(step2)
        Mockito.`when`(step2.variables(variables)).thenReturn(step2)
        Mockito.`when`(step2.send()).thenReturn(future)

        ConnectorJobCommands.send(
            zeebeClient,
            13L,
            ConnectorExecutionResult.Fail(
                errorMessage = "bad",
                retries = 2,
                retryBackoff = retryBackoff,
                variables = variables
            )
        )

        Mockito.verify(step1).retries(2)
        Mockito.verify(step2).errorMessage("bad")
        Mockito.verify(step2).retryBackoff(retryBackoff)
        Mockito.verify(step2).variables(variables)
        Mockito.verify(future).join()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> completedFuture(): CamundaFuture<T> =
        Mockito.mock(CamundaFuture::class.java) as CamundaFuture<T>
}
