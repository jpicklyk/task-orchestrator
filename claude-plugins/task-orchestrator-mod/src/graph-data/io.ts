// What the data layer needs from the engine, as plain functions. The hooks module (index.ts) builds
// one from its `$` (`graphIo`); everything else in graph-data takes this instead of `$`, because
// the plugin validator follows `$` only into functions of the same file. It also lets the unit
// tests run the whole layer over an in-memory implementation.
import type { GraphSnapshot, GraphStatus } from '../../types'

export type SpawnChunk = { stream: 'stdout' | 'stderr'; text: string }

export interface GraphIo {
  /** Calls a TO tool and returns its parsed JSON text; rejects on an MCP error result or bad JSON. */
  callTool(tool: string, args: Record<string, unknown>): Promise<unknown>
  /** A file's text, relative to the working directory (or absolute); rejects when unreadable. */
  readFile(path: string): Promise<string>
  now(): Promise<number>
  after(ms: number, fn: () => void): { cancel(): void }
  every(ms: number, fn: () => void): { cancel(): void }
  sleep(ms: number, signal?: AbortSignal): Promise<void>
  /** A child process's output as a stream; leaving the loop (`return()`) kills it. */
  spawn(request: { argv: readonly string[]; input?: string }): AsyncGenerator<SpawnChunk, unknown>
  /** The graphScope atom's value. */
  readScope(): Promise<string | null>
  setSnapshot(value: GraphSnapshot): Promise<unknown>
  updateStatus(change: (value: GraphStatus) => GraphStatus): Promise<unknown>
  /** `TASK_ORCHESTRATOR_API_URL`, when set. */
  envApiUrl(): Promise<string | undefined>
  /** `TASK_ORCHESTRATOR_API_TOKEN`, when set. */
  envApiToken(): Promise<string | undefined>
  /** The home directory for the user-level client.json: `TASK_ORCHESTRATOR_HOME`, else `HOME`, else `USERPROFILE`. */
  homeDir(): Promise<string | undefined>
}
