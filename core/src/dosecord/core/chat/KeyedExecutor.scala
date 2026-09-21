package dosecord.core.chat

import ox.Ox
import ox.channels.Channel
import ox.fork

/** The keyed executor of DESIGN.md section 4.6 step 2: `vendor:chatId` is striped over N single-consumer Ox channels,
  * so events of one chat run sequentially (per-chat order is the channel's FIFO) while chats run in parallel.
  */
private[chat] final class KeyedExecutor(stripes: Int)(using Ox):
  require(stripes > 0, "stripes must be positive")

  private val channels = Vector.fill(stripes)(Channel.unlimited[() => Unit])

  channels.foreach: channel =>
    fork:
      while true do channel.receive()()

  def submit(key: String)(task: => Unit): Unit =
    channels(math.floorMod(key.hashCode, stripes)).send(() => task)
