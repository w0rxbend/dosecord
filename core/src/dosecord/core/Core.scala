package dosecord.core

import ox.Fork
import ox.Ox
import ox.fork

object Core:
  def nop(using Ox): Fork[Unit] = fork(())
