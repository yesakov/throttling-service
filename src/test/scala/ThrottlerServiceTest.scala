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

  private val config = ConfigFactory.load()
  private val graceRps = config.getInt("throttling.grace_rps")
  private val requestHandler = system.actorOf(ThrottlingService.props(graceRps),"requestHandler")

  implicit val timeout = Timeout(100.seconds)
  private val route = post {
    entity(as[UserRequest]) { statusReport =>
      onSuccess(requestHandler ? ThrottleRequest(statusReport)) {
        case response: ThrottleResponse =>
          complete(
            StatusCodes.OK,
            if (response.sla.isDefined) {
              "User '" + response.sla.get.user.toString + "' with rps = " + response.sla.get.rps.toString
            } else ""
          )
        case _ =>
          complete(StatusCodes.InternalServerError)
      }
    }
  }

  "A Service" should {
    "return 'User 'not authorized' with rps = " + graceRps + "' for not unauthorized users" in {
      Post("/throttle", HttpEntity(
          ContentTypes.`application/json`,
          "{\"token\":\"unathorized\", \"description\":\"some user\"}"
        )) ~> route ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[String] shouldEqual "User 'not authorized' with rps = " + graceRps
      }
    }
  }

  "A Service" should {
    "return correct rps for authorized user" in {
      Post("/throttle", HttpEntity(
        ContentTypes.`application/json`,
        "{\"token\":\"token4\", \"description\":\"some user\"}"
      )) ~> route ~> check {}
      // wait to get Sla from service
      Thread.sleep(500)
      Post("/throttle", HttpEntity(
        ContentTypes.`application/json`,
        "{\"token\":\"token4\", \"description\":\"some user\"}"
      )) ~> route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual "User 'User4' with rps = 10"
      }
    }
  }

  val n = 2
  val t = 10
  val rps = 10
  var successfulRequestsCounter = 0

  // #3 of 'Acceptance	Criteria'  is not clear enough: "... for N users, K rsp during T
  // seconds around T*N*K requests were successful" - K is same for all users or average number?
  // so lets take 2 users with same rps (10) it should be 200 successful requests  in ~ 10 seconds

  "A Service" should {
    "should make T * N * K (" + n * t * rps +  ") successful requests in " + t + " seconds" in {
      Post("/throttle", HttpEntity(
        ContentTypes.`application/json`,
        "{\"token\":\"tokenTest1\", \"description\":\"some user1\"}"
      )) ~> route ~> check{}
      Post("/throttle", HttpEntity(
        ContentTypes.`application/json`,
        "{\"token\":\"tokenTest2\", \"description\":\"some user2\"}"
      )) ~> route ~> check{}


      def runRequests = for(x <- 0 until t * rps) {
        Post("/throttle", HttpEntity(
          ContentTypes.`application/json`,
          "{\"token\":\"tokenTest1\", \"description\":\"some user1\"}"
        )) ~> route ~> check {
          if (status == StatusCodes.OK) successfulRequestsCounter += 1
        }
        Post("/throttle", HttpEntity(
          ContentTypes.`application/json`,
          "{\"token\":\"tokenTest2\", \"description\":\"some user2\"}"
        )) ~> route ~> check {
          if (status == StatusCodes.OK) successfulRequestsCounter += 1
        }
      }

      var executionTime = 0.0

      def time[R](block: => R): R = {
        val t0 = System.nanoTime()
        val result = block    // call-by-name
        val t1 = System.nanoTime()
        executionTime = (t1 - t0) / 1000000
        result
      }

      time { runRequests }
      assert(t - 3 to t + 3 contains (executionTime / 1000).toInt)
      successfulRequestsCounter shouldEqual t * n * rps
    }
  }
}
