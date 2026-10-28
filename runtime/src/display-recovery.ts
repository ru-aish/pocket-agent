/** Only acquisition can be retried: phone actions may already have taken effect. */
export function displayBackendRestarting(result:any):boolean {
  if(!result?.isError||result.code!=='NOT_READY')return false;
  const message=String(result.message??'');
  return /virtual display backend (?:shizuku_unavailable|disconnected|binding)\b|shizuku (?:display )?(?:user)?service disconnected|shizuku display UserService startup is still pending/i.test(message);
}

function pause(ms:number,signal?:AbortSignal):Promise<void> {
  return new Promise((resolve,reject)=>{
    const cancelled=()=>{
      clearTimeout(timer);signal?.removeEventListener('abort',cancelled);
      reject(new Error('Display request cancelled'));
    };
    // Keep this timer referenced so the embedded Node pump can resume the tool.
    const timer=setTimeout(()=>{signal?.removeEventListener('abort',cancelled);resolve();},ms);
    signal?.addEventListener('abort',cancelled,{once:true});
    if(signal?.aborted)cancelled();
  });
}

export interface DisplayRecoveryClock {
  now():number;
  pause(ms:number,signal?:AbortSignal):Promise<void>;
}
const clock:DisplayRecoveryClock={now:()=>performance.now(),pause};

/** Let Shizuku's watchdog restore its binder without blocking the Node isolate. */
export async function acquireDisplayWithRecovery(
  acquire:()=>any,signal?:AbortSignal,onWaiting?:()=>void,timing:DisplayRecoveryClock=clock,
):Promise<any> {
  const deadline=timing.now()+12_000;
  let waiting=false,delay=200;
  for(;;) {
    if(signal?.aborted)throw new Error('Display request cancelled');
    const result=acquire();
    if(!displayBackendRestarting(result)||timing.now()>=deadline)return result;
    if(!waiting){waiting=true;onWaiting?.();}
    const remaining=deadline-timing.now();
    if(remaining<=0)return result;
    await timing.pause(Math.min(delay,remaining),signal);
    delay=Math.min(delay*2,1000);
  }
}
