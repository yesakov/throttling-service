import org.scalatest.{Matchers, WordSpec}
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.http.scaladsl.server._
import Directives._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import com.typesafe.config.ConfigFactory
import throttling.service.{ThrottleRequest, ThrottleResponse, ThrottlingService, UserRequest}
import akka.pattern.ask
import akka.util.Timeout
import spray.json.DefaultJsonProtocol
import scala.concurrent.duration._

trait HealthJsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val healthFormat = jsonFormat2(UserRequest)
}


class ThrottlerServiceTest extends WordSpec with Matchers with ScalatestRouteTest with HealthJsonSupport {
  def actorRefFactory = system

  private val config = ConfigFactory.load()
  private val graceRps = config.getInt("throttling.grace_rps")
  private val requestHandler = system.actorOf(ThrottlingService.props(config.getInt("throttling.grace_rps")),"requestHandler")

  implicit val timeout = Timeout(100.seconds)
  private val route = post {
    entity(as[UserRequest]) { statusReport =>
      onSuccess(requestHandler ? ThrottleRequest(statusReport)) {
        case response: ThrottleResponse =>
          println("OK")
          complete(
            StatusCodes.OK,
            if (response.sla.isDefined) {
              "User '" + response.sla.get.user.toString + "' with rps = " + response.sla.get.rps.toString
            } else ""
          )
        case _ =>
          println("fail")
          complete(StatusCodes.InternalServerError)
      }
    }
  }


  "A Service" should {
    "for not unauthorized users grace rps" in {
      Post("/throttle", HttpEntity(
          ContentTypes.`application/json`,
          "{\"token\":\"token5\", \"description\":\"test user4\"}"
        )) ~> route ~> check {
          status shouldEqual StatusCodes.OK
          println(responseAs[String])
          responseAs[String] shouldEqual "User 'not authorized' with rps = " + graceRps
      }
    }
  }
}
