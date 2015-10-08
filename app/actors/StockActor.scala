package actors

import akka.actor.{Props, ActorRef, Actor}
import utils.{StockQuote, FakeStockQuote}
import java.util.Random
import scala.collection.immutable.{HashSet, Queue}
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import play.libs.Akka
import scalaj.http._
import sys.process._
import java.net.URL
import java.io.File
import java.util.{Date, Locale}
import java.util.Calendar
import java.text.DateFormat
import java.text.DateFormat._

/**
 * There is one StockActor per stock symbol.  The StockActor maintains a list of users watching the stock and the stock
 * values.  Each StockActor updates a rolling dataset of randomly generated stock values.
 */

class StockActor(symbol: String) extends Actor {

  lazy val stockQuote: StockQuote = new FakeStockQuote

  protected[this] var watchers: HashSet[ActorRef] = HashSet.empty[ActorRef]

  // A random data set which uses stockQuote.newPrice to get each data point
  var stockHistory: Queue[java.lang.Double] = {
    lazy val initialPrices: Stream[java.lang.Double]

    val fromDate = Calendar.getInstance()
    fromDate.add(Calendar.DATE, -8);
    val financeFromMonth = fromDate.get(Calendar.MONTH)
    val financeFromDay = fromDate.get(Calendar.DAY_OF_MONTH)
    val financeFromYear = fromDate.get(Calendar.YEAR)

    val tomDate = Calendar.getInstance()
    tomDate.add(Calendar.DATE, -1);
    val financeToMonth = tomDate.get(Calendar.MONTH)
    val financeToDay = tomDate.get(Calendar.DAY_OF_MONTH)
    val financeToYear = tomDate.get(Calendar.YEAR)
    
    val financeSymbol = "T"
    

    val filePath = s"/tmp/${financeSymbol}.csv"
    new URL(s"http://ichart.finance.yahoo.com/table.csv?s=${financeSymbol}&a=${financeFromMonth}&b=${financeFromDay}&c=${financeFromYear}&d=${financeToMonth}&e=${financeToDay}&f=${financeToYear}&g=d&ignore=.csv") #> new File(filePath) !!

    val bufferedSource = io.Source.fromFile(filePath)
    bufferedSource.getLines.drop(1) // delete names of columns
    for (line <- bufferedSource.getLines) {
       val cols = line.split(",").map(_.trim)
         initialPrices = (${cols(4)}.asInstanceOf[java.lang.Double]) #:: initialPrices.map(previous => stockQuote.newPrice(previous))
         initialPrices.take(1).to[Queue]
    }
    println(s"initialPrices.toString =${initialPrices.toString}")

    // val initialPrices: Stream[java.lang.Double] = (new Random().nextDouble * 800) #:: initialPrices.map(previous => stockQuote.newPrice(previous))
    // initialPrices.take(50).to[Queue]
  }
  
  // // Fetch the latest stock value every 75ms
  // val stockTick = context.system.scheduler.schedule(Duration.Zero, 75.millis, self, FetchLatest)

  def receive = {
    case FetchLatest =>
      // add a new stock price to the history and drop the oldest
      // val newPrice = stockQuote.newPrice(stockHistory.last.doubleValue())
      // stockHistory = stockHistory.drop(1) :+ newPrice
      // // notify watchers
      // watchers.foreach(_ ! StockUpdate(symbol, newPrice))
    case WatchStock(_) =>
      // send the stock history to the user
      sender ! StockHistory(symbol, stockHistory.asJava)
      // add the watcher to the list
      watchers = watchers + sender
    case UnwatchStock(_) =>
      watchers = watchers - sender
      if (watchers.size == 0) {
        // stockTick.cancel()
        context.stop(self)
      }
  }
}

class StocksActor extends Actor {
  def receive = {
    case watchStock @ WatchStock(symbol) =>
      // get or create the StockActor for the symbol and forward this message
      context.child(symbol).getOrElse {
        context.actorOf(Props(new StockActor(symbol)), symbol)
      } forward watchStock
    case unwatchStock @ UnwatchStock(Some(symbol)) =>
      // if there is a StockActor for the symbol forward this message
      context.child(symbol).foreach(_.forward(unwatchStock))
    case unwatchStock @ UnwatchStock(None) =>
      // if no symbol is specified, forward to everyone
      context.children.foreach(_.forward(unwatchStock))
  }
}

object StocksActor {
  lazy val stocksActor: ActorRef = Akka.system.actorOf(Props(classOf[StocksActor]))
}


case object FetchLatest

case class StockUpdate(symbol: String, price: Number)

case class StockHistory(symbol: String, history: java.util.List[java.lang.Double])

case class WatchStock(symbol: String)

case class UnwatchStock(symbol: Option[String])