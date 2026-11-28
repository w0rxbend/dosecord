package dosegram.app

import sttp.tapir.server.netty.NettyConfig

/** The Netty settings of every listener of the process: health, webhook and Mini App. A stopped process leaves the ends
  * of the kept-alive connections it closed waiting a minute in TIME_WAIT on its port, and Linux lets a new listener
  * bind that port only when those connections took SO_REUSEADDR too. They did not by default, so a start within that
  * minute failed with "Address already in use" and exited (verification of 2026-11-28, third round).
  */
private[app] object Listeners:
  val Default: NettyConfig = NettyConfig.default.socketConfig(NettyConfig.default.socketConfig.withReuseAddress)
