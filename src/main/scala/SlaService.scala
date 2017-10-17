package throttling.service

import scala.concurrent.{Future, blocking}
import scala.concurrent.ExecutionContext.Implicits._
import scala.util.Random

trait SlaService {
  def getSlaByToken(token: String): Future[Sla]
}

case class Sla(user: String, rps: Int)

class SlaServiceImpl extends SlaService {

  val database =
    Map(
      "token1" -> Sla("User1", 1),
      "token2" -> Sla("User2", 3),
      "token3" -> Sla("User3", 5),
      "token4" -> Sla("User4", 10),
      "token5" -> Sla("User4", 10)
    )


  override def getSlaByToken(token: String) = Future {
    blocking {
      Thread.sleep(250 + {
        if (Random.nextBoolean()) Random.nextInt(25) else -Random.nextInt(25)
      }) // 225 - 275 ms
      database(token)
    }
  }


}