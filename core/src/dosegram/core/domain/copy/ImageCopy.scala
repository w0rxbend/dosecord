package dosegram.core.domain.copy

/** The words around an image that is sent as its text (VISUALS VA-FB-01, VA-FB-03). */
object ImageCopy:

  /** The text equivalent in place of an image, as the adapters write it (ADR-015 CON-02). */
  def inPlace(altText: String): String = s"[image: $altText]"

  /** VA-FB-03: the account had its images of the day; `time` is the local time the next one can be drawn. */
  def dailyLimit(limit: Int, time: String): String =
    s"Images are limited to $limit a day, so this one is sent as text. The next image can be drawn after $time."

  val entries: List[CopyEntry] =
    List(
      CopyEntry("image.in_place", inPlace("Water today: 1.2 of 2.0 l (60%)")),
      CopyEntry("image.daily_limit", dailyLimit(20, "14:05"))
    )
