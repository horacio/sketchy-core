package com.soundcloud.sketchy

import java.net.InetSocketAddress
import java.util.Date

import com.mysql.jdbc.Driver
import com.soundcloud.sketchy.context._
import com.soundcloud.sketchy.events._
import com.soundcloud.sketchy.util._
import com.thimbleware.jmemcached._
import com.thimbleware.jmemcached.storage.CacheStorage
import com.thimbleware.jmemcached.storage.hash.ConcurrentLinkedHashMap.EvictionPolicy
import com.thimbleware.jmemcached.storage.hash._
import net.spy.memcached._

import scala.io.Source.fromFile
import scala.slick.driver.H2Driver.simple.{Database => SlickDatabase}


trait SpecHelper {
  import com.soundcloud.sketchy.events.readers._

  /**
   * memcached setup
   */
  val memcachedAddr = new InetSocketAddress("localhost", 4348)

  lazy val memcached = {
    val daemon = new MemCacheDaemon[LocalCacheElement]()
    daemon.setCache(new CacheImpl(storage(EvictionPolicy.FIFO)))
    daemon.setBinary(false)
    daemon.setAddr(memcachedAddr)
    daemon.setIdleTime(0)
    daemon.setVerbose(false)
    daemon
  }

  def withMemcached(work: MemcachedClient => Unit) {
    try {
      memcached.start()
      work(MemcachedConnectionPool.get(connectionString(memcachedAddr)))
    } finally {
      memcached.stop()
    }
  }

  val day = 24 * 60 * 60
  val defaultBuckets = 24

  def memoryCtx() =
    new MemoryContext[TestStatistics](ContextCfg(ttl = day, numBuckets = defaultBuckets))

  def memcachedCtx(client: MemcachedClient, nameSpace: String = "m") =
    new MemcachedTestContext(client, ContextCfg(ttl = day, numBuckets = defaultBuckets), nameSpace)

  def memcachedStatisticsCtx(
    client: MemcachedClient,
    nameSpace: String = "s",
    userTtl: Int = ContextCfg.MaxTTL) =
    new MemcachedTestStatisticsContext(client, ContextCfg(userTtl = userTtl), nameSpace)

  private def storage(policy: EvictionPolicy): CacheStorage[Key, LocalCacheElement] =
    ConcurrentLinkedHashMap.create(policy, 10000, 10000);

  private def connectionString(addr: InetSocketAddress): String =
    memcachedAddr.getHostName() + ":" + memcachedAddr.getPort()

  /**
   * Context helpers
   */
   def countingContext() = new MemoryContext[Nothing]()

  /**
   * Fixtures spec helper
   */
  val fixturesPath = "core/src/test/resources/fixtures/"

  def brokerFixture(name: String) = fromFile(path("broker", name)).mkString

  def bulkStats(name: String): Seq[BulkStatistics] =
    fixtures("stat", name).split('\n').toSeq.map(BulkStatistics.unmarshal _)

  def junkStats(name: String): Seq[JunkStatistics] =
    fixtures("stat", name).split('\n').toSeq.map(JunkStatistics.unmarshal _)

  def fixture(kind: String, name: String) =
    fixtures(kind, name).head

  def fixtures(kind: String, name: String): String =
    fromFile(path(kind, name)).mkString

  def spamReport(name: String) =
    JSON.fromJson(brokerFixture("spam_report." + name)).get.as[SpamReport]

  def bulkStat(name: String) =
    bulkStats(name).head

  def junkStat(name: String) =
    junkStats(name).head

  def model(name: String): Array[Byte] =
    fromFile(path("model", name))(scala.io.Codec("ISO8859-1")).map(_.toByte).toArray

  private def path(kind: String, name: String): String =
    kind match {
      case "broker" => fixturesPath + "broker/" + name + ".json"
      case "model" => fixturesPath + "model/" + name + ".buf"
      case "stat" => fixturesPath + "stat/" + name + ".txt"
      case "signal" => fixturesPath + "signal/" + name + ".json"
      case "log" => fixturesPath + "log/" + name + ".log"
      case "db" => fixturesPath + "db/" + name + ".sql"
    }


  def edgeLikeUserToUser(
    _id: Long,
    _userId: Long,
    _recipientId: Long,
    _action: Action = UserEvent.Create,
    admin: Boolean = false) =
    new AbstractAffiliation {
      def id = Some(_id)
      val followeeId = Some(_recipientId)
      val createdAt = new Date
      action = _action

      def senderId = Some(_userId)
      def recipientId = Some(_recipientId)

      // edge like
      val sourceId = _userId
      val sinkId = _recipientId
      val graphId = None
      val edgeKind = "Affiliation"
      val isBidirectional = true

      def noSpamCheck = admin
    }

  def edgeLikeUserToItem(
    _id: Long,
    _userId: Long,
    _itemId: Long,
    _kind: String,
    _action: Action = UserEvent.Create,
    _deletedAt: Option[Date] = None,
    admin: Boolean = false) =
    new AbstractEdgeLike with DeleteOnUpdate {
      def id = Some(_id)
      val itemId = Some(_itemId)
      val createdAt = new Date
      override def kind = _kind
      action = _action

      def senderId = Some(_userId)
      def recipientId = Some(_itemId)

      // edge like
      val sourceId = _userId
      val sinkId = _itemId
      val graphId = None
      val edgeKind = _kind
      val isBidirectional = false

      val deletedAt: Option[Date] = _deletedAt

      def noSpamCheck = admin
    }

  def edgeLikeItemToItem(
    _id: Long,
    _userId: Long,
    _aItemId: Long,
    _bItemId: Long,
    _action: Action = UserEvent.Create,
    _kind: String = "Contribution",
    admin: Boolean = false) =
    new AbstractEdgeLike {
      def id = Some(_id)
      val itemId = Some(_aItemId)
      val createdAt = new Date
      override def kind = _kind
      action = _action

      def senderId = Some(_userId)
      def recipientId = Some(_bItemId)

      // edge like
      val sourceId = _aItemId
      val sinkId = _bItemId
      val graphId = Some(_userId)
      val edgeKind = _kind
      val isBidirectional = false

      def noSpamCheck = admin
    }

  def messageLike(
    _id: Long,
    _userId: Long,
    _toUserId: Long,
    _body: String,
    _kind: String,
    admMsgOrTrusted: Boolean = false) =
    new AbstractMessageLike {
      def id = Some(_id)
      val body = Some(_body)
      val createdAt = Some(new Date)
      override def kind = _kind
      action = UserEvent.Create

      def recipientId = Some(_toUserId)
      def senderId = Some(_userId)
      def content = _body

      val public: Option[Boolean] = None

      def noSpamCheck = admMsgOrTrusted
    }

  def classifier(
    predictSays: (Int,Double)) =
    new Classifier {
      def predict(str: String): (Int,Double) = predictSays
    }

  def tokenizer(
    distSays: Double,
    featurizeSays: List[Long],
    fingerprintSays: List[Int]) =
    new Tokenizer {

      def fingerprint(str: String): List[Int] = fingerprintSays
      def dist(set1: List[Int], set2: List[Int]): Double = distSays
      def featurize(str: String): List[Long] = featurizeSays
    }

  /*
   * fixtures
   *
   */
  val commentUnfingerprintable = messageLike(75023212, 1, 2, "!\n", "Comment")
  val messageOutOfDict = messageLike(
    100, 1, 2, "ceklwcmjr3nbrie xmkdox3oxm3exm", "Message")
  val commentEnriched = messageLike(
    55023212, 1, 1, "@CHECK THIS SITE freeloopsandsamples.blogspot.com\n", "Comment")
  val commentCreated = messageLike(
    55023212, 1, 3, "CHECK THIS SITE freeloopsandsamples.blogspot.com\n", "Comment")
  val messageEnriched = messageLike(
    100, 1, 2, "buy viagra cheap viagra at http://spammer.com/viagra", "Message")
  val commentTrusted = messageLike(
    55023212, 1, 3, "CHECK THIS SITE freeloopsandsamples.blogspot.com\n", "Comment", true)
  val commentJunk = messageLike(
    55023212, 1, 3, "viagra http://viagra.com buy VIAGRA NOW\n", "Comment")
  val messageJunk = messageLike(
    100, 1, 2, "buy viagra cheap viagra at http://spammer.com/viagra", "Message")
  val messageShort = messageLike(
    100, 1, 2, "hi", "Message")
}

