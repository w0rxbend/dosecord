package dosecord.core.chat

import dosecord.contracts.Inline
import dosecord.contracts.Markup
import dosecord.contracts.Node
import dosecord.contracts.RichText
import dosecord.contracts.TimeStyle

import java.time.format.DateTimeFormatter

/** Pure RichText -> vendor-markup lowering with per-vendor escaping (DESIGN.md section 4.2: the AST never becomes a
  * vendor string inside the core; the renderer owns the translation).
  */
object VendorMarkup:

  private val TimeFormat = DateTimeFormatter.ofPattern("HH:mm")
  private val DateTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

  def render(text: RichText, markup: Markup, discreetNames: List[String] = Nil): String =
    text.map(renderNode(_, markup, discreetNames)).mkString("\n\n")

  def renderNode(node: Node, markup: Markup, discreetNames: List[String]): String =
    node match
      case Node.Paragraph(inlines) => inlines.map(renderInline(_, markup, discreetNames)).mkString
      case Node.BulletList(items)  =>
        items.map(item => "- " + item.map(renderInline(_, markup, discreetNames)).mkString).mkString("\n")
      case Node.CodeBlock(s) =>
        markup match
          case Markup.Plain                            => s
          case Markup.DiscordMd | Markup.ZulipMd       => s"```\n$s\n```"
          case Markup.TelegramHtml | Markup.MatrixHtml =>
            s"<pre><code>${escapeHtml(s)}</code></pre>"

  def renderInline(inline: Inline, markup: Markup, discreetNames: List[String]): String =
    inline match
      case Inline.Text(s) => wrapNames(escapeText(s, markup), markup, discreetNames)
      case Inline.Bold(s) =>
        markup match
          case Markup.Plain                            => wrapNames(s, markup, discreetNames)
          case Markup.DiscordMd | Markup.ZulipMd       => s"**${wrapNames(escapeMd(s), markup, discreetNames)}**"
          case Markup.TelegramHtml | Markup.MatrixHtml =>
            s"<b>${wrapNames(escapeHtml(s), markup, discreetNames)}</b>"
      case Inline.Italic(s) =>
        markup match
          case Markup.Plain                            => wrapNames(s, markup, discreetNames)
          case Markup.DiscordMd | Markup.ZulipMd       => s"*${wrapNames(escapeMd(s), markup, discreetNames)}*"
          case Markup.TelegramHtml | Markup.MatrixHtml =>
            s"<i>${wrapNames(escapeHtml(s), markup, discreetNames)}</i>"
      case Inline.Code(s) =>
        markup match
          case Markup.Plain                            => s
          case Markup.DiscordMd | Markup.ZulipMd       => s"`${s.replace("`", "'")}`"
          case Markup.TelegramHtml | Markup.MatrixHtml => s"<code>${escapeHtml(s)}</code>"
      case Inline.Link(label, url) =>
        markup match
          case Markup.Plain                            => s"$label ($url)"
          case Markup.DiscordMd | Markup.ZulipMd       => s"[${escapeMd(label)}]($url)"
          case Markup.TelegramHtml | Markup.MatrixHtml =>
            s"""<a href="${escapeHtml(url)}">${escapeHtml(label)}</a>"""
      case Inline.LineBreak           => "\n"
      case Inline.Emoji(name)         => s":$name:"
      case Inline.Time(at, tz, style) =>
        markup match
          case Markup.DiscordMd =>
            val flag = style match
              case TimeStyle.Time     => "t"
              case TimeStyle.DateTime => "f"
              case TimeStyle.Relative => "R"
            s"<t:${at.getEpochSecond}:$flag>"
          case _ =>
            val local = at.atZone(tz)
            style match
              case TimeStyle.Time     => TimeFormat.format(local)
              case TimeStyle.DateTime => DateTimeFormat.format(local)
              case TimeStyle.Relative => DateTimeFormat.format(local)

  /** Discreet mode (DESIGN.md section 4.3): any medication name the core left in the body is wrapped in the vendor's
    * spoiler markup; vendors without an inline spoiler (Zulip, plain text) get the neutral replacement instead.
    */
  private def wrapNames(rendered: String, markup: Markup, names: List[String]): String =
    names.foldLeft(rendered) { (acc, name) =>
      val escaped = escapeText(name, markup)
      if escaped.isEmpty || !acc.contains(escaped) then acc
      else
        val spoiler = markup match
          case Markup.DiscordMd    => s"||$escaped||"
          case Markup.TelegramHtml => s"<tg-spoiler>$escaped</tg-spoiler>"
          case Markup.MatrixHtml   => s"<span data-mx-spoiler>$escaped</span>"
          case Markup.ZulipMd      => "your dose"
          case Markup.Plain        => "your dose"
        acc.replace(escaped, spoiler)
    }

  private def escapeText(s: String, markup: Markup): String =
    markup match
      case Markup.TelegramHtml | Markup.MatrixHtml => escapeHtml(s)
      case Markup.DiscordMd | Markup.ZulipMd       => escapeMd(s)
      case Markup.Plain                            => s

  private def escapeHtml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

  private def escapeMd(s: String): String =
    s.flatMap { c =>
      if "\\*_~`|>".contains(c) then s"\\$c" else c.toString
    }

  /** Split rendered text at paragraph boundaries (`\n\n`); blocks attach to the last chunk (DESIGN.md section 4.3). A
    * paragraph longer than `maxText` is hard-split at line, then character, boundaries.
    */
  def split(text: String, maxText: Int): List[String] =
    require(maxText > 0, "maxText must be positive")
    if text.length <= maxText then List(text)
    else
      val paragraphs = text.split("\n\n", -1).toList.flatMap { p =>
        if p.length <= maxText then List(p) else hardSplit(p, maxText)
      }
      paragraphs
        .foldLeft(List.empty[String]) { (chunks, p) =>
          chunks match
            case Nil => List(p)
            case _   =>
              val last = chunks.last
              val candidate = last + "\n\n" + p
              if candidate.length <= maxText then chunks.dropRight(1) :+ candidate
              else chunks :+ p
        }
        .filter(_.nonEmpty)

  private def hardSplit(paragraph: String, maxText: Int): List[String] =
    val lines = paragraph.split("\n", -1).toList
    val byLine = lines.foldLeft(List.empty[String]) { (chunks, line) =>
      chunks match
        case Nil => List(line)
        case _   =>
          val last = chunks.last
          if (last + "\n" + line).length <= maxText then chunks.dropRight(1) :+ (last + "\n" + line)
          else chunks :+ line
    }
    byLine.flatMap { chunk =>
      if chunk.length <= maxText then List(chunk) else chunk.grouped(maxText).toList
    }
end VendorMarkup
