package org.camunda.community.zeebe.play.rest

import io.camunda.client.CamundaClient
import io.zeebe.zeeqs.data.repository.ProcessRepository
import org.camunda.community.zeebe.play.services.GatewayChoiceDeploymentEnhancer
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/rest/demo")
class DemoResource(
    private val zeebeClient: CamundaClient,
    private val processRepository: ProcessRepository
) {

    @RequestMapping(path = ["/"], method = [RequestMethod.POST])
    fun deployDemoResources(): Long {

        val deployCommand = zeebeClient
            .newDeployResourceCommand()
            .addResourceBytes(
                GatewayChoiceDeploymentEnhancer.enhance(
                    readClasspathResource("demo/solos-transport-process.bpmn"),
                    "solos-transport-process.bpmn"
                ),
                "solos-transport-process.bpmn"
            )
            .addResourceFromClasspath("demo/is_legal_good.dmn")

        return deployCommand
            .send()
            .join()
            .processes
            .first()
            .processDefinitionKey;
    }

    private fun readClasspathResource(resourceName: String): ByteArray =
        requireNotNull(javaClass.classLoader.getResourceAsStream(resourceName)) {
            "Resource '$resourceName' not found"
        }.use { it.readBytes() }

    @RequestMapping(path = ["/"], method = [RequestMethod.GET])
    fun getDemoProcessKey(): Long? {
        return processRepository.findAll()
            .filter { it.bpmnProcessId == "solos-transport-process" }
            .map { it.key }
            .firstOrNull()
    }

}
