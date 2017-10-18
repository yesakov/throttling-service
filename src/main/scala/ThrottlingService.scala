package throttling.service

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import scala.concurrent.duration._
import akka.NotUsed
import akka.stream.{OverflowStrategy, ThrottleMode}
import akka.stream.scaladsl.{Sink, Source}


case class UserRequest(token: Option[String], description: String)
case class ThrottleRequest(userRequest: UserRequest)
case class ThrottleResponse(sla: Option[Sla])
case class Throttle(responder: ActorRef, userSla: Option[Sla])

object ThrottlingService extends ActorSystemHelper {
  def props(graceRps: Int): Props = Props(new ThrottlingService(graceRps))

  def createThrottler(actorToThrottle: ActorRef, duration: Int, rps: Int): ActorRef = {
    Source.actorRef(bufferSize = 1000, OverflowStrategy.dropNew)
      .throttle(rps, duration.second, 0, ThrottleMode.Shaping)
      .to(Sink.actorRef(actorToThrottle, NotUsed))
      .run()
  }
}

class ThrottlingService(val graceRps: Int) extends Actor with ActorLogging {

  implicit val system = ActorSystem("simple-rest-system")
  private implicit val executionContext = system.dispatcher

  private val throttlingCache = scala.collection.mutable.HashMap.empty[String, ActorRef]
  private val slaCache = scala.collection.mutable.HashMap.empty[String, Sla]

  private val unauthorizedToken = "not authorized"
  private val unauthorizedSla = Sla(unauthorizedToken, graceRps)

  private val unauthorizedThrottler =
    ThrottlingService.createThrottler(system.actorOf(Throttler.props()), 1, graceRps)

  throttlingCache.put(unauthorizedToken, unauthorizedThrottler)

  val slaService: SlaServiceImpl = new SlaServiceImpl(unauthorizedSla)

  def receive: Receive = {

    case request: ThrottleRequest =>
      val throttler = if (request.userRequest.token.isEmpty) {
        throttlingCache.getOrElse(unauthorizedToken, {
          throttlingCache.put(unauthorizedToken, unauthorizedThrottler)
          unauthorizedThrottler
        })
      } else {
        val userToken = request.userRequest.token.get
        val userSla = slaCache.getOrElse(userToken, {
          slaCache.put(userToken, unauthorizedSla)
          slaService.getSlaByToken(userToken).onComplete(result => {
            slaCache.put(userToken, result.get)
            throttlingCache.put(
              result.get.user,
              ThrottlingService.createThrottler(system.actorOf(Throttler.props()), 1, result.get.rps)
            )
          })
          unauthorizedSla
        })
        throttlingCache.getOrElse(userSla.user, {
          unauthorizedThrottler
        })
      }

      throttler ! Throttle(sender(), slaCache.get(request.userRequest.token.get))
  }
}

object Throttler {
  def props(): Props = Props(new Throttler())
}

class Throttler extends Actor with ActorLogging {
  def receive: Receive = {
    case request: Throttle => {
      request.responder ! ThrottleResponse(request.userSla)
    }
  }
}

