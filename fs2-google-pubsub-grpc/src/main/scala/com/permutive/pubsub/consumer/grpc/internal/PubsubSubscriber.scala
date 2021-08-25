package com.permutive.pubsub.consumer.grpc.internal

import cats.effect.kernel.{Async, Resource, Sync}
import cats.effect.std.{Dispatcher, Queue, QueueSink}
import cats.syntax.all._
import com.google.api.core.ApiService
import com.google.api.gax.batching.FlowControlSettings
import com.google.cloud.pubsub.v1.{AckReplyConsumer, MessageReceiver, Subscriber}
import com.google.common.util.concurrent.MoreExecutors
import com.google.pubsub.v1.{ProjectSubscriptionName, PubsubMessage}
import com.permutive.pubsub.consumer.grpc.PubsubGoogleConsumer.InternalPubSubError
import com.permutive.pubsub.consumer.grpc.PubsubGoogleConsumerConfig
import com.permutive.pubsub.consumer.{Model => PublicModel}
import fs2.Stream
import org.threeten.bp.Duration

import java.util.concurrent.TimeUnit

private[consumer] object PubsubSubscriber {

  private def createSubscriber[F[_]: Sync](
    projectId: PublicModel.ProjectId,
    subscription: PublicModel.Subscription,
    config: PubsubGoogleConsumerConfig[F],
    queue: QueueSink[F, Either[InternalPubSubError, Model.Record[F]]],
    dispatcher: Dispatcher[F],
  ): Resource[F, ApiService] =
    Resource.make(
      Sync[F].delay {
        val receiver         = new PubsubMessageReceiver(queue, dispatcher)
        val subscriptionName = ProjectSubscriptionName.of(projectId.value, subscription.value)

        // build subscriber with "normal" settings
        val builder =
          Subscriber
            .newBuilder(subscriptionName, receiver)
            .setFlowControlSettings(
              FlowControlSettings
                .newBuilder()
                .setMaxOutstandingElementCount(config.maxQueueSize.toLong)
                .build()
            )
            .setParallelPullCount(config.parallelPullCount)
            .setMaxAckExtensionPeriod(Duration.ofMillis(config.maxAckExtensionPeriod.toMillis))

        // if provided, use subscriber transformer to modify subscriber
        val sub =
          config.customizeSubscriber
            .map(f => f(builder))
            .getOrElse(builder)
            .build()

        sub.addListener(new PubsubErrorListener(queue, dispatcher), MoreExecutors.directExecutor)

        sub.startAsync()
      }
    )(service =>
      Sync[F]
        .blocking(
          service.stopAsync().awaitTerminated(config.awaitTerminatePeriod.toSeconds, TimeUnit.SECONDS)
        )
        .handleErrorWith(config.onFailedTerminate)
    )

  class PubsubMessageReceiver[F[_]: Sync, L](
    queue: QueueSink[F, Either[L, Model.Record[F]]],
    dispatcher: Dispatcher[F],
  ) extends MessageReceiver {
    override def receiveMessage(message: PubsubMessage, consumer: AckReplyConsumer): Unit =
      dispatcher.unsafeRunSync(
        queue.offer(Right(Model.Record(message, Sync[F].delay(consumer.ack()), Sync[F].delay(consumer.nack()))))
      )
  }

  class PubsubErrorListener[F[_], R](
    queue: QueueSink[F, Either[InternalPubSubError, R]],
    dispatcher: Dispatcher[F],
  ) extends ApiService.Listener {
    override def failed(from: ApiService.State, failure: Throwable): Unit =
      dispatcher.unsafeRunSync(queue.offer(Left(InternalPubSubError(failure))))

  }

  def subscribe[F[_]: Async](
    projectId: PublicModel.ProjectId,
    subscription: PublicModel.Subscription,
    config: PubsubGoogleConsumerConfig[F],
  ): Stream[F, Model.Record[F]] =
    for {
      queue      <- Stream.eval(Queue.bounded[F, Either[InternalPubSubError, Model.Record[F]]](config.maxQueueSize))
      dispatcher <- Stream.resource(Dispatcher[F])
      _          <- Stream.resource(PubsubSubscriber.createSubscriber(projectId, subscription, config, queue, dispatcher))
      next       <- Stream.fromQueueUnterminated(queue)
      msg        <- Stream.fromEither[F](next)
    } yield msg
}
