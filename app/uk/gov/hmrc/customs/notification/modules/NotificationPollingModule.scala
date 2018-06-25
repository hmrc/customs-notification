package uk.gov.hmrc.customs.notification.modules

import play.api.inject.guice.GuiceableModule

/**
  * Created by dev on 25/06/2018.
  */
trait NotificationPollingModule  {

  //poll db every X (configurable) milliseconds to fetch distinct csids which are not locked already and send them to NotificationDispatcher.process
  // it should be kicked off as soon as the service starts

}
