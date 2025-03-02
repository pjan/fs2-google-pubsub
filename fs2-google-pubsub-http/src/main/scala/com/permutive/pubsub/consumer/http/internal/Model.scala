package com.permutive.pubsub.consumer.http.internal

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import com.permutive.pubsub.consumer.http.PubsubMessage
import com.permutive.pubsub.consumer.{ConsumerRecord, Model => PublicModel}

import scala.concurrent.duration.FiniteDuration

private[http] object Model {
  case class ProjectNameSubscription(value: String) extends AnyVal
  object ProjectNameSubscription {
    def of(projectId: PublicModel.ProjectId, subscription: PublicModel.Subscription): ProjectNameSubscription =
      ProjectNameSubscription(s"projects/${projectId.value}/subscriptions/${subscription.value}")
  }

  trait InternalRecord[F[_]] { self =>
    def value: PubsubMessage
    def ack: F[Unit]
    def nack: F[Unit]
    def extendDeadline(by: FiniteDuration): F[Unit]

    def toConsumerRecord[A](v: A): ConsumerRecord[F, A] =
      new ConsumerRecord[F, A] {
        override val value: A                        = v
        override val attributes: Map[String, String] = self.value.attributes
        override val ack: F[Unit]                    = self.ack
        override val nack: F[Unit]                   = self.nack

        override def extendDeadline(by: FiniteDuration): F[Unit] = self.extendDeadline(by)
      }
  }

  case class AckId(value: String) extends AnyVal

  case class PullRequest(
    returnImmediately: Boolean,
    maxMessages: Int
  )

  object PullRequest {
    implicit final val PullRequestCodec: JsonValueCodec[PullRequest] =
      JsonCodecMaker.make[PullRequest](CodecMakerConfig)
  }

  case class PullResponse(
    receivedMessages: List[ReceivedMessage]
  )

  object PullResponse {
    implicit final val PullResponseCodec: JsonValueCodec[PullResponse] =
      JsonCodecMaker.make[PullResponse](CodecMakerConfig)
  }

  case class ReceivedMessage(
    ackId: AckId,
    message: PubsubMessage
  )

  case class AckRequest(
    ackIds: List[AckId]
  )

  object AckRequest {
    implicit final val AckRequestCodec: JsonValueCodec[AckRequest] =
      JsonCodecMaker.make[AckRequest](CodecMakerConfig)
  }

  case class ModifyAckDeadlineRequest(
    ackIds: List[AckId],
    ackDeadlineSeconds: Long
  )

  object ModifyAckDeadlineRequest {
    implicit final val ModifyAckDeadlineRequestCodec: JsonValueCodec[ModifyAckDeadlineRequest] =
      JsonCodecMaker.make[ModifyAckDeadlineRequest](CodecMakerConfig)
  }

}
