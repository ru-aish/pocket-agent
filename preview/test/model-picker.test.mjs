import test from 'node:test';
import assert from 'node:assert/strict';
globalThis.window={};
const {compactModelName,reasoningLevels}=await import('../model-picker.js');
test('model chips omit provider paths and bound long model names',()=>{
 assert.equal(compactModelName('provider/gpt-6.1'),'GPT 6.1');
 assert.equal(compactModelName('provider/a-very-long-model-name').length,12);
 assert.equal(compactModelName(''),'Model');
});
test('reasoning slider uses only advertised levels in increasing order',()=>{
 assert.deepEqual(reasoningLevels({supportedReasoningLevels:['high','low','medium']}),['low','medium','high']);
 assert.deepEqual(reasoningLevels({}),[]);
 assert.deepEqual(reasoningLevels({supportedReasoningLevels:[]}),[]);
});
test('catalog discovery is cached, shared in flight, and invalidated explicitly',async()=>{
 const {createModelCatalog}=await import('../model-picker.js');let loads=0,release;
 const cache=createModelCatalog(()=>{loads++;return new Promise(resolve=>{release=resolve;});});
 const first=cache.get('codex-account'),second=cache.get('codex-account');assert.equal(first,second);
 await Promise.resolve();assert.equal(loads,1);release([{id:'model-a'}]);assert.deepEqual(await first,[{id:'model-a'}]);
 assert.deepEqual(await cache.get('codex-account'),[{id:'model-a'}]);assert.equal(loads,1);
 cache.clear();const third=cache.get('codex-account');await Promise.resolve();assert.equal(loads,2);release([]);await third;
});
test('failed model discovery can retry without poisoning the cache',async()=>{
 const {createModelCatalog}=await import('../model-picker.js');let attempts=0;
 const cache=createModelCatalog(async()=>{if(++attempts===1)throw Error('offline');return [{id:'recovered'}];});
 await assert.rejects(cache.get('codex-account'),/offline/);assert.deepEqual(await cache.get('codex-account'),[{id:'recovered'}]);assert.equal(attempts,2);
});
