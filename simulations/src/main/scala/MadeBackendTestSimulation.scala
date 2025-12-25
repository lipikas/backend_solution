import scala.concurrent.duration._

import scala.util.Random

import util.Try

import io.gatling.commons.validation._
import io.gatling.core.session.Session
import io.gatling.core.Predef._
import io.gatling.http.Predef._


class MadeBackendTestSimulation
  extends Simulation {

  def randomClientId() = Random.between(1, 5 + 1)
  def randomTransactionValue() = Random.between(1, 10000 + 1)
  def randomDescription() = Random.alphanumeric.take(10).mkString
  def randomTransactionType() = Seq("c", "d", "d")(Random.between(0, 2 + 1)) // not used
  def toInt(s: String): Option[Int] = {
    try {
      Some(s.toInt)
    } catch {
      case e: Exception => None
    }
  }

  val validateBalanceLimitConsistency = (value: Option[String], session: Session) => {
    /*
      This function is fragile because it depends on having an entry
      called 'limit' with a value convertible to int in the session
      and also that it be chained with jmesPath("balance") so
      that 'value' is the first argument of the validator function
      of 'validate(.., ..)'.
      
      =============================================================
      
      Note for those without performance testing experience:
        The balance/limit logic test goes beyond what is commonly 
        done in performance tests only because of the nature
        of Made Backend Test. Avoid doing this type of thing in 
        performance tests, as it is not a recommended practice
        normally.
    */ 

    val balance = value.flatMap(s => Try(s.toInt).toOption)
    val limit = toInt(session("limit").as[String])

    (balance, limit) match {
      case (Some(s), Some(l)) if s.toInt < l.toInt * -1 => Failure("Limit exceeded!")
      case (Some(s), Some(l)) if s.toInt >= l.toInt * -1 => Success(Option("ok"))
      case _ => Failure("WTF?!")
    }
  }

  val httpProtocol = http
    .baseUrl("http://localhost:9999")
    .userAgentHeader("Chaos Agent - 2024/Q1")

  val debits = scenario("debits")
    .exec {s =>
      val description = randomDescription()
      val client_id = randomClientId()
      val value = randomTransactionValue()
      val payload = s"""{"value": ${value}, "type": "d", "description": "${description}"}"""
      val session = s.setAll(Map("description" -> description, "client_id" -> client_id, "payload" -> payload))
      session
    }
    .exec(
      http("debits")
      .post(s => s"/clients/${s("client_id").as[String]}/transactions")
          .header("content-type", "application/json")
          .body(StringBody(s => s("payload").as[String]))
          .check(
            status.in(200, 422),
            status.saveAs("httpStatus"))
          .checkIf(s => s("httpStatus").as[String] == "200") { jmesPath("limit").saveAs("limit") }
          .checkIf(s => s("httpStatus").as[String] == "200") {
            jmesPath("balance").validate("BalanceLimitConsistency - Transaction", validateBalanceLimitConsistency)
          }
    )

  val credits = scenario("credits")
    .exec {s =>
      val description = randomDescription()
      val client_id = randomClientId()
      val value = randomTransactionValue()
      val payload = s"""{"value": ${value}, "type": "c", "description": "${description}"}"""
      val session = s.setAll(Map("description" -> description, "client_id" -> client_id, "payload" -> payload))
      session
    }
    .exec(
      http("credits")
      .post(s => s"/clients/${s("client_id").as[String]}/transactions")
          .header("content-type", "application/json")
          .body(StringBody(s => s("payload").as[String]))
          .check(
            status.in(200),
            jmesPath("limit").saveAs("limit"),
            jmesPath("balance").validate("BalanceLimitConsistency - Transaction", validateBalanceLimitConsistency)
          )
    )

  val statements = scenario("statements")
    .exec(
      http("statements")
      .get(s => s"/clients/${randomClientId()}/statement")
      .check(
        jmesPath("balance.limit").saveAs("limit"),
        jmesPath("balance.total").validate("BalanceLimitConsistency - Statement", validateBalanceLimitConsistency)
    )
  )

  val concurrentValidationNumRequests = 25
  val concurrentTransactionValidation = (transactionType: String) =>
    scenario(s"concurrent transaction validation - ${transactionType}")
    .exec(
      http("validations")
      .post(s"/clients/1/transactions")
          .header("content-type", "application/json")
          .body(StringBody(s"""{"value": 1, "type": "${transactionType}", "description": "validation"}"""))
          .check(status.is(200))
    )
  
  val concurrentTransactionBalanceValidation = (expectedBalance: Int) =>
    scenario(s"concurrent balance validation - ${expectedBalance}")
    .exec(
      http("validations")
      .get(s"/clients/1/statement")
      .check(
        jmesPath("balance.total").ofType[Int].is(expectedBalance)
      )
    )

  val initialClientBalances = Array(
    Map("id" -> 1, "limit" ->   1000 * 100),
    Map("id" -> 2, "limit" ->    800 * 100),
    Map("id" -> 3, "limit" ->  10000 * 100),
    Map("id" -> 4, "limit" -> 100000 * 100),
    Map("id" -> 5, "limit" ->   5000 * 100),
  )

  val clientNotFoundValidation = scenario("HTTP 404 validation")
    .exec(
      http("validations")
      .get("/clients/6/statement")
      .check(status.is(404))
    )

  val clientCriteria = scenario("validations")
    .feed(initialClientBalances)
    .exec(
      /*
        The http(...) values are intentionally duplicated
        so they are grouped in the report and take up less space.
        The downside is that in case of failure, it may not be possible
        to know its exact cause.
      */ 
      http("validations")
      .get("/clients/#{id}/statement")
      .check(
        status.is(200),
        jmesPath("balance.limit").ofType[String].is("#{limit}"),
        jmesPath("balance.total").ofType[String].is("0")
      )
    )
    .exec(
      http("validations")
      .post("/clients/#{id}/transactions")
          .header("content-type", "application/json")
          .body(StringBody(s"""{"value": 1, "type": "c", "description": "take"}"""))
          .check(
            status.in(200),
            jmesPath("limit").saveAs("limit"),
            jmesPath("balance").validate("BalanceLimitConsistency - Transaction", validateBalanceLimitConsistency)
          )
    )
    .exec(
      http("validations")
      .post("/clients/#{id}/transactions")
          .header("content-type", "application/json")
          .body(StringBody(s"""{"value": 1, "type": "d", "description": "return"}"""))
          .check(
            status.in(200),
            jmesPath("limit").saveAs("limit"),
            jmesPath("balance").validate("BalanceLimitConsistency - Transaction", validateBalanceLimitConsistency)
          )
    )
    .exec(
      http("validations")
      .get("/clients/#{id}/statement")
      .check(
        jmesPath("latest_transactions[0].description").ofType[String].is("return"),
        jmesPath("latest_transactions[0].type").ofType[String].is("d"),
        jmesPath("latest_transactions[0].value").ofType[Int].is("1"),
        jmesPath("latest_transactions[1].description").ofType[String].is("take"),
        jmesPath("latest_transactions[1].type").ofType[String].is("c"),
        jmesPath("latest_transactions[1].value").ofType[Int].is("1")
      )
    )
    .exec( // Statement consistency
      http("validations")
      .post("/clients/#{id}/transactions")
          .header("content-type", "application/json")
          .body(StringBody(s"""{"value": 1, "type": "c", "description": "tricky"}"""))
          .check(
            status.in(200),
            jmesPath("balance").saveAs("balance"),
            jmesPath("limit").saveAs("limit")
          )
          .resources(
            // 5 simultaneous statement queries to verify consistency
            http("validations").get("/clients/#{id}/statement").check(
              jmesPath("latest_transactions[0].description").ofType[String].is("tricky"),
              jmesPath("latest_transactions[0].type").ofType[String].is("c"),
              jmesPath("latest_transactions[0].value").ofType[Int].is("1"),
              jmesPath("balance.limit").ofType[String].is("#{limit}"),
              jmesPath("balance.total").ofType[String].is("#{balance}")
            ),
            http("validations").get("/clients/#{id}/statement").check(
              jmesPath("latest_transactions[0].description").ofType[String].is("tricky"),
              jmesPath("latest_transactions[0].type").ofType[String].is("c"),
              jmesPath("latest_transactions[0].value").ofType[Int].is("1"),
              jmesPath("balance.limit").ofType[String].is("#{limit}"),
              jmesPath("balance.total").ofType[String].is("#{balance}")
            ),
            http("validations").get("/clients/#{id}/statement").check(
              jmesPath("latest_transactions[0].description").ofType[String].is("tricky"),
              jmesPath("latest_transactions[0].type").ofType[String].is("c"),
              jmesPath("latest_transactions[0].value").ofType[Int].is("1"),
              jmesPath("balance.limit").ofType[String].is("#{limit}"),
              jmesPath("balance.total").ofType[String].is("#{balance}")
            ),
            http("validations").get("/clients/#{id}/statement").check(
              jmesPath("latest_transactions[0].description").ofType[String].is("tricky"),
              jmesPath("latest_transactions[0].type").ofType[String].is("c"),
              jmesPath("latest_transactions[0].value").ofType[Int].is("1"),
              jmesPath("balance.limit").ofType[String].is("#{limit}"),
              jmesPath("balance.total").ofType[String].is("#{balance}")
            )
        )
    )
  
  .exec(
      http("validations")
      .post("/clients/#{id}/transactions")
          .header("content-type", "application/json")
          .body(StringBody(s"""{"value": 1.2, "type": "d", "description": "return"}"""))
          .check(status.in(422, 400))
    )
    .exec(
      http("validations")
      .post("/clients/#{id}/transactions")
          .header("content-type", "application/json")
          .body(StringBody(s"""{"value": 1, "type": "x", "description": "return"}"""))
          .check(status.in(422, 400))
    )
    .exec(
      http("validations")
      .post("/clients/#{id}/transactions")
          .header("content-type", "application/json")
          .body(StringBody(s"""{"value": 1, "type": "c", "description": "123456789 and a bit more"}"""))
          .check(status.in(422, 400))
    )
    .exec(
      http("validations")
      .post("/clients/#{id}/transactions")
          .header("content-type", "application/json")
          .body(StringBody(s"""{"value": 1, "type": "c", "description": ""}"""))
          .check(status.in(422, 400))
    )
    .exec(
      http("validations")
      .post("/clients/#{id}/transactions")
          .header("content-type", "application/json")
          .body(StringBody(s"""{"value": 1, "type": "c", "description": null}"""))
          .check(status.in(422, 400))
    )

  /* 
    Separating credits and debits gives a better view
    of how the two operations behave individually.
  */
  setUp(
    concurrentTransactionValidation("d").inject(
      atOnceUsers(concurrentValidationNumRequests)
    ).andThen(
      concurrentTransactionBalanceValidation(concurrentValidationNumRequests * -1).inject(
        atOnceUsers(1)
      )
    ).andThen(
      concurrentTransactionValidation("c").inject(
        atOnceUsers(concurrentValidationNumRequests)
      ).andThen(
        concurrentTransactionBalanceValidation(0).inject(
          atOnceUsers(1)
        )
      )
    ).andThen(
      clientCriteria.inject(
        atOnceUsers(initialClientBalances.length)
      ),
      clientNotFoundValidation.inject(
        atOnceUsers(1)
      ).andThen(
        debits.inject(
          rampUsersPerSec(1).to(220).during(2.minutes),
          constantUsersPerSec(220).during(2.minutes)
        ),
        credits.inject(
          rampUsersPerSec(1).to(110).during(2.minutes),
          constantUsersPerSec(110).during(2.minutes)
        ),
        statements.inject(
          rampUsersPerSec(1).to(10).during(2.minutes),
          constantUsersPerSec(10).during(2.minutes)
        )
      )
    )
  ).protocols(httpProtocol)
}
