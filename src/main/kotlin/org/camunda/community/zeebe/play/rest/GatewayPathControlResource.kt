package org.camunda.community.zeebe.play.rest

import org.camunda.community.zeebe.play.services.GatewayPathControlService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/rest/gateway-path-control")
class GatewayPathControlResource(
    private val gatewayPathControlService: GatewayPathControlService
) {

    @GetMapping("/jobs/{jobKey}")
    fun status(@PathVariable("jobKey") jobKey: Long): GatewayPathControlService.GatewayPathControlStatus =
        gatewayPathControlService.status(jobKey)

    @PostMapping("/process-instances/{processInstanceKey}/gateways/{gatewayElementId}/suppress-auto-continue")
    fun suppressAutoContinue(
        @PathVariable("processInstanceKey") processInstanceKey: Long,
        @PathVariable("gatewayElementId") gatewayElementId: String
    ) {
        gatewayPathControlService.suppressAutoContinue(processInstanceKey, gatewayElementId)
    }
}
