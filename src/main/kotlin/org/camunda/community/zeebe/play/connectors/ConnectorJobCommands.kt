package org.camunda.community.zeebe.play.connectors

import io.camunda.client.CamundaClient

object ConnectorJobCommands {

    fun send(
        zeebeClient: CamundaClient,
        jobKey: Long,
        result: ConnectorExecutionResult
    ) {
        when (result) {
            is ConnectorExecutionResult.Complete -> {
                zeebeClient.newCompleteCommand(jobKey)
                    .variables(result.variables)
                    .send()
                    .join()
            }
            is ConnectorExecutionResult.ThrowBpmnError -> {
                val command = zeebeClient.newThrowErrorCommand(jobKey)
                    .errorCode(result.errorCode)
                    .errorMessage(result.errorMessage ?: "")

                if (result.variables.isNotEmpty()) {
                    command.variables(result.variables)
                }

                command.send().join()
            }
            is ConnectorExecutionResult.Fail -> {
                val command = zeebeClient.newFailCommand(jobKey)
                    .retries(result.retries)
                    .errorMessage(result.errorMessage)

                result.retryBackoff?.let { command.retryBackoff(it) }

                if (result.variables.isNotEmpty()) {
                    command.variables(result.variables)
                }

                command.send().join()
            }
        }
    }
}
