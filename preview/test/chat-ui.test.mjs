import test from 'node:test';
import assert from 'node:assert/strict';
import {parseHTML} from '../../runtime/node_modules/linkedom/esm/index.js';
import {shortTitle} from '../short-title.mjs';
const tick=()=>new Promise(resolve=>setImmediate(resolve));
function dom(){
 const {window,document}=parseHTML('<html><body><div class="workspace"><div id="conversation"><div id="messages"></div></div><button id="voice-button"></button></div></body></html>');
 globalThis.document=document;globalThis.window=window;
 window.PocketNative={};window.pocketRequest=async()=>({models:[{id:'capable',supportedReasoningLevels:['low','high']},{id:'limited',supportedReasoningLevels:['low']}]});
 // Linkedom has no dialog lifecycle or writable select value.
 Object.defineProperty(window.HTMLSelectElement.prototype,'value',{configurable:true,get(){return this._value??this.options[0]?.value??'';},set(value){this._value=value;}});
 window.HTMLElement.prototype.showModal=function(){this.open=true;};window.HTMLElement.prototype.close=function(){this.open=false;};
 return document;
}
test('archive controls are replaced when restoring the live conversation',async()=>{
 const document=dom(),{reconcileMessages}=await import('../chat-view.js');
 const list=document.querySelector('#messages');list.innerHTML='<p>Read only</p><button>Back</button><article class="message"><h2>Old</h2><p class="message-body">Archived</p></article>';
 assert.equal(reconcileMessages(list,[{role:'user',text:'Live question'},{role:'assistant',text:'Live answer'}]),true);
 assert.equal(list.children.length,2);assert.deepEqual([...list.querySelectorAll('.message-body')].map(n=>n.textContent),['Live question','Live answer']);
 assert.equal(list.querySelector('button'),null);
});
test('picker keeps a newer dialog open after an older save completes and retains preferred effort',async()=>{
 const document=dom(),{mountModelPicker}=await import('../model-picker.js');
 const gate=Promise.withResolvers(),saved=[];let config={provider:'codex-account',model:'limited',reasoning:'low',preferredReasoning:'high'};
 const picker=mountModelPicker({getConfig:()=>config,save:async value=>{saved.push(value);await gate.promise;config={...config,...value};},onConnect(){}});
 const trigger=document.querySelector('.model-picker-trigger');assert.equal(trigger.getAttribute('aria-expanded'),'false');
 await picker.open();let dialog=document.querySelector('dialog');
 let selects=dialog.querySelectorAll('select'),model=selects[1];assert.equal(model.value,'limited');
 const apply=[...dialog.querySelectorAll('button')].find(n=>n.textContent==='Use model');
 const saving=apply.onclick();await tick();assert.equal(saved[0].reasoning,'low');assert.equal(saved[0].preferredReasoning,undefined);
 picker.close();await picker.open();gate.resolve();await saving;
 assert.equal(dialog.open,true);assert.equal(trigger.getAttribute('aria-expanded'),'true');
 model=dialog.querySelectorAll('select')[1];model.value='capable';model.onchange();
 assert.equal(dialog.querySelector('input').getAttribute('aria-valuetext'),'high');
 picker.close();assert.equal(trigger.getAttribute('aria-expanded'),'false');
});
test('canonical titles clean prefix-wrapped quotes and both Markdown edges',()=>{
 assert.equal(shortTitle('Title: "Weekly meal planning."'),'Weekly meal planning');
 assert.equal(shortTitle('**Weekly planning**'),'Weekly planning');
 assert.equal(shortTitle('<strong>Conversation title: **Weekly planning**</strong>'),'Weekly planning');
});
