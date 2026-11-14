// Small text helpers of the dashboard. The words are the server's; only their first letter is
// raised when a figure is shown on its own, as a tile headline.

/** The server's figure as a sentence on its own: "goal reached on 5 of 7 days" reads "Goal reached ...". */
export function sentence(text: string): string {
  return text.length === 0 ? text : text.charAt(0).toUpperCase() + text.slice(1);
}
