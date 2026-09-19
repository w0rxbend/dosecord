package dosecord.contracts

import upickle.default.ReadWriter

final case class Envelope(id: String) derives ReadWriter
