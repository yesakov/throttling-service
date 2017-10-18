package throttling.service

import akka.actor.{Actor, ActorRef, Props}
import org.scalatest._

class ThrottlerTest extends FlatSpec with Matchers with BeforeAndAfter with ActorSystemHelper {

  var throttler : ActorRef = null
  var counter = 0
  case object AddOne
  var adder: ActorRef = system.actorOf(Props(new Actor {
    def receive = {
      case AddOne => counter += 1
    }
  }))

  before {
    throttler = ThrottlingService.createThrottler(adder, 1, 10)
  }

  "counter" should "equals 10 after 1.05 seconds" in {
    for (x <- 1 to 20) {
      throttler ! AddOne
    }
    Thread.sleep(1050)
    counter shouldBe(10)
  }
}