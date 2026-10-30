import {emptyAutonomy} from '../src/autonomy.js';
import test from 'node:test';
import assert from 'node:assert/strict';
import {PocketRuntime} from '../src/runtime.js';
import {memoryTools,recallBlock} from '../src/memory.js';
import {delay} from '../src/scripted.js';

class MemoryBridge {
  state:any={};calls:any[]=[];events:any[]=[];historyCalls:any[]=[];failHistory=false;
  records:any[]=[{id:1,scope:'global',category:'preference',content:'Use existing GitHub branches.',confirmed:true,updatedAt:12,source:'agent'}];
  history:any[]=[
    {id:1,threadId:'unrelated',owner:'main',role:'user',timestamp:9,text:'A different archived conversation.'},
    {id:7,threadId:'old-main',owner:'main',role:'assistant',timestamp:10,text:'The motherboard warranty claim was accepted.'},
    {id:8,threadId:'worker-a',owner:'worker-1',role:'assistant',timestamp:11,text:'Private archive for worker one.'},
    {id:9,threadId:'worker-b',owner:'worker-2',role:'assistant',timestamp:12,text:'Private archive for worker two.'},
  ];
  call(method:string,json:string):string {
    const args=JSON.parse(json);
    let result:any={};
    if(method==='credentials.get')result={authMode:'scripted'};
    if(method==='store.load')result=this.state;
    if(method==='store.save')this.state=structuredClone(args.state);
    if(method.startsWith('history.')) {
      this.historyCalls.push({method,...args});if(this.failHistory)throw new Error('Archive unavailable');
      const visible=this.history.filter(x=>args.owner==='main'||x.owner==='main'||x.owner===args.owner);
      if(method==='history.search')result={messages:visible.filter(x=>String(x.text).toLowerCase().includes(String(args.query).toLowerCase()))};
      if(method==='history.read') {
        const record=visible.find(x=>x.id===args.id),offset=args.offset??0;
        const chars=record?[...record.text]:[];
        result={message:record?{...record,text:chars.slice(offset,offset+(args.limit??4000)).join(''),offset,nextOffset:offset+4000<chars.length?offset+4000:null}:{}};
      }
    }
    if(method==='memory') {
      this.calls.push(args);
      if(args.request.action==='save')result={saved:true,id:2};
      else if(args.request.action==='forget')result={deleted:args.owner==='main'};
      else result={memories:this.records};
    }
    if(method==='tool')result={available:true,owner:args.owner};
    if(method==='autonomy.load')result=emptyAutonomy();
    if(method==='autonomy.status')result={enabled:false};
    return JSON.stringify({ok:true,result});
  }
  emit(j:string):void{this.events.push(JSON.parse(j));}
}
const unwrap=(result:any)=>result.details;
async function idle(runtime:PocketRuntime){
  const limit=Date.now()+4000;
  while(runtime.state().active){
    if(Date.now()>limit)throw Error('Runtime did not settle');
    await delay(10);
  }
}
test('main and worker memory tools honor grants and local/global write defaults',async()=>{
  const b=new MemoryBridge();
  const host={call:(method:string,args:any)=>JSON.parse(b.call(method,JSON.stringify(args))).result};
  const main=memoryTools(host,'main');
  assert.deepEqual(main.map(t=>t.name),['memory_search','memory_profile','memory_save','memory_forget','history_search','history_read']);
  await main.find(t=>t.name==='memory_save').execute('a',{key:'pref:git',content:'Use existing branches.',category:'preference',confirmed:true});
  assert.equal(b.calls.at(-1).request.scope,'global');
  const worker=memoryTools(host,'worker-1');
  await worker.find(t=>t.name==='memory_save').execute('b',{key:'task:abc',content:'Done.',category:'episodic'});
  assert.equal(b.calls.at(-1).request.scope,'thread');
  assert.equal(b.calls.at(-1).owner,'worker-1');
  assert.deepEqual(memoryTools(host,'worker-1',['memory_search','memory_profile']).map(t=>t.name),['memory_search','memory_profile']);
  const archived=await main.find(t=>t.name==='history_search').execute('h',{query:'motherboard'});
  assert.match(unwrap(archived).messages[0].text,/warranty claim/);
  const full=await main.find(t=>t.name==='history_read').execute('read',{id:7});
  assert.equal(unwrap(full).message.id,7);
  assert.equal(unwrap(full).message.text,b.history.find(m=>m.id===7).text);
  const memory=await worker.find(t=>t.name==='memory_search').execute('c',{query:'GitHub'});
  assert.equal(unwrap(memory).memories[0].id,1);
});
test('saved memories enter model context automatically, without duplicating recall across turns',async()=>{
  const b=new MemoryBridge(),r=new PocketRuntime(b);
  r.dispatch(JSON.stringify({action:'send',text:'Continue GitHub changes'}));
  await idle(r);
  const system=r.main.messages[0];
  assert.equal(system.role,'system');
  assert.match(system.content,/Use existing GitHub branches/);
  assert.match(system.content,/untrusted data/);
  assert.ok(b.calls.some(c=>c.request.action==='profile'));
  assert.ok(b.calls.some(c=>c.request.action==='search'));
  r.dispatch(JSON.stringify({action:'send',text:'Continue GitHub work'}));
  await idle(r);
  const latest=r.main.messages[0].content;
  assert.equal(latest.split('Use existing GitHub branches').length-1,1);
  assert.equal(r.state().lastError,null);
});
test('researchers can save script notes while memory data does not grant deletion',()=>{
  const b=new MemoryBridge(),r=new PocketRuntime(b);
  const researcher=r.registry.agents.get('researcher')!;
  assert.ok(researcher.tools!.includes('memory_search'));
  assert.ok(researcher.tools!.includes('memory_profile'));
  assert.ok(researcher.tools!.includes('memory_save'));
  assert.ok(researcher.tools!.includes('phone_shell'));
  assert.ok(!researcher.tools!.includes('memory_forget'));
  assert.ok(researcher.tools!.includes('history_search'));
  assert.ok(researcher.tools!.includes('history_read'));
  const block=recallBlock(r,'main','GitHub');
  assert.ok(block.includes('confirmed'));
  assert.match(recallBlock(r,'main','motherboard'),/The motherboard warranty claim was accepted/);
});
test('offline history fixture traverses Pi search and full-message tools',async()=>{
  const b=new MemoryBridge(),r=new PocketRuntime(b);
  r.dispatch(JSON.stringify({action:'send',text:'history fixture search motherboard'}));
  await idle(r);
  assert.ok(r.main.messages.some((m:any)=>m.role==='toolResult'&&m.toolName==='history_search'));
  assert.match(JSON.stringify(r.main.messages.at(-1)),/warranty claim was accepted/);
  r.dispatch(JSON.stringify({action:'send',text:'history fixture read 7'}));
  await idle(r);
  assert.ok(r.main.messages.some((m:any)=>m.role==='toolResult'&&m.toolName==='history_read'));
  assert.match(JSON.stringify(r.main.messages.at(-1)),/warranty claim was accepted/);
  assert.equal(r.state().lastError,null);
});

test('history reads select IDs, forward worker scopes, and paginate long escaped text',async()=>{
  const b=new MemoryBridge();
  const host={call:(method:string,args:any)=>JSON.parse(b.call(method,JSON.stringify(args))).result};
  const worker=memoryTools(host,'worker-1');
  const search=await worker.find(t=>t.name==='history_search').execute('s',{query:'archive'});
  assert.ok(unwrap(search).messages.some((m:any)=>m.id===8));
  assert.ok(!unwrap(search).messages.some((m:any)=>m.id===9));
  assert.equal(b.historyCalls.at(-1).owner,'worker-1');
  const denied=await worker.find(t=>t.name==='history_read').execute('d',{id:9});
  assert.deepEqual(unwrap(denied).message,{});
  assert.equal((await worker.find(t=>t.name==='history_read').execute('r',{id:8})).details.message.text,'Private archive for worker one.');
  b.history.push({id:10,threadId:'large',owner:'main',role:'assistant',text:'\u0001😀'.repeat(18000)});
  const read=memoryTools(host,'main').find(t=>t.name==='history_read');
  let offset=0,text='';
  do {
    const result=await read.execute('page',{id:10,offset});assert.equal(result.isError,false);
    text+=result.details.message.text;offset=result.details.message.nextOffset;
  }while(offset!==null);
  assert.equal(text,b.history.at(-1).text);
});
test('recall isolates history errors and respects actual grants',()=>{
  const b=new MemoryBridge(),r=new PocketRuntime(b);
  b.failHistory=true;
  assert.match(recallBlock(r,'main','motherboard'),/Use existing GitHub branches/);
  b.failHistory=false;b.historyCalls=[];
  const restricted=recallBlock(r,'main','motherboard',['memory_profile']);
  assert.match(restricted,/Use existing GitHub branches/);assert.ok(!restricted.includes('warranty claim'));
  assert.equal(b.historyCalls.length,0);
  assert.equal(recallBlock(r,'main','motherboard',[]),'');
});
test('worker prompt injection honors a restricted tool grant',async()=>{
  const b=new MemoryBridge(),r=new PocketRuntime(b);
  r.registry.agents.set('restricted',{name:'restricted',description:'Restricted',source:'project',systemPrompt:'Restricted worker',tools:['phone_capabilities']});
  const started=r.startTasks([{kind:'spawn',agent:'restricted',label:'Restricted',prompt:'motherboard'}],{initiatedBy:'user'});
  assert.equal(started.starts[0].ok,true);await idle(r);
  const id=started.starts[0].conversationId;
  const system=(r as any).sessions.get(id).messages[0].content;
  assert.ok(!system.includes('warranty claim'));assert.ok(!system.includes('Use existing GitHub branches'));
  assert.equal(b.historyCalls.length,0);
});
