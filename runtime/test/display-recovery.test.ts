import test from 'node:test';
import assert from 'node:assert/strict';
import {acquireDisplayWithRecovery,displayBackendRestarting,type DisplayRecoveryClock} from '../src/display-recovery.js';

const unavailable={isError:true,code:'NOT_READY',message:'Virtual display backend shizuku_unavailable; start Shizuku and authorize Pocket Agent in its UI'};
const binding={isError:true,code:'NOT_READY',message:'Shizuku display UserService startup is still pending; retry after startup completes.'};
function fakeClock() {
  let time=0;
  const delays:number[]=[];
  const clock:DisplayRecoveryClock={now:()=>time,pause:async ms=>{assert.ok(ms>0);delays.push(ms);time+=ms;}};
  return {clock,delays,elapsed:()=>time};
}

test('acquisition waits through missing Shizuku and pending UserService, notifying once',async()=>{
  const timing=fakeClock(),ready={status:'acquired',display_id:9};
  const results=[unavailable,binding,ready];let waits=0,calls=0;
  assert.equal(await acquireDisplayWithRecovery(()=>{calls++;return results.shift();},undefined,()=>waits++,timing.clock),ready);
  assert.equal(calls,3);assert.equal(waits,1);assert.deepEqual(timing.delays,[200,400]);
});

test('a stopped backend exhausts its bounded retry window and returns the actual failure',async()=>{
  const timing=fakeClock();let calls=0;
  assert.equal(await acquireDisplayWithRecovery(()=>{calls++;return unavailable;},undefined,undefined,timing.clock),unavailable);
  assert.equal(timing.elapsed(),12_000);
  assert.ok(calls<20);assert.ok(timing.delays.every(ms=>ms<=1000));
});

test('permission denial, startup timeout, uncertain bootstrap and task cleanup failures are not retried',async()=>{
  for(const failure of [
    {isError:true,code:'NOT_READY',message:'Virtual display backend permission_required; start Shizuku and authorize Pocket Agent in its UI'},
    {isError:true,code:'NOT_READY',message:'Shizuku is running, but its privileged UserService failed to start within 35 seconds. Check device and MediaTek compatibility.'},
    {isError:true,code:'RESULT_UNKNOWN',message:'Display bootstrap failed; native cleanup unconfirmed and lease retained'},
    {isError:true,code:'NOT_READY',message:'Previous display cleanup remains pending'},
    {isError:true,code:'NOT_READY',message:'Android rejected trusted display creation: SecurityException'},
  ]) {
    const timing=fakeClock();let calls=0;
    assert.equal(displayBackendRestarting(failure),false);
    assert.equal(await acquireDisplayWithRecovery(()=>{calls++;return failure;},undefined,undefined,timing.clock),failure);
    assert.equal(calls,1);assert.deepEqual(timing.delays,[]);
  }
});

test('cancellation during the watchdog wait prevents another acquisition',async()=>{
  const abort=new AbortController();let calls=0;
  const timing:DisplayRecoveryClock={now:()=>0,pause:async()=>{abort.abort();}};
  await assert.rejects(acquireDisplayWithRecovery(()=>{calls++;return unavailable;},abort.signal,undefined,timing),/cancelled/);
  assert.equal(calls,1);
});

test('abort interrupts the real pending timer immediately',async()=>{
  const abort=new AbortController();let calls=0;
  const pending=acquireDisplayWithRecovery(()=>{calls++;return unavailable;},abort.signal);
  abort.abort();
  await assert.rejects(pending,/cancelled/);
  assert.equal(calls,1);
});
