/** Mobile adaptation: fixed application agent definitions; no directory discovery. */
import type { ModelThinkingLevel as PiThinkingLevel } from "@earendil-works/pi-ai";
import type { SpawnRequest } from "./schema.js";
// Preserve desktop thinking values through worker configuration and checkpoints.
export type ModelThinkingLevel = PiThinkingLevel | "standard" | "extended";
export const MODEL_THINKING_LEVELS = ["off", "minimal", "low", "medium", "high", "xhigh", "max", "standard", "extended"] as const;

export function isModelThinkingLevel(value: unknown): value is ModelThinkingLevel {
  return typeof value === "string" && (MODEL_THINKING_LEVELS as readonly string[]).includes(value);
}

export type AgentSource = "user" | "project";

export interface AgentDefinition {
  name: string;
  description: string;
  model?: string;
  thinking?: ModelThinkingLevel;
  tools?: string[];
  skills?: string[];
  systemPrompt: string;
  source: AgentSource;
  sourcePath?: string;
}

export type AgentDefinitionSummary = Readonly<Pick<
  AgentDefinition,
  "name" | "description" | "source" | "sourcePath"
>>;

export interface RequestedExecutionConfig {
  readonly model?: string;
  readonly thinking?: ModelThinkingLevel;
  readonly skills?: readonly string[];
  readonly tools?: readonly string[];
  readonly cwd?: string;
}

export interface EffectiveExecutionConfig {
  readonly model?: string;
  readonly thinking?: ModelThinkingLevel;
  readonly cwd: string;
  readonly skills: readonly string[];
  readonly tools: readonly string[];
}

export type ExecutionOverrides = Readonly<Pick<RequestedExecutionConfig, "model" | "thinking">>;

export class AgentRegistry {
  readonly agents = new Map<string, AgentDefinition>();
}
export function summarizeAgentDefinition(definition: AgentDefinition): AgentDefinitionSummary {
  return {
    name: definition.name,
    description: definition.description,
    source: definition.source,
    ...(definition.sourcePath ? { sourcePath: definition.sourcePath } : {}),
  };
}

/** Resolve spawn-over-definition precedence. */
export function resolveRequestedConfig(
  config: AgentDefinition,
  spawn: SpawnRequest,
): RequestedExecutionConfig {
  const skills = spawn.skills ?? config.skills;
  return {
    model: spawn.model ?? config.model,
    thinking: spawn.thinking ?? config.thinking,
    skills: skills !== undefined ? [...skills] : undefined,
    tools: config.tools !== undefined ? [...config.tools] : undefined,
    cwd: spawn.cwd,
  };
}
