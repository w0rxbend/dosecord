package dosecord.contracts

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

import org.scalacheck.Arbitrary
import org.scalacheck.Gen

/** ScalaCheck generators for every contracts type. Kept deliberately conservative (short printable strings, valid
  * values) so the round-trip properties exercise shape, not the smart constructors' rejection paths — those are covered
  * by ValidationSuite.
  */
object Gens:
  def smallList[T](g: Gen[T], max: Int = 4): Gen[List[T]] = Gen.choose(0, max).flatMap(Gen.listOfN(_, g))
  def smallNonEmptyList[T](g: Gen[T], max: Int = 4): Gen[List[T]] = Gen.choose(1, max).flatMap(Gen.listOfN(_, g))
  def smallMap(max: Int = 3): Gen[Map[String, String]] =
    Gen.choose(0, max).flatMap(n => Gen.mapOfN(n, Gen.zip(genText, genText)))

  val genUuid: Gen[UUID] = Gen.uuid
  val genInstant: Gen[Instant] = Gen.choose(0L, 4102444800L).map(Instant.ofEpochSecond)
  val genDuration: Gen[Duration] = Gen.choose(0L, 100000L).map(Duration.ofSeconds)
  val genZoneId: Gen[ZoneId] = Gen.oneOf("UTC", "Europe/Kyiv", "America/New_York").map(ZoneId.of)
  val genLocalDate: Gen[LocalDate] = Gen.choose(0L, 30000L).map(LocalDate.ofEpochDay)
  val genText: Gen[String] = Gen.nonEmptyListOf(Gen.alphaNumChar).map(_.mkString).map(_.take(12))
  val genOptText: Gen[Option[String]] = Gen.option(genText)

  given Arbitrary[UUID] = Arbitrary(genUuid)
  given Arbitrary[Instant] = Arbitrary(genInstant)
  given Arbitrary[Duration] = Arbitrary(genDuration)
  given Arbitrary[ZoneId] = Arbitrary(genZoneId)
  given Arbitrary[LocalDate] = Arbitrary(genLocalDate)

  given Arbitrary[AccountId] = Arbitrary(genUuid.map(AccountId(_)))
  given Arbitrary[IdentityId] = Arbitrary(genUuid.map(IdentityId(_)))
  given Arbitrary[EventId] = Arbitrary(genUuid.map(EventId(_)))

  val genSource: Gen[Source] =
    Gen.nonEmptyListOf(Gen.alphaLowerChar).map(cs => Source.unsafe("dosecord." + cs.mkString.take(12)))
  given Arbitrary[Source] = Arbitrary(genSource)

  val genHandle: Gen[Handle] =
    for
      first <- Gen.alphaNumChar
      restLen <- Gen.choose(2, 20)
      rest <- Gen.listOfN(restLen, Gen.oneOf(Gen.alphaNumChar, Gen.const('.'), Gen.const('_'), Gen.const('-')))
    yield Handle.unsafe((first +: rest).mkString.take(80))
  given Arbitrary[Handle] = Arbitrary(genHandle)

  val genHhMm: Gen[HhMm] =
    for
      h <- Gen.choose(0, 23)
      m <- Gen.choose(0, 59)
    yield HhMm.unsafe(f"$h%02d:$m%02d")
  given Arbitrary[HhMm] = Arbitrary(genHhMm)

  val genMoodLevel: Gen[MoodLevel] = Gen.choose(MoodLevel.Min, MoodLevel.Max).map(MoodLevel.unsafe)
  given Arbitrary[MoodLevel] = Arbitrary(genMoodLevel)

  given Arbitrary[Weekday] = Arbitrary(Gen.oneOf(Weekday.values.toList))

  given Arbitrary[Actor] = Arbitrary(
    for
      vendor <- Gen.oneOf("discord", "telegram", "zulip", "matrix", "console")
      userId <- genText
      username <- genOptText
      account <- Gen.option(genUuid.map(AccountId(_)))
    yield Actor(vendor, userId, username, account)
  )

  given Arbitrary[ReminderPolicyData] = Arbitrary(
    for
      offset <- Gen.choose(-60, 60)
      snoozes <- smallNonEmptyList(Gen.choose(1, 720)).map(_.distinct.sorted)
      miss <- Gen.choose(1, 10080)
      max <- Gen.choose(1, 10)
      discreet <- Gen.oneOf(true, false)
    yield ReminderPolicyData(offset, snoozes, miss, max, discreet)
  )

  given Arbitrary[MedicationData] = Arbitrary(
    for
      name <- genText
      amount <- genOptText
      unit <- genOptText
      instructions <- genOptText
    yield MedicationData(name, amount, unit, instructions)
  )

  given Arbitrary[FixedTimeScheduleData] = Arbitrary(
    for
      tz <- Gen.oneOf("UTC", "Europe/Kyiv")
      days <- smallNonEmptyList(Gen.oneOf(Weekday.values.toList)).map(_.distinct)
      times <- smallNonEmptyList(genHhMm).map(_.distinct)
      start <- genLocalDate
      end <- Gen.option(genLocalDate.suchThat(!_.isBefore(start)))
    yield FixedTimeScheduleData(tz, days, times, start, end)
  )

  given Arbitrary[Command] = Arbitrary(
    Gen.oneOf(
      Gen.zip(genOptText, genOptText).map(Command.IdentityStartRequested.apply),
      Gen.zip(genHandle, genOptText, Gen.const("UTC")).map(Command.IdentitySignupRequested.apply),
      for
        med <- Arbitrary.arbitrary[MedicationData]
        schedule <- Arbitrary.arbitrary[FixedTimeScheduleData]
        policy <- Arbitrary.arbitrary[ReminderPolicyData]
      yield Command.MedicationScheduleCreateRequested(med, schedule, policy),
      for
        level <- genMoodLevel
        note <- genOptText
        tags <- smallList(genText)
      yield Command.MoodCheckinRecordRequested(level, note, tags),
      Gen.zip(genText, genOptText, genOptText).map(Command.MedicationIntakeMarkTakenRequested.apply),
      Gen.zip(genText, Gen.option(Gen.choose(1, 600)), genOptText).map(Command.HabitCheckinRecordRequested.apply)
    )
  )

  given Arbitrary[Event] = Arbitrary(
    Gen.oneOf(
      Gen
        .zip(genUuid, genUuid, Gen.const("discord"))
        .map((a, i, v) => Event.AccountCreated(AccountId(a), IdentityId(i), v)),
      Gen
        .zip(genUuid, genUuid, genUuid, Gen.const("telegram"))
        .map((l, a, i, v) => Event.PlatformLinked(l, AccountId(a), IdentityId(i), v)),
      Gen
        .zip(genUuid, genMoodLevel, genOptText, smallList(genText))
        .map((a, l, n, t) => Event.MoodCheckinRecorded(AccountId(a), l, n, t)),
      Gen
        .zip(genUuid, genUuid, genUuid, Gen.const("Europe/Kyiv"))
        .map((a, m, s, tz) => Event.ScheduleCreated(AccountId(a), m, s, tz)),
      Gen.zip(genUuid, genUuid, Gen.choose(1, 3)).map((a, o, s) => Event.DoseDue(AccountId(a), o, s)),
      Gen
        .zip(genUuid, genUuid, genInstant, Gen.oneOf(true, false))
        .map((a, o, t, l) => Event.IntakeTaken(AccountId(a), o, t, l)),
      Gen
        .zip(genUuid, genUuid, Gen.const("done"), genOptText)
        .map((a, h, s, v) => Event.HabitCheckinRecorded(AccountId(a), h, s, v))
    )
  )

  given [A](using arb: Arbitrary[A]): Arbitrary[Envelope[A]] = Arbitrary(
    for
      id <- genUuid
      tpe <- Gen.oneOf(Command.allTypes ++ Event.allTypes)
      source <- genSource
      time <- genInstant
      actor <- Arbitrary.arbitrary[Actor]
      data <- arb.arbitrary
      subject <- genOptText
      dataschema <- genOptText
      correlation <- genOptText
      causation <- genOptText
      traceparent <- genOptText
    yield Envelope(
      EventId(id),
      tpe,
      source,
      time,
      actor,
      data,
      subject,
      Envelope.SpecVersion,
      Envelope.DataContentType,
      dataschema,
      correlation,
      causation,
      traceparent
    )
  )

  // ---------- Chat model ----------

  given Arbitrary[PlatformIdentity] = Arbitrary(
    Gen.zip(Gen.const("discord"), genText, genOptText).map(PlatformIdentity.apply)
  )
  given Arbitrary[ChatRef] = Arbitrary(Gen.zip(Gen.const("discord"), genText, genOptText).map(ChatRef.apply))
  given Arbitrary[MessageHandle] = Arbitrary(
    Gen.zip(Gen.const("discord"), genText, genText, Gen.choose(0, 5)).map(MessageHandle.apply)
  )
  given Arbitrary[Principal] = Arbitrary(
    Gen
      .zip(genUuid, Gen.option(genUuid), Gen.oneOf(true, false))
      .map((i, a, l) => Principal(IdentityId(i), a.map(AccountId(_)), l))
  )
  given Arbitrary[LifecycleState] = Arbitrary(Gen.oneOf(LifecycleState.values.toList))

  private val CallbackCodecMax = (1L << 48) - 1
  private val genRawToken: Gen[String] =
    Gen.listOfN(46, Gen.oneOf(('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9') ++ Seq('-', '_'))).map(cs => s"dc:${cs.mkString}")
  given Arbitrary[CallbackRef] = Arbitrary(
    Gen
      .zip(Gen.choose(0, 0xffff), genUuid, Gen.choose(0L, CallbackCodecMax), Gen.oneOf(true, false), genRawToken)
      .map(
        CallbackRef.apply
      )
  )

  given Arbitrary[Inbound] = Arbitrary(
    Gen.oneOf(
      Gen
        .zip(genText, Gen.option(Arbitrary.arbitrary[MessageHandle]), Gen.oneOf(true, false))
        .map(
          Inbound.MessageReceived.apply
        ),
      Gen.zip(genText, smallMap(), genText).map(Inbound.CommandInvoked.apply),
      Gen
        .zip(
          Arbitrary.arbitrary[CallbackRef],
          smallList(genText),
          Gen.option(Arbitrary.arbitrary[MessageHandle])
        )
        .map(Inbound.InteractionSubmitted.apply),
      Gen
        .zip(genText, Arbitrary.arbitrary[CallbackRef], smallMap())
        .map(
          Inbound.FormSubmitted.apply
        ),
      Gen.zip(genText, Arbitrary.arbitrary[MessageHandle], Gen.oneOf(true, false)).map(Inbound.ReactionChanged.apply),
      Gen.const(Inbound.ConversationStarted),
      Gen.zip(genUuid, genUuid).map(Inbound.AccountLinkCompleted.apply),
      Gen.zip(Arbitrary.arbitrary[LifecycleState], genOptText).map(Inbound.AdapterLifecycle.apply)
    )
  )

  given Arbitrary[InboundEvent] = Arbitrary(
    for
      id <- genUuid
      vendor <- Gen.oneOf("discord", "telegram", "console")
      vendorEventId <- genText
      receivedAt <- genInstant
      createdAt <- Gen.option(genInstant)
      actor <- Arbitrary.arbitrary[PlatformIdentity]
      chat <- Arbitrary.arbitrary[ChatRef]
      cursor <- genOptText
      principal <- Gen.option(Arbitrary.arbitrary[Principal])
      body <- Arbitrary.arbitrary[Inbound]
    yield InboundEvent(EventId(id), vendor, vendorEventId, receivedAt, createdAt, actor, chat, cursor, principal, body)
  )

  given Arbitrary[TimeStyle] = Arbitrary(Gen.oneOf(TimeStyle.values.toList))
  given Arbitrary[Inline] = Arbitrary(
    Gen.oneOf(
      genText.map(Inline.Text.apply),
      genText.map(Inline.Bold.apply),
      genText.map(Inline.Italic.apply),
      genText.map(Inline.Code.apply),
      Gen.zip(genText, Gen.const("https://example.com")).map(Inline.Link.apply),
      Gen.const(Inline.LineBreak),
      genText.map(Inline.Emoji.apply),
      Gen.zip(genInstant, genZoneId, Arbitrary.arbitrary[TimeStyle]).map(Inline.Time.apply)
    )
  )
  given Arbitrary[Node] = Arbitrary(
    Gen.oneOf(
      smallNonEmptyList(Arbitrary.arbitrary[Inline]).map(Node.Paragraph.apply),
      smallNonEmptyList(smallNonEmptyList(Arbitrary.arbitrary[Inline])).map(Node.BulletList.apply),
      genText.map(Node.CodeBlock.apply)
    )
  )
  given Arbitrary[RichText] = Arbitrary(smallNonEmptyList(Arbitrary.arbitrary[Node]))

  given Arbitrary[ChoiceStyle] = Arbitrary(Gen.oneOf(ChoiceStyle.values.toList))
  given Arbitrary[ChoiceLayout] = Arbitrary(Gen.oneOf(ChoiceLayout.values.toList))
  given Arbitrary[Choice] = Arbitrary(
    Gen.zip(genText, genText, Arbitrary.arbitrary[ChoiceStyle], genOptText, smallList(genText)).map(Choice.apply)
  )
  given Arbitrary[ChoiceSet] = Arbitrary(
    for
      id <- genText
      prompt <- Gen.option(Arbitrary.arbitrary[RichText])
      choices <- smallNonEmptyList(Arbitrary.arbitrary[Choice], 25)
      layout <- Arbitrary.arbitrary[ChoiceLayout]
      maxSelect <- Gen.choose(1, choices.length)
      minSelect <- Gen.choose(0, maxSelect)
      ttl <- Gen.option(genDuration)
    yield ChoiceSet(id, prompt, choices, layout, minSelect, maxSelect, ttl)
  )
  given Arbitrary[FieldType] = Arbitrary(Gen.oneOf(FieldType.values.toList))
  given Arbitrary[Field] = Arbitrary(
    Gen
      .zip(
        genText,
        genText,
        Arbitrary.arbitrary[FieldType],
        Gen.oneOf(true, false),
        genOptText,
        smallList(Arbitrary.arbitrary[Choice], 3),
        genOptText
      )
      .map(Field.apply)
  )
  given Arbitrary[Form] = Arbitrary(
    for
      id <- genText
      title <- genText.map(_.take(45))
      fields <- smallNonEmptyList(Arbitrary.arbitrary[Field], 5)
      submit <- genText
    yield Form(id, title, fields, submit)
  )
  given Arbitrary[NoticeLevel] = Arbitrary(Gen.oneOf(NoticeLevel.values.toList))
  given Arbitrary[Notice] = Arbitrary(
    Gen.zip(Arbitrary.arbitrary[NoticeLevel], Arbitrary.arbitrary[RichText]).map(Notice.apply)
  )
  given Arbitrary[Block] = Arbitrary(
    Gen.oneOf(
      Arbitrary.arbitrary[ChoiceSet].map(Block.Choices.apply),
      Arbitrary.arbitrary[Form].map(Block.FormBlock.apply),
      Arbitrary.arbitrary[Notice].map(Block.NoticeBlock.apply)
    )
  )
  given Arbitrary[Visibility] = Arbitrary(Gen.oneOf(Visibility.values.toList))
  given Arbitrary[Importance] = Arbitrary(Gen.oneOf(Importance.values.toList))
  given Arbitrary[OutboundMessage] = Arbitrary(
    for
      body <- Arbitrary.arbitrary[RichText]
      blocks <- smallList(Arbitrary.arbitrary[Block], 3)
      visibility <- Arbitrary.arbitrary[Visibility]
      importance <- Arbitrary.arbitrary[Importance]
      replaces <- Gen.option(Arbitrary.arbitrary[MessageHandle])
      deleteAfter <- Gen.option(genDuration)
      discreet <- Gen.oneOf(true, false)
      silent <- Gen.oneOf(true, false)
      dedupe <- genText
      correlation <- genText
    yield OutboundMessage(
      body,
      blocks,
      visibility,
      importance,
      replaces,
      deleteAfter,
      discreet,
      silent,
      dedupe,
      correlation
    )
  )

  // ---------- Capability profile ----------

  given Arbitrary[EditCapability] = Arbitrary(
    Gen.oneOf(
      Gen.const(EditCapability.AnyAge),
      genDuration.map(EditCapability.Window.apply),
      Gen.const(EditCapability.NoEdit)
    )
  )
  given Arbitrary[DeleteCapability] = Arbitrary(
    Gen.oneOf(
      Gen.const(DeleteCapability.AnyAge),
      genDuration.map(DeleteCapability.Window.apply),
      Gen.const(DeleteCapability.NoDelete)
    )
  )
  given Arbitrary[DmInitiation] = Arbitrary(Gen.oneOf(DmInitiation.values.toList))
  given Arbitrary[Markup] = Arbitrary(Gen.oneOf(Markup.values.toList))
  given Arbitrary[CapabilityProfile] = Arbitrary(
    for
      nativeCommands <- Gen.oneOf(true, false)
      buttons <- Gen.oneOf(true, false)
      select <- Gen.oneOf(true, false)
      modal <- Gen.oneOf(true, false)
      ephemeral <- Gen.oneOf(true, false)
      editOwn <- Arbitrary.arbitrary[EditCapability]
      deleteOwn <- Arbitrary.arbitrary[DeleteCapability]
      botReactions <- Gen.oneOf(true, false)
      reactionEvents <- Gen.oneOf(true, false)
      transientAck <- Gen.oneOf(true, false)
      deferrable <- Gen.oneOf(true, false)
      ackDeadline <- Gen.option(genDuration)
      polls <- Gen.oneOf(true, false)
      silentDelivery <- Gen.oneOf(true, false)
      dm <- Arbitrary.arbitrary[DmInitiation]
      maxText <- Gen.choose(1, 10000)
      maxChoicesPerRow <- Gen.choose(0, 5)
      maxRows <- Gen.choose(0, 5)
      callbackBudget <- Gen.option(Gen.choose(1, 100))
      eventsBudget <- Gen.choose(1, 10)
      markup <- Arbitrary.arbitrary[Markup]
    yield CapabilityProfile(
      nativeCommands,
      buttons,
      select,
      modal,
      ephemeral,
      editOwn,
      deleteOwn,
      botReactions,
      reactionEvents,
      transientAck,
      deferrable,
      ackDeadline,
      polls,
      silentDelivery,
      dm,
      maxText,
      maxChoicesPerRow,
      maxRows,
      callbackBudget,
      eventsBudget,
      markup
    )
  )

  // ---------- Errors, rendered model, ops, commands ----------

  given Arbitrary[ChatError] = Arbitrary(
    Gen.oneOf(
      genText.map(ChatError.Retryable.apply),
      genDuration.map(ChatError.RateLimited.apply),
      genText.map(ChatError.Unreachable.apply),
      genText.map(ChatError.TooOld.apply),
      genText.map(ChatError.Unsupported.apply),
      Gen.zip(genText, Gen.oneOf(true, false)).map(ChatError.Permanent.apply)
    )
  )

  given Arbitrary[RenderedChoice] = Arbitrary(
    Gen.zip(Gen.choose(1, 25), genText, genText, Arbitrary.arbitrary[ChoiceStyle], genOptText).map(RenderedChoice.apply)
  )
  given Arbitrary[RenderedControls] = Arbitrary(
    Gen.oneOf(
      Gen.const(RenderedControls.NoControls),
      smallNonEmptyList(smallNonEmptyList(Arbitrary.arbitrary[RenderedChoice])).map(RenderedControls.Buttons.apply),
      Gen
        .zip(genText, smallNonEmptyList(Arbitrary.arbitrary[RenderedChoice]), Gen.choose(0, 1), Gen.choose(1, 25))
        .map(
          RenderedControls.SelectMenu.apply
        ),
      Gen
        .zip(genText, smallNonEmptyList(Arbitrary.arbitrary[RenderedChoice]), smallList(genText))
        .map(
          RenderedControls.Numbered.apply
        )
    )
  )
  given Arbitrary[ChoiceMapEntry] = Arbitrary(
    Gen.zip(genText, Gen.choose(1, 25), genText, genOptText, genText).map(ChoiceMapEntry.apply)
  )
  given Arbitrary[RenderedField] = Arbitrary(
    Gen
      .zip(genText, genText, Arbitrary.arbitrary[FieldType], Gen.oneOf(true, false), genOptText, smallList(genText),
        genOptText)
      .map(RenderedField.apply)
  )
  given Arbitrary[RenderedForm] = Arbitrary(
    Gen.zip(genText, genText, smallNonEmptyList(Arbitrary.arbitrary[RenderedField]), genText).map(RenderedForm.apply)
  )
  given Arbitrary[RenderedMessage] = Arbitrary(
    for
      chunks <- smallNonEmptyList(genText)
      controls <- smallList(Arbitrary.arbitrary[RenderedControls], 3)
      form <- Gen.option(Arbitrary.arbitrary[RenderedForm])
      ephemeral <- Gen.oneOf(true, false)
      deleteAfter <- Gen.option(genDuration)
      silent <- Gen.oneOf(true, false)
      suppressPreview <- Gen.oneOf(true, false)
      choiceMap <- smallList(Arbitrary.arbitrary[ChoiceMapEntry], 5)
    yield RenderedMessage(chunks, controls, form, ephemeral, deleteAfter, silent, suppressPreview, choiceMap)
  )
  given Arbitrary[RenderReport] = Arbitrary(
    Gen
      .zip(smallMap(), Gen.choose(1, 5), Gen.oneOf(true, false), smallList(genText))
      .map(RenderReport.apply)
  )
  given Arbitrary[CommandArg] = Arbitrary(
    Gen.zip(genText, genText, Gen.oneOf(true, false), smallList(genText), Gen.oneOf(true, false)).map(CommandArg.apply)
  )
  given Arbitrary[CommandSpec] = Arbitrary(
    Gen
      .zip(
        genText,
        genText,
        smallList(Arbitrary.arbitrary[CommandArg]),
        Arbitrary.arbitrary[Visibility],
        Gen.oneOf(true, false)
      )
      .map(CommandSpec.apply)
  )
  given Arbitrary[VendorOp] = Arbitrary(
    Gen.oneOf(
      Gen
        .zip(
          Arbitrary.arbitrary[ChatRef],
          Arbitrary.arbitrary[RenderedMessage],
          genText,
          Gen.option(Arbitrary.arbitrary[MessageHandle])
        )
        .map(VendorOp.Send.apply),
      Gen.zip(Arbitrary.arbitrary[MessageHandle], Arbitrary.arbitrary[RenderedMessage]).map(VendorOp.Edit.apply),
      Arbitrary.arbitrary[MessageHandle].map(VendorOp.Delete.apply),
      Gen.zip(Arbitrary.arbitrary[MessageHandle], genText, Gen.oneOf(true, false), genText).map(VendorOp.React.apply),
      Arbitrary.arbitrary[MessageHandle].map(VendorOp.UnreactAll.apply),
      Arbitrary.arbitrary[RenderedForm].map(VendorOp.OpenForm.apply),
      genOptText.map(VendorOp.Ack.apply),
      Arbitrary.arbitrary[ChatRef].map(VendorOp.Typing.apply),
      smallList(Arbitrary.arbitrary[CommandSpec]).map(VendorOp.RegisterCommands.apply)
    )
  )
  given Arbitrary[Target] = Arbitrary(
    Gen.oneOf(
      genUuid.map(u => Target.AccountTarget(AccountId(u))),
      Arbitrary.arbitrary[ChatRef].map(Target.ChatTarget.apply)
    )
  )
  given Arbitrary[SendResult] = Arbitrary(
    Gen.zip(Arbitrary.arbitrary[MessageHandle], Arbitrary.arbitrary[RenderReport]).map(SendResult.apply)
  )
end Gens
