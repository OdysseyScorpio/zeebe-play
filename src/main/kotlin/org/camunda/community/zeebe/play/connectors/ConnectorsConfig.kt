package org.camunda.community.zeebe.play.connectors

import io.camunda.client.CamundaClient
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import javax.annotation.PostConstruct

@Configuration
@EnableJpaRepositories
@EntityScan
@EnableConfigurationProperties(ConnectorProperties::class)
class ConnectorsConfig(
    private val zeebeClient: CamundaClient,
    private val connectorProperties: ConnectorProperties,
    private val connectorSecretRepository: ConnectorSecretRepository,
    private val connectorService: ConnectorService
) {

    private val logger = LoggerFactory.getLogger(ConnectorsConfig::class.java)

    @PostConstruct
    fun startConnectors() {
        if (connectorProperties.mode == ConnectorProperties.ConnectorsMode.ACTIVE) {
            // start all connectors
            connectorService
                .findAvailableConnectors()
                .forEach { connectorConfig ->
                    zeebeClient
                        .newWorker()
                        .jobType(connectorConfig.type())
                        .handler { _, job ->
                            val result = connectorService.executeConnectorJob(
                                connectorConfig,
                                job
                            )
                            ConnectorJobCommands.send(zeebeClient, job.key, result)
                        }
                        .name(connectorConfig.name())
                        .fetchVariables(connectorConfig.inputVariables().toList())
                        .open()

                    logger.info("Start Zeebe connector. [name: '${connectorConfig.name()}', type: '${connectorConfig.type()}']")
                }
        }
    }

    @PostConstruct
    fun storeConnectorSecrets() {
        connectorProperties.secrets.forEach {
            val secret = ConnectorSecret(
                name = it.name,
                value = it.value
            )
            connectorSecretRepository.save(secret)
        }
    }

}
