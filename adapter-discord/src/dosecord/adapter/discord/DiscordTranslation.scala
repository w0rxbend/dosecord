package dosecord.adapter.discord

import dosecord.contracts.ChoiceStyle
import dosecord.contracts.RenderedControls
import dosecord.contracts.RenderedForm
import dosecord.contracts.RenderedMessage

/** Contracts -> Discord model lowering (ROADMAP M2.1). The renderer already degraded every block to this profile's
  * rungs, so buttons/selects map 1:1; a `Numbered` control set never reaches a buttons profile (its legend is in the
  * text and its reactions arrive as separate react ops, DESIGN.md section 4.3).
  */
private[discord] object DiscordTranslation:

  def toMessage(rendered: RenderedMessage): DiscordMessage =
    DiscordMessage(
      text = rendered.chunks.mkString("\n\n"),
      components = rendered.controls.flatMap(componentsOf),
      silent = rendered.silent,
      suppressPreview = rendered.suppressPreview
    )

  private def componentsOf(controls: RenderedControls): List[DiscordComponent] = controls match
    case RenderedControls.NoControls    => Nil
    case RenderedControls.Buttons(rows) =>
      rows.map(row =>
        DiscordComponent.ButtonRow(row.map(c => DiscordButton(c.label, c.callback, buttonStyle(c.style))))
      )
    case RenderedControls.SelectMenu(id, choices, minSelect, maxSelect) =>
      // Each option's value carries its own `dc:` token; the submit arrives with those values (the adapter reads the
      // callback from `values.head`).
      List(
        DiscordComponent.Select(id, choices.map(c => DiscordSelectOption(c.label, c.callback)), minSelect, maxSelect)
      )
    case RenderedControls.Numbered(_, _, _) => Nil

  private def buttonStyle(style: ChoiceStyle): DiscordButtonStyle = style match
    case ChoiceStyle.Primary   => DiscordButtonStyle.Primary
    case ChoiceStyle.Secondary => DiscordButtonStyle.Secondary
    case ChoiceStyle.Danger    => DiscordButtonStyle.Danger
    case ChoiceStyle.Success   => DiscordButtonStyle.Success

  /** Discord modals carry one custom id: `<form id>:<submit token>`, so the form id round-trips on submission (the
    * submit token is what the mediator MAC-verifies; the form id is informational).
    */
  def toModal(form: RenderedForm): DiscordModal =
    DiscordModal(
      customId = s"${form.id}:${form.submit}",
      title = form.title,
      fields = form.fields.map(f => DiscordModalField(f.key, f.label, f.placeholder, f.required, f.value))
    )
