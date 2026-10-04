/** "Home Base — Igoumenitsa" → "Igoumenitsa" */
export function shortPlaceName(name: string): string {
  return name.replace(/^.*—\s*/, '') || name;
}
