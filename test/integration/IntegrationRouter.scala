/*
 * Copyright 2023 HM Revenue & Customs
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


import com.typesafe.config.ConfigFactory
import integration.IntegrationRouter._
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Suite}
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api._
import play.api.inject.DefaultApplicationLifecycle
import play.api.libs.json.{util => jsonUtil, _}
import play.api.mvc._
import play.api.routing.Router
import play.api.routing.sird._
import play.api.test.Helpers.AUTHORIZATION
import play.core.server.{Server, ServerConfig, ServerProvider}
import util.TestData

import scala.util.{Failure, Success}

trait IntegrationRouter extends BeforeAndAfterEach
  with BeforeAndAfterAll {
  this: Suite with GuiceOneServerPerSuite =>

  def testApp: Application = {
    val context = ApplicationLoader.Context(
      environment = Environment.simple(path = serverConfig.rootDir, mode = serverConfig.mode),
      initialConfiguration = Configuration(ConfigFactory.load()),
      lifecycle = new DefaultApplicationLifecycle,
      devContext = None
    )

    new BuiltInComponentsFromContext(context) {
      override def httpFilters: Seq[EssentialFilter] = Nil

      override def router: Router = Router.from(routes(Action))
    }.application
  }

  private var server: Server = _
//
//  override def beforeAll(): Unit = {
//    println("Starting test server")
//    Play.start(testApp)
//    server = ServerProvider.defaultServerProvider.createServer(serverConfig, testApp)
//    super.beforeAll()
//  }
//
//  override def afterAll(): Unit = {
//    println("Shutting down test server")
//    try {
//      super.afterAll()
//    } finally {
//      server.stop()
//    }
//  }

  private val routes: DefaultActionBuilder => Router.Routes = Action => {
    case _ =>
      Action { req =>
        req match {
          case GET(r) if req.path == s"$ApiSubsFieldsUrlContext/${TestData.NewClientSubscriptionId.id.toString}" =>
            Responses.ApiSubscriptionFieldsOk
          case GET(r) if req.path.startsWith(ApiSubsFieldsUrlContext) =>
            Results.NotFound.withHeaders()
          case POST(r) if req.path == InternalPushUrlContext =>
            if (req.headers.get(AUTHORIZATION).contains(TestData.PushSecurityToken.value)) {
              Results.Ok
            } else {
              Results.Unauthorized
            }
          case POST(r) if r.path == ExternalPushUrlContext =>
            req.body.asJson match {
              case Some(o: JsObject) =>
                val callbackUrl = o.value.get("url").fold("")(_.as[String])
                if (callbackUrl == TestData.ClientPushUrl.toString) {
                  Results.Ok
                } else if (callbackUrl == TestData.BadRequestUrl.toString) {
                  Results.BadRequest
                } else if (callbackUrl == TestData.InternalServerErrorUrl.toString) {
                  Results.InternalServerError
                } else
                  Results.BadGateway
            }
          case POST(r) if r.path == MetricsUrlContext =>
            Results.Ok
          case _ =>
            Results.ImATeapot // short and stout, couldn't match the path so error to stdout
        }
      }
  }
}

object IntegrationRouter {
  val TestHost = "localhost"
  val TestPort = 9666
  val TestOrigin = s"http://$TestHost:$TestPort"
  val serverConfig = ServerConfig(port = Some(TestPort), mode = Mode.Test)

  val ApiSubsFieldsUrlContext = "/field"
  val InternalPushUrlContext = "/some-custom-internal-push-url"
  val ExternalPushUrlContext = "/notify-customs-declarant"
  val MetricsUrlContext = "/log-times"
  val PullQueueContext = "/queue"

  object Responses {
    val ApiSubscriptionFieldsOk: Result =
      Results.Ok {
        s"""
           |{
           |  "clientId" : "${TestData.ClientId}",
           |  "apiContext" : "customs/declarations",
           |  "apiVersion" : "1.0",
           |  "fieldsId" : "${TestData.NewClientSubscriptionId}",
           |  "fields": {
           |    "callbackUrl": "${TestData.ClientPushUrl.toString}",
           |    "securityToken": "${TestData.PushSecurityToken.value}"
           |  }
           |}
           |""".stripMargin
      }
  }
}