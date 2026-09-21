package dosecord.contracts

/** Serialisation for the `conversation_sessions`, `form_runs` and `callback_slots` JSON documents (M0.12b). Lives in
  * contracts so the core stays free of a JSON library (M0.9 convention).
  */
object WizardDocument:

  /** Session `data`: flow answers keyed by field, plus engine-reserved `_`-prefixed keys (history, grace marker). */
  def dataToJson(data: Map[String, String]): String = upickle.default.write(data)
  def dataFromJson(json: String): Map[String, String] = upickle.default.read[Map[String, String]](json)

  /** FormRunner `answers`: collected field answers keyed by field key. */
  def answersToJson(answers: Map[String, String]): String = upickle.default.write(answers)
  def answersFromJson(json: String): Map[String, String] = upickle.default.read[Map[String, String]](json)

  /** `callback_slots.chat`: the owning ChatRef. */
  def chatToJson(chat: ChatRef): String = upickle.default.write(chat)

  /** `callback_slots.payload`: one semantic key the engine dispatch matches on. */
  def slotPayloadToJson(key: String): String = upickle.default.write(Map("k" -> key))
  def slotPayloadKey(json: String): String =
    val payload = upickle.default.read[Map[String, String]](json)
    payload("k")
