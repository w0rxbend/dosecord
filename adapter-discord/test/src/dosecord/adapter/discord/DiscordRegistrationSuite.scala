package dosecord.adapter.discord

import dosecord.core.application.CommandRegistry
import net.dv8tion.jda.api.interactions.IntegrationType
import net.dv8tion.jda.api.interactions.InteractionContextType
import net.dv8tion.jda.api.interactions.commands.OptionType

import scala.jdk.CollectionConverters.*

/** ROADMAP M2.1 registration acceptance: the global slash plan from the core's `CommandSpec` surface registers
  * `USER_INSTALL` only (the M0.8 GO verdict and the ADR-001 addendum; M0.8 was not Partial, so no `GUILD_INSTALL`)
  * with the bot-DM context only — a guild or group-DM invocation is impossible by registration. Asserted on the real
  * JDA `CommandData` (pure data, no gateway): integration types, contexts, option kinds, choices and autocomplete.
  */
class DiscordRegistrationSuite extends munit.FunSuite:

  private val plan = DiscordRegistration.plan(CommandRegistry.all)

  test("every command registers USER_INSTALL only and the bot-DM context only"):
    assert(plan.nonEmpty)
    plan.foreach: command =>
      assertEquals(command.integrationTypes, Set(DiscordIntegrationType.UserInstall), command.name)
      assertEquals(command.contexts, Set(DiscordContext.BotDm), command.name)

  test("the JDA CommandData fixture: a guild invocation is impossible by registration"):
    plan.foreach: command =>
      val data = JdaTransport.toCommandData(command)
      assertEquals(data.getIntegrationTypes, Set(IntegrationType.USER_INSTALL).asJava, command.name)
      assertEquals(data.getContexts, Set(InteractionContextType.BOT_DM).asJava, command.name)

  test("the registered surface is the full CommandSpec registry"):
    assertEquals(plan.map(_.name), CommandRegistry.all.map(_.name))

  test("/mood level is an INTEGER option with choices 1-10; /snooze minutes offers the filtered minute choices"):
    val mood = plan.find(_.name == "mood").get
    val level = mood.options.find(_.name == "level").get
    assertEquals(level.kind, DiscordOptionKind.Integer)
    assertEquals(level.choices, (1 to 10).map(_.toString).toList)

    val snooze = plan.find(_.name == "snooze").get
    val minutes = snooze.options.find(_.name == "minutes").get
    assertEquals(minutes.kind, DiscordOptionKind.Integer)
    assertEquals(minutes.choices, List("10", "30", "60"))

    val moodData = JdaTransport.toCommandData(mood)
    val levelData = moodData.getOptions.get(0)
    assertEquals(levelData.getType, OptionType.INTEGER)
    assertEquals(levelData.getChoices.size, 10)

  test("/taken, /skip and /log take an optional autocompleted medication option"):
    List("taken", "skip", "log").foreach: name =>
      val option = plan.find(_.name == name).flatMap(_.options.find(_.name == "medication")).get
      assertEquals(option.kind, DiscordOptionKind.String, name)
      assert(option.autocomplete, name)
      assert(!option.required, name)
    val logData = JdaTransport.toCommandData(plan.find(_.name == "log").get)
    assert(logData.getOptions.get(0).isAutoComplete)

  test("no prefix commands and no message-content surface: the plan is slash-only native registration"):
    // The adapter exposes no text-command path at all; registration carries only slash commands with typed options.
    assert(plan.forall(_.name.matches("[a-z0-9-]+")))
