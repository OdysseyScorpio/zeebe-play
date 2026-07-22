package org.camunda.community.zeebe.play.rest

import io.camunda.client.CamundaClient
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/rest/incidents")
class IncidentsResource(private val zeebeClient: CamundaClient) {

    @RequestMapping(path = ["/{incidentKey}/resolve"], method = [RequestMethod.POST])
    fun resolve(@PathVariable("incidentKey") incidentKey: Long) {

        zeebeClient.newResolveIncidentCommand(incidentKey)
            .send()
            .join()
    }

}
