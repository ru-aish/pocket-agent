import test from 'node:test';
import assert from 'node:assert/strict';
import {Type} from 'typebox';
import {DesktopToolRouter} from '../src/desktop-connector.js';

function fixture(){
 const controller=new AbortController(),records:any[]=[];let calls=0,persist=true;
 const tool={name:'read_fixture',description:'Read fixture',parameters:Type.Object({value:Type.String()}),execute:async(_id:string,args:any)=>{calls++;return {content:[{type:'text',text:args.value}]};}};
 const router=new DesktopToolRouter({tools:owner=>owner==='restricted'?[]:[tool],persist:()=>persist,record:(...args)=>records.push(args)});
 const session=router.open('main',['read_fixture'],controller.signal);
 const args=(request='one',value='receipt')=>({pocket_session:session.sessionId,pocket_request:request,value});
 return {router,controller,session,args,tool,records,calls:()=>calls,failSave:()=>{persist=false;}};
}
test('native connector validates schemas and restricts calls to active worker grants',async()=>{
 const f=fixture();
 assert.equal(f.router.list()[0].inputSchema.required.includes('pocket_session'),true);
 await assert.rejects(f.router.call('grant','read_fixture',{...f.args(),value:42}));
 await assert.rejects(f.router.call('grant','unknown',f.args()),/not granted/);
 const worker=f.router.open('restricted',['read_fixture'],f.controller.signal);
 await assert.rejects(f.router.call('grant','read_fixture',{...f.args(),pocket_session:worker.sessionId}),/not granted/);
 await f.router.call('grant','read_fixture',f.args());
 await assert.rejects(f.router.call('another-grant','read_fixture',f.args('two')),/another authorized/);
 f.controller.abort();await assert.rejects(f.router.call('grant','read_fixture',f.args()),/inactive/);
 assert.equal(f.calls(),1);
});
test('concurrent retries and completed retries execute a mutation exactly once',async()=>{
 const f=fixture(),gate=Promise.withResolvers<void>();
 f.tool.execute=async(_id,args)=>{await gate.promise;return {content:[{type:'text',text:args.value}]};};
 const first=f.router.call('grant','read_fixture',f.args()),second=f.router.call('grant','read_fixture',f.args());
 assert.equal(f.router.active,true);gate.resolve();assert.deepEqual(await first,await second);
 assert.equal(f.records.length,2);assert.deepEqual(await f.router.call('grant','read_fixture',f.args()),await first);
 assert.equal(f.records.length,2);
 await assert.rejects(f.router.call('grant','read_fixture',f.args('one','different')),/different arguments/);
 f.session.close();assert.equal(f.router.active,false);
 await assert.rejects(f.router.call('grant','read_fixture',f.args()),/inactive/);
});
test('failed durable reservation never executes and interrupted receipts never replay',async()=>{
 const f=fixture();f.failSave();await assert.rejects(f.router.call('grant','read_fixture',f.args()),/Nothing was executed/);assert.equal(f.calls(),0);
 const g=fixture();await g.router.call('grant','read_fixture',g.args());
 const receipts=structuredClone(g.router.snapshot());for(const receipt of Object.values(receipts)){receipt.state='started';delete receipt.result;}
 g.router.restore(receipts);await assert.rejects(g.router.call('grant','read_fixture',g.args()),/outcome is unknown/);assert.equal(g.calls(),1);
 const restored=new DesktopToolRouter({tools:()=>[g.tool],persist:()=>true,record:()=>{}});restored.restore(receipts);
 await assert.rejects(restored.call('grant','read_fixture',g.args()),/inactive/);
});
test('failed result persistence does not repeat execution and strips durable screenshots',async()=>{
 const f=fixture();f.tool.execute=async()=>{f.failSave();return {content:[{type:'text',text:'done'},{type:'image',data:'private',mimeType:'image/png'}] as any};};
 await assert.rejects(f.router.call('grant','read_fixture',f.args()),/receipt could not be saved/);
 const retry=await f.router.call('grant','read_fixture',f.args());assert.equal(f.records.length,2);assert.deepEqual(retry.content,[{type:'text',text:'done'}]);
 assert.ok(!JSON.stringify(f.router.snapshot()).includes('private'));
});
