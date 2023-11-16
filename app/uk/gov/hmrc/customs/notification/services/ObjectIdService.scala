package uk.gov.hmrc.customs.notification.services

import org.bson.types.ObjectId

import javax.inject.{Inject, Singleton}

@Singleton
class ObjectIdService @Inject()() {
  def newId(): ObjectId = new ObjectId()
}
