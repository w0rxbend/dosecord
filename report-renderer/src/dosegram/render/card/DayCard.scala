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
import dosegram.render.scene.Size
import dosegram.render.scene.TextKind
import dosegram.render.tokens.Domain
import dosegram.render.tokens.TextStyle

/** IMG-03, `card.day.v1`: the day blob and one line per tracked domain. The halo carries the day's status; the blob
  * shows the mood entry only and never reacts to another domain (VA-BL-01). Medication is one pooled line with counts
  * and status glyphs, so the card holds no medication name at any privacy level.
  */
object DayCard:
  val TemplateId = "card.day.v1"
  // Up to this many domain lines the card collapses to the square canvas with a smaller picture; more stay portrait.
  private val CompactRowLimit = 2
  private val LabelBelowPx = 54.0
  private val LabelDescentPx = 12.0
  private val LabelToRowsPx = 28.0
  private val LinePx = 64.0
  private val RowGapPx = 12.0
  private val TextLeft = 144.0
  private val GlyphPx = DoseGlyph.MinimumSidePx
  private val GlyphGapPx = 12.0
  private val NoteLinePx = 48.0

  /** The picture of one canvas: the blob and halo radii, and the blob radius when there is no halo. The halo's top sits
    * on the body top, clear of the header.
    */
  final private case class Geometry(canvas: Size, blobRadius: Double, haloRadius: Double, loneBlobRadius: Double):
    def center: Point = Point(canvas.width / 2, CardFrame.BodyTop + haloRadius + Halo.StrokePx / 2)

  private val PortraitGeometry = Geometry(CardFrame.Portrait, 172, 272, 260)
  private val CompactGeometry = Geometry(CardFrame.Square, 116, 184, 200)

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

  /** Where the picture sits: the blob, the halo and its icons when a domain is tracked, and the mood label under them.
    * Without a halo the blob takes the room of the ring (VA-BL-05).
    */
  final private case class Picture(geometry: Geometry, halo: HaloFrame, segmentCount: Int):
    private def hasHalo: Boolean = segmentCount > 0
    def center: Point = halo.center
    def blobFrame: BlobFrame = BlobFrame(center, if hasHalo then geometry.blobRadius else geometry.loneBlobRadius)
    def iconCenters: Vector[Point] = if hasHalo then Halo.iconCenters(segmentCount, halo) else Vector.empty

    def top: Double =
      if hasHalo then center.y - halo.radius - Halo.StrokePx / 2 else center.y - geometry.loneBlobRadius

    def labelBaseline: Double =
      val ringBottom = center.y + halo.radius + Halo.StrokePx / 2
      val bottom =
        if hasHalo then (iconCenters.map(_.y + Icons.SizePx / 2) :+ ringBottom).max
        else center.y + geometry.loneBlobRadius * BlobFigure.GroundBottomShare
      bottom + LabelBelowPx

    def movedDown(distance: Double): Picture = copy(halo = halo.copy(center = center.moved(0, distance)))

  def render(day: DayData, context: CardContext): CardOutput =
    val copy = context.copy
    val rows = Vector(medication(day, context), water(day, context), food(day, context), sleep(day, context)).flatten
    val geometry = if rows.size <= CompactRowLimit then CompactGeometry else PortraitGeometry
    val frame = CardFrame(context, geometry.canvas, context.tokens.surface)
    // What the card leaves under its rows is shared above and below the picture and the rows, so the card does not end
    // in an empty band.
    val natural = Picture(geometry, HaloFrame(geometry.center, geometry.haloRadius, frame.background), rows.size)
    val belowLabel = if rows.isEmpty then LabelDescentPx else LabelToRowsPx + rows.map(rowHeight).sum
    val picture = natural.movedDown(frame.balance(natural.top, natural.labelBaseline + belowLabel))
    val blob = day.mood
      .map(reading => BlobFigure.of(reading.state, picture.blobFrame, frame.tokens))
      .getOrElse(BlobFigure.noEntry(picture.blobFrame, frame.tokens))
    val moodLabel = day.mood.map(copy.moodLabel).getOrElse(copy.noMoodEntry)
    val labelLook = TextLook(TextStyle.BodyMedium, frame.tokens.ink, Align.Middle, TextKind.Label)
    val halo =
      if rows.isEmpty then Vector.empty
      else Halo.of(rows.map(row => HaloSegment(row.domain, row.halo)), picture.halo, frame.tokens)
    val nodes = frame.header(copy.todayTitle, copy.dayDate(day.date))
      ++ halo
      ++ rows.zip(picture.iconCenters).map { (row, center) =>
        Icons.of(row.domain, center.moved(-Icons.SizePx / 2, -Icons.SizePx / 2), frame.tokens.hue(row.domain))
      }
      ++ Vector(blob, frame.text(moodLabel, Point(picture.center.x, picture.labelBaseline), labelLook, frame.width))
      ++ drawRows(rows, picture.labelBaseline + LabelToRowsPx, frame, context)
      :+ frame.footer
    val text = TextEquivalent.of(copy.dayText(day), copy.dayDiscreet, context)
    CardOutput(frame.scene(TemplateId, nodes), text, "dosegram-day.png", context.privacy == Privacy.Discreet)

  private def rowHeight(row: Row): Double = LinePx + row.detail.height + RowGapPx

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
    val tops = rows.scanLeft(top)((y, row) => y + rowHeight(row))
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
