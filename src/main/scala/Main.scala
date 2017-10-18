package throttling.service

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import scala.concurrent.duration._
import akka.pattern.ask
import spray.json._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import com.typesafe.config.{ConfigFactory}

import scala.io.StdIn

trait HealthJsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val healthFormat = jsonFormat2(UserRequest)
}

trait ActorSystemHelper {
  implicit val system = ActorSystem("simple-rest-system")
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = system.dispatcher
}

object HttpServer extends HealthJsonSupport with ActorSystemHelper {

  private val config = ConfigFactory.load()
  private val host = config.getString("http.host")
  private val port = config.getInt("http.port")

  def main(args: Array[String]): Unit = {

    val requestHandler = system.actorOf(ThrottlingService.props(config.getInt("throttling.grace_rps")),"requestHandler")

    //Define the route
    val route : Route = {

      implicit val timeout = Timeout(100.seconds)

      path("throttle") {
        post {
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
      }
    }

    //Startup, and listen for requests
    val bindingFuture = Http().bindAndHandle(route, host, port)
    println(s"Waiting for requests at http://$host:$port/...\nHit RETURN to terminate")

    StdIn.readLine()

    //Shutdown
    bindingFuture.flatMap(_.unbind())
    system.terminate()
  }
}