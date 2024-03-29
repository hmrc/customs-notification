/*
 * Copyright 2024 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package integration

import com.google.inject.AbstractModule
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Span}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import play.api.inject.guice.GuiceableModule
import uk.gov.hmrc.customs.notification.logging.CdsLogger
import util.{UnitSpec, WireMockRunner}

case class IntegrationTestModule(mockLogger: CdsLogger) extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[CdsLogger]) toInstance mockLogger
  }

  def asGuiceableModule: GuiceableModule = GuiceableModule.guiceable(this)
}

trait IntegrationTestSpec extends UnitSpec
  with WireMockRunner
  with BeforeAndAfterEach
  with BeforeAndAfterAll
  with Eventually {

  override implicit def patienceConfig: PatienceConfig = super.patienceConfig.copy(timeout = Span(defaultTimeout.toMillis, Millis))

}
