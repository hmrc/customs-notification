package uk.gov.hmrc.customs.notification.config

import com.google.inject.AbstractModule
import uk.gov.hmrc.customs.notification.services.RetryScheduler
import uk.gov.hmrc.http.HttpClient
import uk.gov.hmrc.play.bootstrap.http.DefaultHttpClient

private class HttpModule extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[HttpClient]).to(classOf[DefaultHttpClient])
  }
}

private class ConfigValidatorModule extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[ConfigValidator]).asEagerSingleton()
  }
}

private class RetrySchedulerModule extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[RetryScheduler]).asEagerSingleton()
  }
}
