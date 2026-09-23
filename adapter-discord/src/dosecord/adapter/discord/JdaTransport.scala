package dosecord.adapter.discord

import dosecord.contracts.LifecycleState
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.label.Label
import net.dv8tion.jda.api.components.selections.SelectOption
import net.dv8tion.jda.api.components.selections.StringSelectMenu
import net.dv8tion.jda.api.components.textinput.TextInput
import net.dv8tion.jda.api.components.textinput.TextInputStyle
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.events.interaction.GenericInteractionCreateEvent
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.events.session.SessionDisconnectEvent
import net.dv8tion.jda.api.events.session.SessionRecreateEvent
import net.dv8tion.jda.api.events.session.SessionResumeEvent
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.InteractionContextType
import net.dv8tion.jda.api.interactions.callbacks.IAutoCompleteCallback
import net.dv8tion.jda.api.interactions.callbacks.IDeferrableCallback
import net.dv8tion.jda.api.interactions.callbacks.IMessageEditCallback
import net.dv8tion.jda.api.interactions.callbacks.IModalCallback
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback
import net.dv8tion.jda.api.interactions.commands.Command
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.modals.Modal

import java.time.Instant
import java.util.concurrent.CountDownLatch
import scala.compiletime.uninitialized
import scala.jdk.CollectionConverters.*

/** The real JDA 6.6 gateway/REST boundary (ROADMAP M2.1; the only file that touches `net.dv8tion`). The listener hands
  * every event straight to the adapter's handler — it returns immediately and never blocks a JDA thread (the adapter
  * forks each event into its Ox scope). Login uses JDA's default intents: no message-content intent, nothing privileged
  * (M0.8); DM interactions and DM message content need no further intent.
  *
  * All REST calls use `complete()` (the adapter blocks on virtual threads by contract) and translate JDA's
  * `ErrorResponseException` into [[DiscordApiException]] for [[DiscordErrorMapper]] — 50007 included, so a closed or
  * blocked DM surfaces as `Unreachable`.
  */
final class JdaTransport(token: String) extends DiscordTransport:

  private val shutdownLatch = new CountDownLatch(1)
  @volatile private var jda: JDA = uninitialized
  @volatile private var handler: DiscordEvent => Unit = uninitialized

  override def connect(handler: DiscordEvent => Unit): Unit =
    this.handler = handler
    // build() blocks until the gateway is READY; default intents (no message-content intent).
    val built = JDABuilder.createDefault(token).build()
    jda = built
    built.addEventListener(listener)

  override def awaitDisconnect(): Unit = shutdownLatch.await()

  override def disconnect(): Unit =
    shutdownLatch.countDown()
    val instance = jda
    if instance != null then instance.shutdown()

  override def selfUser: DiscordUser =
    val self = jda.getSelfUser
    DiscordUser(self.getId, self.getEffectiveName)

  // ---------- Outbound REST ----------

  override def sendMessage(channelId: String, message: DiscordMessage, nonce: Option[String]): String =
    jdaCall:
      val action = channel(channelId).sendMessage(message.text)
      if message.silent then action.setSuppressedNotifications(true)
      if message.suppressPreview then action.setSuppressEmbeds(true)
      nonce.foreach(action.setNonce)
      action.setComponents(JdaTransport.componentsOf(message).asJava).complete().getId

  override def editMessage(channelId: String, messageId: String, message: DiscordMessage): Unit =
    jdaCall:
      // `silent` is a create-only flag: an edit cannot change a message's notification suppression.
      channel(channelId)
        .editMessageById(messageId, message.text)
        .setComponents(JdaTransport.componentsOf(message).asJava)
        .complete()
      ()

  override def deleteMessage(channelId: String, messageId: String): Unit =
    jdaCall(channel(channelId).deleteMessageById(messageId).complete())

  override def addReaction(channelId: String, messageId: String, emoji: String): Unit =
    jdaCall(channel(channelId).addReactionById(messageId, Emoji.fromUnicode(emoji)).complete())

  override def removeReaction(channelId: String, messageId: String, emoji: String): Unit =
    jdaCall(channel(channelId).removeReactionById(messageId, Emoji.fromUnicode(emoji)).complete())

  override def registerCommands(commands: List[DiscordCommand]): Unit =
    jdaCall:
      jda.updateCommands().addCommands(commands.map(JdaTransport.toCommandData).asJava).complete()
      ()

  override def openPrivateChannel(userId: String): String =
    jdaCall(jda.openPrivateChannelById(userId).complete().getId)

  private def channel(channelId: String) =
    jda.getChannelById(classOf[net.dv8tion.jda.api.entities.channel.middleman.MessageChannel], channelId)

  // ---------- Inbound ----------

  /** A live JDA interaction behind the transport's [[DiscordInteraction]]; every op blocks the calling virtual thread
    * and translates `ErrorResponseException` into [[DiscordApiException]].
    */
  private final class JdaInteraction(event: GenericInteractionCreateEvent) extends DiscordInteraction:
    override def id: String = event.getId
    override def createdAt: Instant = Snowflake.createdAt(event.getId)
    override def user: DiscordUser = DiscordUser(event.getUser.getId, event.getUser.getEffectiveName)
    override def channelId: String = event.getChannel.getId
    override def context: DiscordContext = event.getContext match
      case InteractionContextType.BOT_DM          => DiscordContext.BotDm
      case InteractionContextType.PRIVATE_CHANNEL => DiscordContext.PrivateChannel
      case _                                      => DiscordContext.Guild

    override def deferReply(ephemeral: Boolean): Unit = event match
      case e: IReplyCallback =>
        jdaCall(e.deferReply().setEphemeral(ephemeral).complete())
      case _ => notReplyable("deferReply")

    override def deferUpdate(): Unit = event match
      case e: IMessageEditCallback =>
        jdaCall(e.deferEdit().complete())
      case _ => notEditable("deferUpdate")

    override def reply(message: DiscordMessage, ephemeral: Boolean): String = event match
      case e: IReplyCallback =>
        jdaCall:
          val action = e.reply(message.text).setEphemeral(ephemeral)
          if message.silent then action.setSuppressedNotifications(true)
          action.setComponents(JdaTransport.componentsOf(message).asJava).complete().getId
      case _ => notReplyable("reply")

    override def editOriginal(message: DiscordMessage): String = event match
      case e: IDeferrableCallback =>
        jdaCall:
          val action = e.getHook.editOriginal(message.text)
          action.setComponents(JdaTransport.componentsOf(message).asJava).complete().getId
      case _ => notEditable("editOriginal")

    override def editSourceMessage(message: DiscordMessage): String = event match
      case e: IMessageEditCallback =>
        jdaCall:
          e.editMessage(message.text).setComponents(JdaTransport.componentsOf(message).asJava).complete().getId
      case _ => notEditable("editSourceMessage")

    override def replyModal(modal: DiscordModal): Unit = event match
      case e: IModalCallback =>
        jdaCall(e.replyModal(JdaTransport.toModal(modal)).complete())
      case _ => throw IllegalStateException("replyModal on a modal-less interaction")

    override def replyChoices(choices: List[String]): Unit = event match
      case e: IAutoCompleteCallback =>
        jdaCall(e.replyChoices(choices.map(c => Command.Choice(c, c)).asJava).complete())
      case _ => throw IllegalStateException("replyChoices on a non-autocomplete interaction")

    private def notReplyable(op: String): Nothing =
      throw IllegalStateException(s"$op on a non-replyable interaction")

    private def notEditable(op: String): Nothing =
      throw IllegalStateException(s"$op on a non-editable interaction")
  end JdaInteraction

  /** Every transport op funnels through here: JDA's `ErrorResponseException` becomes [[DiscordApiException]] with the
    * numeric code and meaning (Discord 50007 included).
    */
  private def jdaCall[A](op: => A): A =
    try op
    catch
      case e: ErrorResponseException =>
        throw DiscordApiException(e.getErrorCode, None, Option(e.getMeaning).getOrElse(e.getErrorResponse.name()))

  private val listener = new ListenerAdapter:
    override def onSlashCommandInteraction(event: SlashCommandInteractionEvent): Unit =
      val options = event.getOptions.asScala.toList.map(o => DiscordOption(o.getName, o.getAsString))
      emit(DiscordEvent.SlashCommand(JdaInteraction(event), event.getName, options))

    override def onButtonInteraction(event: ButtonInteractionEvent): Unit =
      emit(DiscordEvent.Component(JdaInteraction(event), event.getCustomId, Nil, event.getMessageId))

    override def onStringSelectInteraction(event: StringSelectInteractionEvent): Unit =
      emit(
        DiscordEvent.Component(
          JdaInteraction(event),
          event.getCustomId,
          event.getValues.asScala.toList,
          event.getMessageId
        )
      )

    override def onModalInteraction(event: ModalInteractionEvent): Unit =
      val fields = event.getValues.asScala.toList.map(m => m.getCustomId -> m.getAsString).toMap
      emit(DiscordEvent.ModalSubmit(JdaInteraction(event), event.getModalId, fields))

    override def onCommandAutoCompleteInteraction(event: CommandAutoCompleteInteractionEvent): Unit =
      val focused = event.getFocusedOption
      emit(DiscordEvent.Autocomplete(JdaInteraction(event), event.getName, focused.getName, focused.getValue))

    override def onMessageReceived(event: MessageReceivedEvent): Unit =
      // DM text only (wizard free-text steps, FormRunner); the app is DM-only and never reads guild content.
      if !event.getAuthor.isBot && event.getChannel.getType == ChannelType.PRIVATE then
        val message = event.getMessage
        emit(
          DiscordEvent.Message(
            message.getId,
            message.getTimeCreated.toInstant,
            DiscordUser(message.getAuthor.getId, message.getAuthor.getEffectiveName),
            message.getChannel.getId,
            message.getContentRaw,
            Option(message.getReferencedMessage).map(_.getId)
          )
        )

    override def onReady(event: ReadyEvent): Unit =
      emit(DiscordEvent.Lifecycle(LifecycleState.Connected, None))

    override def onSessionResume(event: SessionResumeEvent): Unit =
      emit(DiscordEvent.Lifecycle(LifecycleState.Resumed, None))

    override def onSessionRecreate(event: SessionRecreateEvent): Unit =
      emit(DiscordEvent.Lifecycle(LifecycleState.Connected, None))

    override def onSessionDisconnect(event: SessionDisconnectEvent): Unit =
      emit(DiscordEvent.Lifecycle(LifecycleState.Disconnected, None))

  private def emit(event: DiscordEvent): Unit = handler(event)
end JdaTransport

/** The Discord-model -> JDA translation, in the companion so the registration fixture can assert on real `CommandData`
  * (ROADMAP M2.1: a guild invocation is impossible by registration) with no gateway.
  */
object JdaTransport:

  private[discord] def componentsOf(message: DiscordMessage): List[ActionRow] =
    message.components.map:
      case DiscordComponent.ButtonRow(buttons) =>
        ActionRow.of(buttons.map(buttonOf).asJava)
      case DiscordComponent.Select(customId, options, minSelect, maxSelect) =>
        ActionRow.of(
          StringSelectMenu
            .create(customId)
            .addOptions(options.map(o => SelectOption.of(o.label, o.value)).asJava)
            .setMinValues(minSelect)
            .setMaxValues(maxSelect)
            .build()
        )

  private[discord] def buttonOf(button: DiscordButton): Button =
    button.style match
      case DiscordButtonStyle.Primary   => Button.primary(button.customId, button.label)
      case DiscordButtonStyle.Secondary => Button.secondary(button.customId, button.label)
      case DiscordButtonStyle.Danger    => Button.danger(button.customId, button.label)
      case DiscordButtonStyle.Success   => Button.success(button.customId, button.label)

  private[discord] def toCommandData(
      command: DiscordCommand
  ): net.dv8tion.jda.api.interactions.commands.build.SlashCommandData =
    val data = Commands.slash(command.name, command.description)
    command.options.foreach: option =>
      val optionData = OptionData(
        if option.kind == DiscordOptionKind.Integer then OptionType.INTEGER else OptionType.STRING,
        option.name,
        option.description,
        option.required
      )
      if option.autocomplete then optionData.setAutoComplete(true)
      if option.choices.nonEmpty then
        option.kind match
          case DiscordOptionKind.Integer =>
            optionData.addChoices(option.choices.map(c => Command.Choice(c, c.toLong)).asJava)
          case DiscordOptionKind.String =>
            optionData.addChoices(option.choices.map(c => Command.Choice(c, c)).asJava)
      data.addOptions(optionData)
    data.setIntegrationTypes(
      command.integrationTypes
        .map {
          case DiscordIntegrationType.UserInstall  => net.dv8tion.jda.api.interactions.IntegrationType.USER_INSTALL
          case DiscordIntegrationType.GuildInstall => net.dv8tion.jda.api.interactions.IntegrationType.GUILD_INSTALL
        }
        .toList
        .asJava
    )
    data.setContexts(
      command.contexts
        .map {
          case DiscordContext.BotDm          => InteractionContextType.BOT_DM
          case DiscordContext.PrivateChannel => InteractionContextType.PRIVATE_CHANNEL
          case DiscordContext.Guild          => InteractionContextType.GUILD
        }
        .toList
        .asJava
    )
    data

  private[discord] def toModal(modal: DiscordModal): Modal =
    val builder = Modal.create(modal.customId, modal.title)
    modal.fields.foreach: field =>
      val input = TextInput.create(field.key, TextInputStyle.SHORT)
      field.placeholder.foreach(input.setPlaceholder)
      input.setRequired(field.required)
      field.value.foreach(input.setValue)
      builder.addComponents(Label.of(field.label, input.build()))
    builder.build()
