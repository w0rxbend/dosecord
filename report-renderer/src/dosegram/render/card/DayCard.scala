package dosegram.render.card

import dosegram.render.blob.BlobFigure
import dosegram.render.blob.BlobFrame
import dosegram.render.chart.DoseGlyph
import dosegram.render.chart.DoseStatus
import dosegram.render.chart.Halo
import dosegram.render.chart.HaloFrame
import dosegram.render.chart.HaloSegment
import dosegram.render.chart.HaloState
import dosegram.render.chart.Icons
import dosegram.render.scene.Align
import dosegram.render.scene.Box
import dosegram.render.scene.Node
import dosegram.render.scene.Point
import dosegram.render.scene.TextKind
import dosegram.render.tokens.Domain
import dosegram.render.tokens.TextStyle

/** IMG-03, `card.day.v1`: the day blob and one line per tracked domain. The halo carries the day's status; the blob
  * shows the mood entry only and never reacts to another domain (VA-BL-01). Medication is one pooled line with counts
  * and status glyphs, so the card holds no medication name at any privacy level.
  */
object DayCard:
  val TemplateId = "card.day.v1"
  private val Center = Point(540, 410)
  private val BlobRadius = 190.0
  private val HaloRadius = 300.0
  private val LabelBelowPx = 58.0
  private val LabelToRowsPx = 30.0
  private val LinePx = 68.0
  private val RowGapPx = 12.0
  private val TextLeft = 144.0
  private val GlyphPx = DoseGlyph.MinimumSidePx
  private val GlyphGapPx = 12.0
  private val NoteLinePx = 48.0

  /** One domain line: the value at the end edge and what, if anything, sits on the second line. */
  final private case class Row(domain: Domain, value: String, halo: HaloState, detail: Detail):
    // A row without a record states that quietly: regular weight and secondary ink, not the weight of a value.
    def valueLook(frame: CardFrame): TextLook =
      if halo == HaloState.NothingRecorded then TextLook(TextStyle.Body, frame.tokens.ink2, Align.End, TextKind.Label)
      else TextLook(TextStyle.Value, frame.tokens.ink, Align.End, TextKind.Value)

  private enum Detail:
    case Nothing
    case Glyphs(statuses: Vector[DoseStatus])
    case Note(text: String)

    def height: Double =
      this match
        case Nothing   => 0
        case _: Glyphs => GlyphPx + GlyphGapPx
        case _: Note   => NoteLinePx

  def render(day: DayData, context: CardContext): CardOutput =
    val frame = CardFrame(context, CardFrame.Portrait, context.tokens.surface)
    val copy = context.copy
    val rows = Vector(medication(day, context), water(day, context), food(day, context), sleep(day, context)).flatten
    val haloFrame = HaloFrame(Center, HaloRadius, frame.background)
    val iconCenters = if rows.isEmpty then Vector.empty else Halo.iconCenters(rows.size, haloFrame)
    val pictureBottom = (iconCenters.map(_.y + Icons.SizePx / 2) :+ (Center.y + HaloRadius + Halo.StrokePx / 2)).max
    val labelBaseline = pictureBottom + LabelBelowPx
    val blobAt = BlobFrame(Center, BlobRadius)
    val blob = day.mood
      .map(reading => BlobFigure.of(reading.state, blobAt, frame.tokens))
      .getOrElse(BlobFigure.noEntry(blobAt, frame.tokens))
    val moodLabel = day.mood.map(copy.moodLabel).getOrElse(copy.noMoodEntry)
    val labelLook = TextLook(TextStyle.BodyMedium, frame.tokens.ink, Align.Middle, TextKind.Label)
    val nodes = frame.header(copy.todayTitle, copy.dayDate(day.date))
      ++ (if rows.isEmpty then Vector.empty
          else Halo.of(rows.map(row => HaloSegment(row.domain, row.halo)), haloFrame, frame.tokens))
      ++ rows.zip(iconCenters).map { (row, center) =>
        Icons.of(row.domain, center.moved(-Icons.SizePx / 2, -Icons.SizePx / 2), frame.tokens.hue(row.domain))
      }
      ++ Vector(blob, frame.text(moodLabel, Point(Center.x, labelBaseline), labelLook, frame.width))
      ++ drawRows(rows, labelBaseline + LabelToRowsPx, frame, context)
      :+ frame.footer
    val text = TextEquivalent.of(copy.dayText(day), copy.dayDiscreet, context)
    CardOutput(frame.scene(TemplateId, nodes), text, "dosegram-day.png", context.privacy == Privacy.Discreet)

  private def medication(day: DayData, context: CardContext): Option[Row] =
    day.medication.map { statuses =>
      val counts = DoseCounts.of(statuses)
      if counts.counted == 0 then Row(Domain.Medication, context.copy.noData, HaloState.NothingRecorded, Detail.Nothing)
      else
        val halo = HaloState.Progress(counts.taken.toDouble / counts.counted, counts.skipped.toDouble / counts.counted)
        Row(Domain.Medication, context.copy.takenOf(counts.taken, counts.counted), halo, Detail.Glyphs(statuses))
    }

  private def water(day: DayData, context: CardContext): Option[Row] =
    day.water.map { progress =>
      if progress.volumeMl == 0 then unrecorded(Domain.Water, context)
      else
        Row(Domain.Water, context.copy.waterFraction(progress), HaloState.Progress(progress.share, 0), Detail.Nothing)
    }

  private def food(day: DayData, context: CardContext): Option[Row] =
    day.food.map { meals =>
      if meals.logged == 0 then unrecorded(Domain.Food, context)
      else
        val value = context.copy.mealsOf(meals.logged, meals.planned)
        Row(Domain.Food, value, HaloState.Progress(meals.share, 0), Detail.Nothing)
    }

  private def sleep(day: DayData, context: CardContext): Option[Row] =
    day.sleep.map {
      case SleepEvidence.Recorded(minutes, goalMinutes) =>
        val share = if goalMinutes <= 0 then 0.0 else StrictMath.min(1.0, minutes.toDouble / goalMinutes)
        val note = Detail.Note(context.copy.fromYourCheckIns)
        Row(Domain.Sleep, context.copy.duration(minutes), HaloState.Progress(share, 0), note)
      case SleepEvidence.QuietWindowOnly =>
        Row(Domain.Sleep, context.copy.estimateOnly, HaloState.Estimate, Detail.Nothing)
      case SleepEvidence.NoRecord => unrecorded(Domain.Sleep, context)
    }

  private def unrecorded(domain: Domain, context: CardContext): Row =
    Row(domain, context.copy.nothingLoggedYet, HaloState.NothingRecorded, Detail.Nothing)

  private def drawRows(rows: Vector[Row], top: Double, frame: CardFrame, context: CardContext): Vector[Node] =
    val tops = rows.scanLeft(top)((y, row) => y + LinePx + row.detail.height + RowGapPx)
    rows.zip(tops).flatMap { (row, y) =>
      val nameLook = frame.look(TextStyle.BodyMedium, frame.tokens.ink)
      Vector(
        Icons.of(row.domain, Point(frame.left, y + 4), frame.tokens.hue(row.domain)),
        frame.text(context.copy.domainName(row.domain), Point(TextLeft, y + 48), nameLook, 300),
        frame.text(row.value, Point(frame.right, y + 50), row.valueLook(frame), frame.width - 340)
      ) ++ detail(row.detail, y + LinePx, frame)
    }

  private def detail(detail: Detail, top: Double, frame: CardFrame): Vector[Node] =
    detail match
      case Detail.Nothing    => Vector.empty
      case Detail.Note(text) =>
        Vector(
          frame.text(
            text,
            Point(frame.right, top + 30),
            TextLook(TextStyle.Label, frame.tokens.ink2, Align.End, TextKind.Label),
            frame.width
          )
        )
      case Detail.Glyphs(statuses) =>
        // The glyph line holds every dose of the day or none: a day with more doses than fit is stated by its counts.
        val pitch = GlyphPx + GlyphGapPx
        val capacity = ((frame.right - TextLeft + GlyphGapPx) / pitch).toInt
        if statuses.size > capacity then Vector.empty
        else
          statuses.zipWithIndex.map { (status, index) =>
            DoseGlyph.of(status, Box(TextLeft + index * pitch, top, GlyphPx, GlyphPx), frame.tokens)
          }
