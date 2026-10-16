/** Mobile adaptation: coding SDK/resource discovery is intentionally excluded.
 * Runtime callers inject the app-owned Pi Agent executor into GenerationScheduler.
 * See docs/RUNTIME.md and the preserved upstream LICENSE. */
export const DEFAULT_EXECUTE_GENERATION_DEPENDENCIES = {};
export async function executeGeneration(): Promise<never> { throw new Error('Mobile host must inject a generation executor'); }
export function resolveModel(requested:any,current:any,registry:any):any {
  if (!requested || requested===current?.id || requested===`${current?.provider}/${current?.id}`) return {ok:true,value:current};
  const selected = registry?.[requested];
  return selected && selected.provider===current.provider && selected.baseUrl===current.baseUrl
    ? {ok:true,value:selected}
    : {ok:false,error:'Model is not in the discovered provider catalog. Use subagent action=models first.'};
}
export function resolveTaskCwd(cwd:string,requested?:string):any {
  return requested && requested!==cwd ? {ok:false,error:'Desktop working directories are unavailable on mobile.'}:{ok:true,value:cwd};
}
export function resolveRequestedSkills(_cwd:string,skills:readonly string[]):any {
  return skills.length?{ok:false,error:'Desktop skill discovery is unavailable on mobile.'}:{ok:true,value:[]};
}
