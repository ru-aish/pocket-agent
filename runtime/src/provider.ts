/** Selected Pi adapters. Agent loops and tool execution stay inside Android. */
import type { StreamFn } from '@earendil-works/pi-agent-core';
import type { AssistantMessage, Model } from '@earendil-works/pi-ai';
import { streamSimple } from '@earendil-works/pi-ai/api/openai-responses';
import { streamSimple as completionsStreamSimple } from '@earendil-works/pi-ai/api/openai-completions';
import { streamSimple as codexStreamSimple } from '@earendil-works/pi-ai/api/openai-codex-responses';
import { AssistantMessageEventStream } from '@earendil-works/pi-ai/utils/event-stream';
import { SCRIPT_MODEL, scriptedStream } from './scripted.js';
import {CODEX_RESPONSES_URL,ensureCodexCredentials,redactProviderSecrets,type CodexCredentialInput} from './codex-auth.js';
export {registerCodexCredentialUpdater,redactProviderSecrets} from './codex-auth.js';

export const REASONING_LEVELS = ['minimal','low','medium','high','xhigh'] as const;
export type ReasoningLevel = typeof REASONING_LEVELS[number];
export function normalizeReasoning(value: unknown): ReasoningLevel {
  return typeof value === 'string' && (REASONING_LEVELS as readonly string[]).includes(value)
    ? value as ReasoningLevel : 'low';
}
export interface ProviderConfig {
  mode?: 'scripted' | 'openai' | 'codex' | 'codex-account' | 'opencode' | 'nebius';
  endpoint?: string;
  model?: string;
  reasoning?: ReasoningLevel;
}
export interface ProviderCredentials extends CodexCredentialInput {
  endpoint?: string;
  model?: string;
  reasoning?: ReasoningLevel;
  reasoningModel?: string;
  reasoningLevels?: ReasoningLevel[];
  authMode?: string;
}
type ProviderModel = Model<'openai-responses'> | Model<'openai-codex-responses'> | Model<'openai-completions'>;
export const OPENCODE_BASE_URL = 'https://opencode.ai/inference/openai/v1';
export const NEBIUS_BASE_URL = 'https://api.tokenfactory.nebius.com/v1';
function providerInput(mode: string, id: string): Array<'text'|'image'> {
  const normalized = id.toLowerCase();
  const vision = /(^|[/._-])(vl|vision|omni)([/._-]|$)/.test(normalized)
    || normalized === 'zai-org/glm-5.3-flash'
    || normalized === 'glm-5.3-flash-free';
  return vision ? ['text','image'] : ['text'];
}
function accountMode(config: ProviderConfig, credentials: ProviderCredentials): boolean {
  return (credentials.authMode || config.mode) === 'codex-account';
}
function selectedMode(config: ProviderConfig, credentials: ProviderCredentials): string {
  return credentials.authMode || config.mode || 'openai';
}
function isRelay(config: ProviderConfig, credentials: ProviderCredentials): boolean {
  return ['codex', 'codexRelay', 'codex-relay'].includes(selectedMode(config, credentials));
}
function advertisedReasoning(credentials: ProviderCredentials, id: string): ReasoningLevel[] | undefined {
  if (credentials.reasoningModel !== id || !Array.isArray(credentials.reasoningLevels)) return undefined;
  const levels = credentials.reasoningLevels.filter((value,index,all)=>
    (REASONING_LEVELS as readonly string[]).includes(value) && all.indexOf(value)===index);
  return levels.length ? levels : undefined;
}
function thinkingMap(levels: ReasoningLevel[] | undefined): any {
  if (!levels) return undefined;
  const supported = new Set(levels);
  return {
    off:null,
    minimal:supported.has('minimal')?'minimal':null,
    low:supported.has('low')?'low':null,
    medium:supported.has('medium')?'medium':null,
    high:supported.has('high')?'high':null,
    xhigh:supported.has('xhigh')?'xhigh':null,
    max:null,
  };
}
export function modelFor(config: ProviderConfig, credentials: ProviderCredentials = {}, override?: string): ProviderModel {
  if (config.mode === 'scripted') return SCRIPT_MODEL;
  const mode = selectedMode(config, credentials);
  const account = accountMode(config, credentials), relay = isRelay(config, credentials);
  if (account && (config.endpoint || credentials.endpoint)) throw new Error('Codex account inference uses a fixed provider endpoint; remove the endpoint override.');
  const fixed = mode === 'opencode' ? OPENCODE_BASE_URL : mode === 'nebius' ? NEBIUS_BASE_URL : undefined;
  const configured = credentials.endpoint || config.endpoint;
  if (fixed && configured && configured.replace(/\/$/, '') !== fixed) throw new Error('This provider uses a fixed API endpoint.');
  const endpoint = account ? CODEX_RESPONSES_URL : fixed || configured || (relay ? 'http://10.0.2.2:8787/v1' : 'https://api.openai.com/v1');
  const url = new URL(endpoint);
  if (url.username || url.password || url.search || url.hash) throw new Error('Provider endpoint must be a base URL without credentials, query or fragment.');
  if (relay) {
    if (url.protocol !== 'http:' || !['10.0.2.2', '127.0.0.1', 'localhost'].includes(url.hostname)) throw new Error('Codex relay is restricted to the Android emulator host or loopback.');
  } else if (url.protocol !== 'https:') throw new Error('Direct API providers require HTTPS.');
  const id = override || credentials.model || config.model || (account ? 'gpt-6-luna' : mode === 'opencode' ? 'big-pickle' : mode === 'nebius' ? 'zai-org/GLM-5.3-Flash' : 'gpt-6-sol');
  if (!/^[a-zA-Z0-9._:/+@-]{1,160}$/.test(id)) throw new Error('Invalid model identifier.');
  const levels=advertisedReasoning(credentials,id), levelMap=thinkingMap(levels);
  if (mode === 'opencode' || mode === 'nebius') {
    return {
      id, name: id, provider: mode, api: 'openai-completions',
      baseUrl: url.toString().replace(/\/$/, ''), input: providerInput(mode,id),
      reasoning: true, contextWindow: 128000, maxTokens: 4096,
      ...(levelMap?{thinkingLevelMap:levelMap}:{}),
      cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
      compat: { supportsDeveloperRole: false },
    };
  }
  return {
    id, name: id, provider: account ? 'openai-codex' : 'openai', api: account ? 'openai-codex-responses' : 'openai-responses',
    baseUrl: url.toString().replace(/\/$/, ''), input: ['text', 'image'],
    reasoning: true, contextWindow: 128000, maxTokens: 4096,
    ...(levelMap?{thinkingLevelMap:levelMap}:{}),
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
    // Pi emits strict:false for standard Responses. The Codex adapter emits
    // strict:null, which is explicitly patched to false below for optional fields.
    compat: { supportsMaxOutputTokens: !relay && !account, supportsStrictMode: true },
  };
}
function failure(model: ProviderModel | undefined, text: string, aborted = false): AssistantMessageEventStream {
  const stream = new AssistantMessageEventStream();
  const output: AssistantMessage = {
    role:'assistant',content:[],api:model?.api || 'openai-responses',provider:model?.provider || 'openai',model:model?.id || 'unconfigured',
    timestamp:Date.now(),stopReason:aborted ? 'aborted' : 'error',errorMessage:text.slice(0,512),
    usage:{input:0,output:0,cacheRead:0,cacheWrite:0,totalTokens:0,cost:{input:0,output:0,cacheRead:0,cacheWrite:0,total:0}},
  };
  stream.push({type:'error',reason:aborted ? 'aborted' : 'error',error:output});stream.end(output);return stream;
}
function fixedCompletionsFetch(mode: string, fetcher: typeof fetch, requestSignal?: AbortSignal): typeof fetch {
  const expected = mode === 'opencode'
    ? OPENCODE_BASE_URL + '/chat/completions'
    : mode === 'nebius'
      ? NEBIUS_BASE_URL + '/chat/completions'
      : undefined;
  if (!expected) return fetcher;
  return (async(input: any, init?: RequestInit)=>{
    if (String(input) !== expected) throw new Error('Unexpected fixed-provider inference destination.');
    const timeout = AbortSignal.timeout(120000);
    // Keep both the wall-clock timeout and the original conversation signal
    // attached after response headers arrive so a stalled SSE body cannot hang.
    const signal = AbortSignal.any([timeout,...(init?.signal?[init.signal]:[]),...(requestSignal?[requestSignal]:[])]);
    return fetcher(expected,{...init,redirect:'error',signal});
  }) as typeof fetch;
}

const CODEX_INFERENCE_ATTEMPTS = 3;
const RETRYABLE_HTTP = new Set([408,429,500,502,503,504]);
const RETRYABLE_NETWORK = new Set(['ECONNRESET','ECONNREFUSED','EPIPE','ETIMEDOUT','EAI_AGAIN','ENETUNREACH','UND_ERR_CONNECT_TIMEOUT','UND_ERR_SOCKET']);
function retryableNetwork(error: unknown): boolean {
  const value = error as any;
  const code = value?.cause?.code ?? value?.code;
  if (typeof code === 'string') return RETRYABLE_NETWORK.has(code);
  return error instanceof TypeError && /^(fetch failed|failed to fetch|network request failed)$/i.test(error.message);
}
function retryDelay(headers: Headers | undefined, attempt: number): number {
  const milliseconds = headers?.get('retry-after-ms');
  const after = headers?.get('retry-after');
  if (milliseconds && Number.isFinite(Number(milliseconds))) return Math.max(0,Number(milliseconds));
  if (after) {
    if (Number.isFinite(Number(after))) return Math.max(0,Number(after)*1000);
    const date = Date.parse(after);
    if (Number.isFinite(date)) return Math.max(0,date-Date.now());
  }
  return 250 * 2**attempt;
}
function retryWait(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve,reject)=>{
    const abort = ()=>{clearTimeout(timer);signal.removeEventListener('abort',abort);reject(new Error('Codex inference interrupted.'));};
    const timer = setTimeout(()=>{signal.removeEventListener('abort',abort);resolve();},ms);
    signal.addEventListener('abort',abort,{once:true});
    if (signal.aborted) abort();
  });
}
/** Inspect only a bounded error code, never expose provider bodies to Pi or stored diagnostics. */
async function terminalCodexError(response: Response): Promise<boolean> {
  if (!RETRYABLE_HTTP.has(response.status)) {await response.body?.cancel();return true;}
  if (!response.body) return false;
  const reader = response.body.getReader();const chunks: Uint8Array[] = [];let size = 0;
  try {
    while (true) {
      const next = await reader.read();if (next.done) break;
      size += next.value.byteLength;
      if (size > 32768) return true;
      chunks.push(next.value);
    }
    const body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    return ['insufficient_quota','usage_limit_reached','billing_hard_limit_reached','invalid_api_key','token_expired']
      .includes(body?.error?.code) || ['insufficient_quota','usage_limit_reached','usage_not_included'].includes(body?.error?.type);
  } catch {return false;}
  finally {try {await reader.cancel();} catch {/* Discard remote diagnostics. */}}
}

function fixedCodexFetch(fetcher: typeof fetch, requestSignal?: AbortSignal): typeof fetch {
  return (async(input: any, init?: RequestInit)=>{
    if (String(input) !== CODEX_RESPONSES_URL) throw new Error('Unexpected Codex inference destination.');
    const timeout = AbortSignal.timeout(120000);
    // Pi releases its header-only combined signal after fetch resolves. Keep
    // the original conversation signal attached for the entire response body.
    const signal = AbortSignal.any([timeout,...(init?.signal?[init.signal]:[]),...(requestSignal?[requestSignal]:[])]);
    // Retry only inference transport before successful headers. Pi never retries the
    // agent loop or streamed output, and credential refresh/persistence is outside here.
    let response!: Response;
    for (let attempt=0;attempt<CODEX_INFERENCE_ATTEMPTS;attempt++) {
      if (signal.aborted) throw new Error('Codex inference interrupted.');
      try {
        response = await fetcher(CODEX_RESPONSES_URL,{...init,redirect:'error',signal});
      } catch (error) {
        if (signal.aborted || !retryableNetwork(error) || attempt+1===CODEX_INFERENCE_ATTEMPTS)
          throw new Error('Codex inference connection failed.');
        await retryWait(retryDelay(undefined,attempt),signal);
        continue;
      }
      if (response.ok) break;
      const terminal = await terminalCodexError(response);
      const delay = retryDelay(response.headers,attempt);
      if (signal.aborted) throw new Error('Codex inference interrupted.');
      // Do not ignore a provider's long Retry-After or extend the shared 120s deadline.
      if (terminal || !RETRYABLE_HTTP.has(response.status) || attempt+1===CODEX_INFERENCE_ATTEMPTS || delay>5000) {
        return new Response(JSON.stringify({error:{message:`Codex inference rejected the request (HTTP ${response.status}).`}}),{status:response.status,headers:{'content-type':'application/json'}});
      }
      await retryWait(delay,signal);
    }
    if (!response.body) throw new Error('Codex returned no event stream.');
    const reader = response.body.getReader();let bytes = 0;
    const body = new ReadableStream<Uint8Array>({
      async pull(controller) {
        try {
          const next = await reader.read();
          if (next.done) {controller.close();return;}
          bytes += next.value.byteLength;
          if (bytes > 16*1024*1024) {await reader.cancel();throw new Error('Codex response exceeded its size limit.');}
          controller.enqueue(next.value);
        } catch {controller.error(new Error('Codex event stream ended unexpectedly.'));}
      },
      cancel: reason=>reader.cancel(reason),
    });
    return new Response(body,{status:response.status,headers:response.headers});
  }) as typeof fetch;
}

export function makeStream(config: ProviderConfig, credentials: ProviderCredentials = {}, owner: any): StreamFn {
  if (config.mode === 'scripted') return scriptedStream(owner);
  return async(_model, context, options)=>{
    let model: ProviderModel | undefined;
    const account = accountMode(config,credentials);
    const mode = selectedMode(config,credentials);
    try {
      model = modelFor(config,credentials,_model?.id);
      if (options?.signal?.aborted) return failure(model,'Request cancelled.',true);
      let apiKey = credentials.apiKey;
      if (account) apiKey = (await ensureCodexCredentials(credentials,{signal:options?.signal,fetch:options?.fetch})).accessToken;
      else if (mode === 'opencode' && !apiKey?.trim()) return failure(model,'Configure an OpenCode Console service-account key before sending.');
      else if (mode === 'nebius' && !apiKey?.trim()) return failure(model,'Configure a Nebius API key before sending.');
      else if (!apiKey?.trim()) return failure(model,'Configure a provider credential before sending.');
      if (options?.signal?.aborted) return failure(model,'Request cancelled.',true);
      const reasoning=normalizeReasoning(credentials.reasoning ?? config.reasoning);
      const supported=advertisedReasoning(credentials,model.id);
      if (supported && !supported.includes(reasoning))
        return failure(model,`Reasoning effort ${reasoning} is not supported by ${model.id}. Choose one of: ${supported.join(', ')}.`);
      if (account && !supported && reasoning!=='low')
        return failure(model,'Discover models before using a non-Low reasoning effort with Codex account auth.');
      const shared = {...options,apiKey,transport:'sse' as const,maxRetries:0,timeoutMs:120000,reasoning};
      const source = model.api === 'openai-codex-responses'
        ? codexStreamSimple(model,context,{
            ...shared,fetch:fixedCodexFetch(options?.fetch ?? globalThis.fetch,options?.signal),
            onPayload:async(raw,selected)=>{
              const replacement = await options?.onPayload?.(raw,selected);
              const body = (replacement ?? raw) as any;
              return {...body,model:model!.id,store:false,stream:true,
                ...(Array.isArray(body.tools) ? {tools:body.tools.map((tool:any)=>tool.type==='function'?{...tool,strict:false}:tool)} : {})};
            },
          })
        : model.api === 'openai-completions'
          ? completionsStreamSimple(model,context,{
              ...shared,
              fetch:fixedCompletionsFetch(mode,options?.fetch ?? globalThis.fetch,options?.signal),
            })
          : streamSimple(model,context,shared);
      const guarded = new AssistantMessageEventStream();
      void(async()=>{
        try {
          for await (const event of source) {
            if (event.type === 'error') {
              if (account) {
                // Do not persist raw provider diagnostics or remote failure bodies.
                event.error = {...event.error,errorMessage:options?.signal?.aborted?'Request cancelled.':'Codex inference failed; check account sign-in and availability.'};
                delete event.error.diagnostics;
              } else event.error.errorMessage = redactProviderSecrets(event.error.errorMessage || 'Provider request failed.',credentials).slice(0,512);
            }
            guarded.push(event);
          }
          // Terminal events carry the sanitized final message; do not replace it
          // with the upstream stream's unredacted error result.
          guarded.end();
        } catch {
          const aborted = !!options?.signal?.aborted;
          const terminal = await failure(model,aborted?'Request cancelled.':'Provider stream failed; no retry was attempted.',aborted).result();
          guarded.push({type:'error',reason:aborted?'aborted':'error',error:terminal});guarded.end(terminal);
        }
      })();
      return guarded;
    } catch (error) {
      const aborted = !!options?.signal?.aborted;
      const message = account ? (error instanceof Error && /^(Invalid Codex|Codex account|Codex refresh|Codex token|Secure Codex|Codex account inference)/.test(error.message) ? error.message : 'Codex account connection failed; sign in again.') : error instanceof Error ? error.message : 'Provider configuration failed.';
      return failure(model,aborted?'Request cancelled.':redactProviderSecrets(message,credentials),aborted);
    }
  };
}
