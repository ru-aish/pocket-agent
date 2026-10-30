import test from 'node:test';
import assert from 'node:assert/strict';
globalThis.window={};
const {compactModelName,reasoningLevels}=await import('../model-picker.js');
test('model chips omit provider paths and bound long model names',()=>{
 assert.equal(compactModelName('provider/gpt-6.1'),'GPT 6.1');
 assert.equal(compactModelName('provider/a-very-long-model-name'),'a-very-long…');
 assert.equal(compactModelName(''),'Model');
});
test('reasoning slider uses only advertised levels in increasing order',()=>{
 assert.deepEqual(reasoningLevels({supportedReasoningLevels:['high','low','medium']}),['low','medium','high']);
 assert.deepEqual(reasoningLevels({}),[]);
 assert.deepEqual(reasoningLevels({supportedReasoningLevels:[]}),[]);
});
