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

import uk.gov.hmrc.customs.notification.models.errors.CdsError

import scala.concurrent.{ExecutionContext, Future}

case class FutureCdsResult[+E <: CdsError, +A](value: Future[Either[E, A]]) {
  def map[A1](f: A => A1)(implicit ec: ExecutionContext): FutureCdsResult[E, A1] = FutureCdsResult(value.map(_.map(f)))

  def flatMap[E1 >: E <: CdsError, A1](f: A => FutureCdsResult[E1, A1])(implicit ec: ExecutionContext): FutureCdsResult[E1, A1] =
    FutureCdsResult(value.flatMap {
      case Right(a1) => f(a1).value
      case Left(e1) => Future.successful(Left(e1))
    })
}

object FutureCdsResult {
  object Implicits {

    implicit class FutureCdsResultExtensions[E <: CdsError, +A](r: FutureCdsResult[E, A]) {
      def mapError[E1 >: E <: CdsError](f: E => E1)(implicit ec: ExecutionContext): FutureCdsResult[E1, A] = FutureCdsResult(r.value.map {
        case Left(e) => Left(f(e))
        case Right(a) => Right(a)
      })
    }

    implicit class FutureEitherExtensions[E <: CdsError, A](fe: Future[Either[E, A]]) {
      def toFutureCdsResult: FutureCdsResult[E, A] = FutureCdsResult(fe)
    }

    implicit class EitherExtensions[E <: CdsError, W <: CdsError, A](e: Either[E, A]) {
      def toFutureCdsResult: FutureCdsResult[E, A] = FutureCdsResult(Future.successful(e))
    }
  }
}
