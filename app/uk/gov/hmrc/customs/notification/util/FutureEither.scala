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

package uk.gov.hmrc.customs.notification.util

import cats.implicits.toBifunctorOps
import uk.gov.hmrc.customs.notification.util.Helpers.ignore

import scala.concurrent.{ExecutionContext, Future}

case class FutureEither[+E, +A](value: Future[Either[E, A]]) {
  def map[A1](f: A => A1)(implicit ec: ExecutionContext): FutureEither[E, A1] = FutureEither(value.map(_.map(f)))

  def flatMap[E1 >: E, A1](f: A => FutureEither[E1, A1])(implicit ec: ExecutionContext): FutureEither[E1, A1] =
    FutureEither(value.flatMap {
      case Right(a1) => f(a1).value
      case Left(e1) => Future.successful(Left(e1))
    })

  def withUnitAsError(implicit ec: ExecutionContext): FutureEither[Unit, A] =
    FutureEither(value.map(_.leftMap(ignore)))
}

object FutureEither {
  object Implicits {
    implicit class FutureEitherExtensions[E, A](fe: Future[Either[E, A]]) {
      def toFutureEither: FutureEither[E, A] = FutureEither(fe)
    }

    implicit class EitherExtensions[E, W, A](e: Either[E, A]) {
      def toFutureEither: FutureEither[E, A] = FutureEither(Future.successful(e))
    }
  }
}
