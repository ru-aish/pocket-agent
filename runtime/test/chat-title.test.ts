import test from 'node:test';
import assert from 'node:assert/strict';
import {cleanChatTitle,generateChatTitle} from '../src/chat-title.js';

test('conversation titles remove presentation markup and stay short in any language',()=>{
  assert.equal(cleanChatTitle('  <strong>Title: "Weekly meal planning."</strong>  '),'Weekly meal planning');
  assert.ok(Array.from(cleanChatTitle('非常に長い会話のタイトル'.repeat(10))).length<=42);
  assert.ok(cleanChatTitle('one two three four five six seven eight').split(' ').length<=6);
});
test('title generation uses the connected provider with no phone tools or conversation mutation',async()=>{
  let calls=0;
  const title=await generateChatTitle({mode:'nebius',model:'fixture-model'},{apiKey:'fixture-secret'},'user: Help me plan vegetarian dinners for next week',new AbortController().signal,async(input,init)=>{
    calls++;assert.equal(new Headers(init?.headers).get('Authorization'),'Bearer fixture-secret');assert.equal(String(input),'https://api.tokenfactory.nebius.com/v1/chat/completions');
    const body=JSON.parse(String(init?.body)) as {tools?:unknown;messages:{role:string;content:string}[]};
    assert.ok(!body.tools||Array.isArray(body.tools)&&body.tools.length===0);
    assert.equal(body.messages.filter(message=>message.role==='system'||message.role==='developer').length,1);assert.match(JSON.stringify(body.messages),/vegetarian/);
    const chunks=[{id:'title',object:'chat.completion.chunk',created:1,model:'fixture-model',choices:[{index:0,delta:{role:'assistant',content:'Vegetarian dinner planning'},finish_reason:null}]},{id:'title',object:'chat.completion.chunk',created:1,model:'fixture-model',choices:[{index:0,delta:{},finish_reason:'stop'}]}];
    return new Response(chunks.map(chunk=>'data: '+JSON.stringify(chunk)+'\n\n').join('')+'data: [DONE]\n\n',{headers:{'Content-Type':'text/event-stream'}});
  });
  assert.equal(calls,1);assert.equal(title,'Vegetarian dinner planning');
});
