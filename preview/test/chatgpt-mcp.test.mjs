import test from 'node:test';
import assert from 'node:assert/strict';
import {parseHTML} from '../../runtime/node_modules/linkedom/esm/index.js';
import {mountChatGptMcp} from '../native-chatgpt-mcp.js';
const flush=()=>new Promise(resolve=>setImmediate(resolve));
function fixture(){
 const {document}=parseHTML('<html><body></body></html>');let clock=1000,timer,stage='online',generation=1;const calls=[];
 const el=(tag,cls='',text='')=>{const node=document.createElement(tag);node.className=cls;node.textContent=text;return node;};
 const api=async(path,input)=>{calls.push({path,input});const action=path.split('/').at(-1);if(action==='stop')stage='stopped';if(action==='rotate')generation++;if(action==='pairing'||action==='rotate')return {code:`CODE-${generation}`,expiresAt:clock+300000};return {stage,publicUrl:'https://phone.example/mcp',connections:1};};
 const dispose=mountChatGptMcp(document.body,{api,el,field:(parent,label,value)=>{const node=el('input');node.value=value;node.setAttribute('aria-label',label);parent.append(node);return node;},button:(label,run)=>{const node=el('button','',label);node.onclick=run;return node;},heading(){},note(){},now:()=>clock,setTimer:fn=>{timer=fn;return 1;},clearTimer:()=>{timer=undefined;}});
 return {document,calls,dispose,button:label=>[...document.querySelectorAll('button')].find(node=>node.textContent===label),advance:async ms=>{clock+=ms;await timer?.();await flush();}};
}
test('settings show URL and expiring one-use code, rotate, and clear on stop',async()=>{
 const f=fixture();await flush();assert.equal(f.document.querySelector('output').textContent,'CODE-1');assert.equal(f.document.querySelector('input').value,'https://phone.example/mcp');
 assert.ok(f.document.body.textContent.includes('5:00'));await f.advance(1000);assert.ok(f.document.body.textContent.includes('4:59'));
 await f.button('Get a new code').onclick();assert.equal(f.document.querySelector('output').textContent,'CODE-2');
 await f.button('Copy pairing code').onclick();assert.deepEqual(f.calls.at(-1),{path:'/native/chatgpt-mcp/copy',input:{what:'code'}});
 await f.button('Stop MCP connection').onclick();assert.equal(f.document.querySelector('output').textContent,'—');assert.equal(f.document.querySelector('input').value,'');assert.equal(f.button('Copy pairing code').disabled,true);f.dispose();
});
test('expiry refreshes the pairing code and leaving settings erases displayed secrets',async()=>{
 const f=fixture();await flush();const before=f.calls.length;await f.advance(300001);assert.ok(f.calls.length>before);assert.ok(f.document.body.textContent.includes('5:00'));
 f.document.querySelector('[aria-label="ngrok token"]').value='private-token';f.dispose();assert.equal(f.document.querySelector('[aria-label="ngrok token"]').value,'');assert.equal(f.document.querySelector('output').textContent,'—');
 const count=f.calls.length;await f.advance(10000);assert.equal(f.calls.length,count);
});
