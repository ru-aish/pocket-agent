// Keep runtime event bursts from flooding the native bridge. An event received
// during an in-flight read schedules one trailing read, so the final state wins.
export function createRefreshScheduler(refresh,{delay=100,setTimer=setTimeout,clearTimer=clearTimeout}={}) {
  let timer=null,running=false,dirty=false;
  function schedule(){dirty=true;if(timer===null&&!running)timer=setTimer(flush,delay);}
  async function flush(){timer=null;if(!dirty||running)return;dirty=false;running=true;try{await refresh();}finally{running=false;if(dirty)schedule();}}
  schedule.cancel=()=>{dirty=false;if(timer!==null)clearTimer(timer);timer=null;};
  return schedule;
}
