/*
 * Copyright 2018 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair

import com.codahale.metrics._
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem, Props, SupervisorStrategy}
import akka.http.scaladsl.Http
import akka.pattern.after
import akka.stream.{ActorMaterializer, BindFailedException}
import akka.util.Timeout
import com.softwaremill.sttp.okhttp.OkHttpFutureBackend
import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.bitcoin.{BinaryData, Block}
import fr.acinq.eclair.NodeParams.{BITCOIND, ELECTRUM}
import fr.acinq.eclair.api.{GetInfoResponse, Service}
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BasicBitcoinJsonRPCClient, BatchingBitcoinJsonRPCClient, ExtendedBitcoinClient}
import fr.acinq.eclair.blockchain.bitcoind.zmq.ZMQActor
import fr.acinq.eclair.blockchain.bitcoind.{BitcoinCoreWallet, ZmqWatcher}
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.SSL
import fr.acinq.eclair.blockchain.electrum.ElectrumClientPool.ElectrumServerAddress
import fr.acinq.eclair.blockchain.electrum._
import fr.acinq.eclair.blockchain.fee.{ConstantFeeProvider, _}
import fr.acinq.eclair.blockchain.{EclairWallet, _}
import fr.acinq.eclair.channel.Register
import fr.acinq.eclair.crypto.LocalKeyManager
import fr.acinq.eclair.io.{Authenticator, Server, Switchboard}
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router._
import grizzled.slf4j.Logging
import org.json4s.JsonAST.JArray

import scala.concurrent.duration._
import scala.concurrent._

/**
  * Setup eclair from a data directory.
  *
  * Created by PM on 25/01/2016.
  *
  * @param datadir  directory where eclair-core will write/read its data.
  * @param overrideDefaults use this parameter to programmatically override the node configuration .
  * @param seed_opt optional seed, if set eclair will use it instead of generating one and won't create a seed.dat file.
  */
class Setup(datadir: File,
            overrideDefaults: Config = ConfigFactory.empty(),
            seed_opt: Option[BinaryData] = None)(implicit system: ActorSystem) extends Logging {

  logger.info(s"hello!")
  logger.info(s"version=${getClass.getPackage.getImplementationVersion} commit=${getClass.getPackage.getSpecificationVersion}")
  logger.info(s"datadir=${datadir.getCanonicalPath}")


  val config = NodeParams.loadConfiguration(datadir, overrideDefaults)
  val seed = seed_opt.getOrElse(NodeParams.getSeed(datadir))
  val chain = config.getString("chain")
  val keyManager = new LocalKeyManager(seed, NodeParams.makeChainHash(chain))
  val nodeParams = NodeParams.makeNodeParams(datadir, config, keyManager)

  // early checks
  DBCompatChecker.checkDBCompatibility(nodeParams)
  DBCompatChecker.checkNetworkDBCompatibility(nodeParams)
  PortChecker.checkAvailable(config.getString("server.binding-ip"), config.getInt("server.port"))

  logger.info(s"nodeid=${nodeParams.nodeId} alias=${nodeParams.alias}")
  logger.info(s"using chain=$chain chainHash=${nodeParams.chainHash}")

  logger.info(s"initializing secure random generator")
  // this will force the secure random instance to initialize itself right now, making sure it doesn't hang later (see comment in package.scala)
  secureRandom.nextInt()

  Slf4jReporter
    .forRegistry(nodeParams.metrics)
    .outputTo(LoggerFactory.getLogger("fr.acinq.eclair.metrics"))
    .convertRatesTo(TimeUnit.SECONDS)
    .convertDurationsTo(TimeUnit.MILLISECONDS)
    .filter(MetricFilter.startsWith("router"))
    .build
    .start(1, TimeUnit.MINUTES)

  Slf4jReporter
    .forRegistry(nodeParams.metrics)
    .outputTo(LoggerFactory.getLogger("fr.acinq.eclair.metrics"))
    .convertRatesTo(TimeUnit.SECONDS)
    .convertDurationsTo(TimeUnit.MILLISECONDS)
    .filter(MetricFilter.startsWith("db"))
    .build
    .start(1, TimeUnit.MINUTES)

  Slf4jReporter
    .forRegistry(nodeParams.metrics)
    .outputTo(LoggerFactory.getLogger("fr.acinq.eclair.metrics"))
    .convertRatesTo(TimeUnit.SECONDS)
    .convertDurationsTo(TimeUnit.MILLISECONDS)
    .filter(MetricFilter.startsWith("peer"))
    .filter(new MetricFilter {
      override def matches(s: String, metric: Metric): Boolean = {
        metric match {
            // only display meters if they report significant values
          case m: Meter => m.getOneMinuteRate > 1 || m.getFiveMinuteRate > 1 || m.getFifteenMinuteRate > 1
          case _ => true
        }
      }
    })
    .build
    .start(1, TimeUnit.HOURS)

  implicit val ec = ExecutionContext.Implicits.global
  implicit val sttpBackend = OkHttpFutureBackend()

  val bitcoin = nodeParams.watcherType match {
    case BITCOIND =>
      val bitcoinClient = new BasicBitcoinJsonRPCClient(
        user = config.getString("bitcoind.rpcuser"),
        password = config.getString("bitcoind.rpcpassword"),
        host = config.getString("bitcoind.host"),
        port = config.getInt("bitcoind.rpcport"))
      implicit val timeout = Timeout(30 seconds)
      implicit val formats = org.json4s.DefaultFormats
      val future = for {
        json <- bitcoinClient.invoke("getblockchaininfo").recover { case _ => throw BitcoinRPCConnectionException }
        // Make sure wallet support is enabled in bitcoind.
        _ <- bitcoinClient.invoke("getbalance").recover { case _ => throw BitcoinWalletDisabledException }
        progress = (json \ "verificationprogress").extract[Double]
        blocks = (json \ "blocks").extract[Long]
        headers = (json \ "headers").extract[Long]
        chainHash <- bitcoinClient.invoke("getblockhash", 0).map(_.extract[String]).map(BinaryData(_)).map(x => BinaryData(x.reverse))
        bitcoinVersion <- bitcoinClient.invoke("getnetworkinfo").map(json => (json \ "version")).map(_.extract[Int])
        unspentAddresses <- bitcoinClient.invoke("listunspent").collect { case JArray(values) =>
          values
            .filter(value => (value \ "spendable").extract[Boolean])
            .map(value => (value \ "address").extract[String])
        }
      } yield (progress, chainHash, bitcoinVersion, unspentAddresses, blocks, headers)
      // blocking sanity checks
      val (progress, chainHash, bitcoinVersion, unspentAddresses, blocks, headers) = await(future, 30 seconds, "bicoind did not respond after 30 seconds")
      assert(bitcoinVersion >= 160300, "Eclair requires Bitcoin Core 0.16.3 or higher")
      assert(chainHash == nodeParams.chainHash, s"chainHash mismatch (conf=${nodeParams.chainHash} != bitcoind=$chainHash)")
      if (chainHash != Block.RegtestGenesisBlock.hash) {
        assert(unspentAddresses.forall(address => !isPay2PubkeyHash(address)), "Make sure that all your UTXOS are segwit UTXOS and not p2pkh (check out our README for more details)")
      }
      assert(progress > 0.999, s"bitcoind should be synchronized (progress=$progress")
      assert(headers - blocks <= 1, s"bitcoind should be synchronized (headers=$headers blocks=$blocks")
      // TODO: add a check on bitcoin version?

      Bitcoind(bitcoinClient)
    case ELECTRUM =>
      val addresses = config.hasPath("electrum") match {
        case true =>
          val host = config.getString("electrum.host")
          val port = config.getInt("electrum.port")
          val ssl = config.getString("electrum.ssl") match {
            case "off" => SSL.OFF
            case "loose" => SSL.LOOSE
            case _ => SSL.STRICT // strict mode is the default when we specify a custom electrum server, we don't want to be MITMed
          }
          val address = InetSocketAddress.createUnresolved(host, port)
          logger.info(s"override electrum default with server=$address ssl=$ssl")
          Set(ElectrumServerAddress(address, ssl))
        case false =>
          val (addressesFile, sslEnabled) = nodeParams.chainHash match {
            case Block.RegtestGenesisBlock.hash => ("/electrum/servers_regtest.json", false) // in regtest we connect in plaintext
            case Block.TestnetGenesisBlock.hash => ("/electrum/servers_testnet.json", true)
            case Block.LivenetGenesisBlock.hash => ("/electrum/servers_mainnet.json", true)
          }
          val stream = classOf[Setup].getResourceAsStream(addressesFile)
          ElectrumClientPool.readServerAddresses(stream, sslEnabled)
      }
      val electrumClient = system.actorOf(SimpleSupervisor.props(Props(new ElectrumClientPool(addresses)), "electrum-client", SupervisorStrategy.Resume))
      Electrum(electrumClient)
  }

  def bootstrap: Future[Kit] = {
    for {
      _ <- Future.successful(true)
      feeratesRetrieved = Promise[Boolean]()
      zmqBlockConnected = Promise[Boolean]()
      zmqTxConnected = Promise[Boolean]()
      tcpBound = Promise[Unit]()
      routerInitialized = Promise[Unit]()

      defaultFeerates = FeeratesPerKB(
        block_1 = config.getLong("default-feerates.delay-blocks.1"),
        blocks_2 = config.getLong("default-feerates.delay-blocks.2"),
        blocks_6 = config.getLong("default-feerates.delay-blocks.6"),
        blocks_12 = config.getLong("default-feerates.delay-blocks.12"),
        blocks_36 = config.getLong("default-feerates.delay-blocks.36"),
        blocks_72 = config.getLong("default-feerates.delay-blocks.72")
      )
      minFeeratePerByte = config.getLong("min-feerate")
      smoothFeerateWindow = config.getInt("smooth-feerate-window")
      feeProvider = (nodeParams.chainHash, bitcoin) match {
        case (Block.RegtestGenesisBlock.hash, _) => new FallbackFeeProvider(new ConstantFeeProvider(defaultFeerates) :: Nil, minFeeratePerByte)
        case (_, Bitcoind(bitcoinClient)) =>
            new FallbackFeeProvider(new SmoothFeeProvider(new BitgoFeeProvider(nodeParams.chainHash), smoothFeerateWindow) :: new SmoothFeeProvider(new EarnDotComFeeProvider(), smoothFeerateWindow) :: new SmoothFeeProvider(new BitcoinCoreFeeProvider(bitcoinClient, defaultFeerates), smoothFeerateWindow) :: new ConstantFeeProvider(defaultFeerates) :: Nil, minFeeratePerByte) // order matters!
        case _ =>
          new FallbackFeeProvider(new SmoothFeeProvider(new BitgoFeeProvider(nodeParams.chainHash), smoothFeerateWindow) :: new SmoothFeeProvider(new EarnDotComFeeProvider(), smoothFeerateWindow) :: new ConstantFeeProvider(defaultFeerates) :: Nil, minFeeratePerByte) // order matters!
      }
      _ = system.scheduler.schedule(0 seconds, 10 minutes)(feeProvider.getFeerates.map {
        case feerates: FeeratesPerKB =>
          Globals.feeratesPerKB.set(feerates)
          Globals.feeratesPerKw.set(FeeratesPerKw(feerates))
          system.eventStream.publish(CurrentFeerates(Globals.feeratesPerKw.get))
          logger.info(s"current feeratesPerKB=${Globals.feeratesPerKB.get()} feeratesPerKw=${Globals.feeratesPerKw.get()}")
          feeratesRetrieved.trySuccess(true)
      })
      _ <- feeratesRetrieved.future

      watcher = bitcoin match {
        case Bitcoind(bitcoinClient) =>
          system.actorOf(SimpleSupervisor.props(Props(new ZMQActor(config.getString("bitcoind.zmqblock"), Some(zmqBlockConnected))), "zmqblock", SupervisorStrategy.Restart))
          system.actorOf(SimpleSupervisor.props(Props(new ZMQActor(config.getString("bitcoind.zmqtx"), Some(zmqTxConnected))), "zmqtx", SupervisorStrategy.Restart))
          system.actorOf(SimpleSupervisor.props(ZmqWatcher.props(new ExtendedBitcoinClient(new BatchingBitcoinJsonRPCClient(bitcoinClient))), "watcher", SupervisorStrategy.Resume))
        case Electrum(electrumClient) =>
          zmqBlockConnected.success(true)
          zmqTxConnected.success(true)
          system.actorOf(SimpleSupervisor.props(Props(new ElectrumWatcher(electrumClient)), "watcher", SupervisorStrategy.Resume))
      }

      router = system.actorOf(SimpleSupervisor.props(Router.props(nodeParams, watcher, Some(routerInitialized)), "router", SupervisorStrategy.Resume))
      routerTimeout = after(FiniteDuration(config.getDuration("router-init-timeout").getSeconds, TimeUnit.SECONDS), using = system.scheduler)(Future.failed(new RuntimeException("Router initialization timed out")))
      _ <- Future.firstCompletedOf(routerInitialized.future :: routerTimeout :: Nil)

      wallet = bitcoin match {
        case Bitcoind(bitcoinClient) => new BitcoinCoreWallet(bitcoinClient)
        case Electrum(electrumClient) =>
          val electrumWallet = system.actorOf(ElectrumWallet.props(seed, electrumClient, ElectrumWallet.WalletParameters(nodeParams.chainHash)), "electrum-wallet")
          implicit val timeout = Timeout(30 seconds)
          new ElectrumEclairWallet(electrumWallet, nodeParams.chainHash)
      }
      _ = wallet.getFinalAddress.map {
        case address => logger.info(s"initial wallet address=$address")
      }

      audit = system.actorOf(SimpleSupervisor.props(Auditor.props(nodeParams), "auditor", SupervisorStrategy.Resume))
      paymentHandler = system.actorOf(SimpleSupervisor.props(config.getString("payment-handler") match {
        case "local" => LocalPaymentHandler.props(nodeParams)
        case "noop" => Props[NoopPaymentHandler]
      }, "payment-handler", SupervisorStrategy.Resume))
      register = system.actorOf(SimpleSupervisor.props(Props(new Register), "register", SupervisorStrategy.Resume))
      relayer = system.actorOf(SimpleSupervisor.props(Relayer.props(nodeParams, register, paymentHandler), "relayer", SupervisorStrategy.Resume))
      authenticator = system.actorOf(SimpleSupervisor.props(Authenticator.props(nodeParams), "authenticator", SupervisorStrategy.Resume))
      switchboard = system.actorOf(SimpleSupervisor.props(Switchboard.props(nodeParams, authenticator, watcher, router, relayer, wallet), "switchboard", SupervisorStrategy.Resume))
      server = system.actorOf(SimpleSupervisor.props(Server.props(nodeParams, authenticator, new InetSocketAddress(config.getString("server.binding-ip"), config.getInt("server.port")), Some(tcpBound)), "server", SupervisorStrategy.Restart))
      paymentInitiator = system.actorOf(SimpleSupervisor.props(PaymentInitiator.props(nodeParams.nodeId, router, register), "payment-initiator", SupervisorStrategy.Restart))

      kit = Kit(
        nodeParams = nodeParams,
        system = system,
        watcher = watcher,
        paymentHandler = paymentHandler,
        register = register,
        relayer = relayer,
        router = router,
        switchboard = switchboard,
        paymentInitiator = paymentInitiator,
        server = server,
        wallet = wallet)

      zmqBlockTimeout = after(5 seconds, using = system.scheduler)(Future.failed(BitcoinZMQConnectionTimeoutException))
      zmqTxTimeout = after(5 seconds, using = system.scheduler)(Future.failed(BitcoinZMQConnectionTimeoutException))
      tcpTimeout = after(5 seconds, using = system.scheduler)(Future.failed(TCPBindException(config.getInt("server.port"))))

      _ <- Future.firstCompletedOf(zmqBlockConnected.future :: zmqBlockTimeout :: Nil)
      _ <- Future.firstCompletedOf(zmqTxConnected.future :: zmqTxTimeout :: Nil)
      _ <- Future.firstCompletedOf(tcpBound.future :: tcpTimeout :: Nil)
      _ <- if (config.getBoolean("api.enabled")) {
        logger.info(s"json-rpc api enabled on port=${config.getInt("api.port")}")
        implicit val materializer = ActorMaterializer()
        val api = new Service {

          override def scheduler = system.scheduler

          override val password = {
            val p = config.getString("api.password")
            if (p.isEmpty) throw EmptyAPIPasswordException else p
          }

          override def getInfoResponse: Future[GetInfoResponse] = Future.successful(
            GetInfoResponse(nodeId = nodeParams.nodeId,
              alias = nodeParams.alias,
              port = config.getInt("server.port"),
              chainHash = nodeParams.chainHash,
              blockHeight = Globals.blockCount.intValue()))

          override def appKit: Kit = kit

          override val socketHandler = makeSocketHandler(system)(materializer)
        }
        val httpBound = Http().bindAndHandle(api.route, config.getString("api.binding-ip"), config.getInt("api.port")).recover {
          case _: BindFailedException => throw TCPBindException(config.getInt("api.port"))
        }
        val httpTimeout = after(5 seconds, using = system.scheduler)(Future.failed(TCPBindException(config.getInt("api.port"))))
        Future.firstCompletedOf(httpBound :: httpTimeout :: Nil)
      } else {
        Future.successful(logger.info("json-rpc api is disabled"))
      }
    } yield kit

  }

  private def await[T](awaitable: Awaitable[T], atMost: Duration, messageOnTimeout: => String): T = try {
    Await.result(awaitable, atMost)
  } catch {
    case e: TimeoutException =>
      logger.error(messageOnTimeout)
      throw e
  }

}

// @formatter:off
sealed trait Bitcoin
case class Bitcoind(bitcoinClient: BasicBitcoinJsonRPCClient) extends Bitcoin
case class Electrum(electrumClient: ActorRef) extends Bitcoin
// @formatter:on

case class Kit(nodeParams: NodeParams,
               system: ActorSystem,
               watcher: ActorRef,
               paymentHandler: ActorRef,
               register: ActorRef,
               relayer: ActorRef,
               router: ActorRef,
               switchboard: ActorRef,
               paymentInitiator: ActorRef,
               server: ActorRef,
               wallet: EclairWallet)

case object BitcoinZMQConnectionTimeoutException extends RuntimeException("could not connect to bitcoind using zeromq")

case object BitcoinRPCConnectionException extends RuntimeException("could not connect to bitcoind using json-rpc")

case object BitcoinWalletDisabledException extends RuntimeException("bitcoind must have wallet support enabled")

case object EmptyAPIPasswordException extends RuntimeException("must set a password for the json-rpc api")

case object IncompatibleDBException extends RuntimeException("database is not compatible with this version of eclair")

case object IncompatibleNetworkDBException extends RuntimeException("network database is not compatible with this version of eclair")
