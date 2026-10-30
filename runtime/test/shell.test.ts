import test from 'node:test';
import assert from 'node:assert/strict';
import {nativeTools} from '../src/tools.js';
import {PocketRuntime} from '../src/runtime.js';
import {composePrompt} from '../src/prompts.js';
import {emptyAutonomy} from '../src/autonomy.js';

function host() {
 const calls:any[]=[],events:string[]=[];
 return {calls,events,call(method:string,args:any){calls.push({method,...args});return {status:'running',session_id:'test-session',images:args.args?.action==='capture'?[{data:'aW1hZ2U=',mimeType:'image/jpeg'}]:undefined};},
  authorizePhone(){events.push('authorized');},async ensureDisplay(owner:string):Promise<any>{events.push('display:'+owner);return {status:'acquired',display_id:81};},
  releaseScreen(owner:string){events.push('released:'+owner);return {status:'released'};},dropPriorImages(owner:string){events.push('drop:'+owner);}};
}
test('shell remains available under every grant and forwards arbitrary commands unchanged without allocating a display',async()=>{
 for(const grant of [undefined,[],['phone_capabilities']]) {
  const h=host(),tool=nativeTools(h,'worker',grant).find(t=>t.name==='phone_shell')!;
  assert.ok(tool);
  const command='mkdir -p "$POCKET_SCRIPTS_DIR"; cat > "$POCKET_SCRIPTS_DIR/task.sh" <<\'SCRIPT\'\nprintf "%s\\n" "$HOME"\nSCRIPT\nsh "$POCKET_SCRIPTS_DIR/task.sh"';
  const result=await tool.execute('one',{action:'exec',command,cwd:'/data/local/tmp'});
  assert.equal(h.calls.length,1);assert.equal(h.calls[0].args.command,command);assert.equal(h.calls[0].owner,'worker');
  assert.equal(result.details.session_id,'test-session');assert.ok(!h.events.some(e=>e.startsWith('display:')));
 }
});
test('shell capture acquires a lease and returns a model image without calling accessibility',async()=>{
 const h=host(),tool=nativeTools(h,'researcher',[]).find(t=>t.name==='phone_shell')!;
 const result=await tool.execute('capture',{action:'capture'});
 assert.ok(h.events.includes('display:researcher'));assert.ok(h.events.includes('drop:researcher'));
 assert.equal(h.calls[0].name,'phone_shell');assert.equal(h.calls[0].args.action,'capture');
 assert.deepEqual(result.content[1],{type:'image',data:'aW1hZ2U=',mimeType:'image/jpeg'});
 assert.equal(h.calls.some(c=>c.name==='phone_observe'),false);
});
test('shell acquire and release use the shared display pool while explicit capture does not allocate another display',async()=>{
 const h=host(),tool=nativeTools(h,'worker',[]).find(t=>t.name==='phone_shell')!;
 assert.equal((await tool.execute('acquire',{action:'acquire'})).details.display_id,81);
 assert.equal(h.calls.length,0);
 await tool.execute('capture',{action:'capture',display_id:82});
 assert.equal(h.events.filter(e=>e.startsWith('display:')).length,1);
 await tool.execute('release',{action:'release'});assert.ok(h.events.includes('released:worker'));
});
test('cancelled shell commands never dispatch and a failed allocation never dispatches capture',async()=>{
 const h=host(),tool=nativeTools(h,'worker',[]).find(t=>t.name==='phone_shell')!;
 const cancelled=new AbortController();cancelled.abort();
 await assert.rejects(tool.execute('cancel',{action:'exec',command:'echo no'},cancelled.signal),/Cancelled/);assert.equal(h.calls.length,0);
 h.ensureDisplay=async()=>({isError:true,code:'NOT_READY'});
 const result=await tool.execute('capture',{action:'capture'});assert.equal(result.isError,true);assert.equal(h.calls.length,0);
});
test('main and all built-in workers advertise shell access and unattended shell has no per-action approval gate',()=>{
 const bridge={call(method:string,json:string){let result:any={};if(method==='credentials.get')result={authMode:'scripted'};if(method==='autonomy.load')result=emptyAutonomy();return JSON.stringify({ok:true,result});},emit(){}};
 const runtime=new PocketRuntime(bridge);
 assert.ok(runtime.main.agent.state.tools.some((t:any)=>t.name==='phone_shell'));
 for(const definition of runtime.registry.agents.values())assert.ok(definition.tools?.includes('phone_shell'),definition.name);
 const task=runtime.autonomy.execute('task',{action:'create',title:'Run script',objective:'Use saved script'});
 const snapshot=runtime.autonomy.snapshot();
 (runtime as any).autonomyOwner=()=> 'auto:worker';
 (runtime as any).autonomy.snapshot=()=>({...snapshot,tasks:[{...task,status:'active'}],runs:[{conversationId:'worker',taskId:task.id,approvedTools:[]}]});
 (runtime as any).call=(method:string)=>method==='autonomy.status'?{enabled:true}:{};
 assert.doesNotThrow(()=>runtime.authorizePhone('worker','phone_shell',{action:'exec',command:'echo available'}));
 assert.throws(()=>runtime.authorizePhone('worker','phone_sms',{}),/approval/);
});
test('every composed prompt explains independent execution, persistent scripts, screenshots and general access',()=>{
 const prompt=composePrompt('Research worker.','');
 for(const term of ['phone_shell','without a PC','scripts_directory','memory_save','action=capture','not a shell command restriction','session_id'])assert.ok(prompt.includes(term),term);
});

test('standing shell grant and implicit capture allocation work inside phone_batch',async()=>{
 const h=host(),tool=nativeTools(h,'worker',['phone_batch']).find(t=>t.name==='phone_batch')!;
 await tool.execute('batch',{calls:[{name:'phone_shell',args:{action:'capture'}}]});
 assert.ok(h.events.includes('display:worker'));
 assert.equal(h.calls[0].name,'phone_batch');assert.equal(h.calls[0].args.calls[0].name,'phone_shell');
});
