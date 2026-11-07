/** Director mode is on since this ISO instant; null while off. */
export type DirectorState = { since: string } | null

declare module 'claude-code' {
  interface PluginState {
    'director-indicator': { director: DirectorState }
  }
}
