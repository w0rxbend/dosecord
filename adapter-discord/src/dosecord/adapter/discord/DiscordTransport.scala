package dosecord.adapter.discord

import dosecord.contracts.LifecycleState

import java.time.Instant

/** A live Discord interaction at the transport boundary. The adapter's `InteractionHandle` (contracts) is built on top
  * of this; every method blocks (the adapter runs on virtual threads) and raises [[DiscordApiException]] on vendor
  * failures. The message-producing ops return the vendor message id so the handle can mint a `MessageHandle`.
  */
trait DiscordInteraction:
  def id: String
  def createdAt: Instant
  def user: DiscordUser
  def channelId: String
  def context: DiscordContext
  def deferReply(ephemeral: Boolean): Unit
  def deferUpdate(): Unit
  def reply(message: DiscordMessage, ephemeral: Boolean): String
  def editOriginal(message: DiscordMessage): String
  def editSourceMessage(message: DiscordMessage): String
  def replyModal(modal: DiscordModal): Unit
  def replyChoices(choices: List[String]): Unit

/** Every JDA event the adapter consumes, already normalized: the JDA listener hands these over without blocking the
  * gateway thread (the adapter forks each one into its Ox scope, ROADMAP M2.1). Interactions carry their live
  * [[DiscordInteraction]]; plain DM messages and lifecycle transitions do not.
  */
enum DiscordEvent:
  case SlashCommand(interaction: DiscordInteraction, name: String, options: List[DiscordOption])
  case Component(interaction: DiscordInteraction, customId: String, values: List[String], sourceMessageId: String)
  case ModalSubmit(interaction: DiscordInteraction, customId: String, fields: Map[String, String])
  case Autocomplete(interaction: DiscordInteraction, command: String, focusedOption: String, query: String)
  case Message(
      messageId: String,
      createdAt: Instant,
      author: DiscordUser,
      channelId: String,
      text: String,
      replyToMessageId: Option[String]
  )
  case Lifecycle(state: LifecycleState, fromCursor: Option[String])

/** The gateway/REST boundary (ROADMAP M2.1: the inbound-event mapping layer is testable without the gateway — suite A
  * runs the whole adapter against a fake transport). `connect` blocks until the gateway is READY and then returns;
  * `awaitDisconnect` parks the adapter's gateway thread until `disconnect`. Events are delivered on the gateway's own
  * thread and must be handed off immediately.
  */
trait DiscordTransport:
  def connect(handler: DiscordEvent => Unit): Unit
  def awaitDisconnect(): Unit
  def disconnect(): Unit
  def selfUser: DiscordUser

  def sendMessage(channelId: String, message: DiscordMessage, nonce: Option[String]): String
  def editMessage(channelId: String, messageId: String, message: DiscordMessage): Unit
  def deleteMessage(channelId: String, messageId: String): Unit
  def addReaction(channelId: String, messageId: String, emoji: String): Unit
  def removeReaction(channelId: String, messageId: String, emoji: String): Unit

  /** Global slash registration (upsert); the plan's install types and contexts come from [[DiscordRegistration]]. */
  def registerCommands(commands: List[DiscordCommand]): Unit

  /** Opens (or reuses) the bot DM with the user and returns the channel id (the `dm_channel_id` cache's cold path). */
  def openPrivateChannel(userId: String): String
