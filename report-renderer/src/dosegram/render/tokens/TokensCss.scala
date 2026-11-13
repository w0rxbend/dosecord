package dosegram.render.tokens

import dosegram.render.color.Rgb

/** Derives the Mini App stylesheet from the renderer tokens, so one file is the source of every colour (ROADMAP G4.1).
  * Light values sit on `:root`; dark values under `[data-scheme="dark"]`, which the Mini App sets from the Telegram
  * colour scheme.
  */
object TokensCss:

  def render: String =
    s"""/* Generated from dosegram.render.tokens.Tokens. Do not edit. */
       |:root {
       |${declarations(Tokens.Light)}
       |}
       |
       |[data-scheme="dark"] {
       |${declarations(Tokens.Dark)}
       |}
       |""".stripMargin

  private def declarations(tokens: Tokens): String =
    named(tokens).map((name, color) => s"  --dg-$name: ${color.hex};").mkString("\n")

  private def named(tokens: Tokens): Vector[(String, Rgb)] =
    Vector(
      "surface" -> tokens.surface,
      "panel" -> tokens.panel,
      "grid" -> tokens.grid,
      "ink" -> tokens.ink,
      "ink-2" -> tokens.ink2,
      "ink-3" -> tokens.ink3,
      "attention" -> tokens.attention,
      "spruce" -> tokens.spruce,
      "tide" -> tokens.tide,
      "rye" -> tokens.rye,
      "beet" -> tokens.beet,
      "dusk" -> tokens.dusk,
      "spruce-late" -> tokens.spruceLate
    ) ++ tokens.spruceHeat.zipWithIndex.map((color, index) => s"spruce-heat-${index + 1}" -> color)
