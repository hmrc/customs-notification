package integration

import integration.connectors.{ApiSubscriptionFieldsConnectorSpec, SendNotificationConnectorSpec}
import org.scalatest.Suites

class AllIntegrationSpecs extends Suites(
  new ApiSubscriptionFieldsConnectorSpec,
  new SendNotificationConnectorSpec) with IntegrationBaseSpec
